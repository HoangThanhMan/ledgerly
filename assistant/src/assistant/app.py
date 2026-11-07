"""The assistant's HTTP API: chat with the assistant, and confirm a proposal it prepared."""

import json
import logging
from collections.abc import Callable
from datetime import date
from typing import Annotated, Literal

import httpx
import uvicorn
from fastapi import FastAPI, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

from assistant.agent import Agent, user_message
from assistant.auth import subject_of
from assistant.conversations import ConversationStore
from assistant.database import Database
from assistant.ledger_client import LedgerClient, LedgerError, LedgerUnavailable
from assistant.llm import LlmClient, LlmUnavailable, OpenAICompatClient
from assistant.proposals import ProposalStore
from assistant.settings import Settings
from assistant.tools import ToolBox

log = logging.getLogger(__name__)


class ChatRequest(BaseModel):
    conversationId: str | None = None
    message: Annotated[str, Field(min_length=1, max_length=2000)]
    language: Literal["vi", "en"] = "vi"


class _Problem(Exception):
    def __init__(self, status: int, slug: str, title: str):
        self.status, self.slug, self.title = status, slug, title


def _problem(status: int, slug: str, title: str) -> JSONResponse:
    return JSONResponse(
        {"type": f"/problems/{slug}", "title": title, "status": status},
        status_code=status,
        media_type="application/problem+json",
    )


def _caller(authorization: str | None) -> tuple[str, str]:
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise _Problem(401, "unauthenticated", "Sign in first")
    try:
        return token, subject_of(token)
    except ValueError as error:
        raise _Problem(401, "unauthenticated", "Sign in first") from error


def create_app(
    settings: Settings,
    llm: LlmClient,
    http: httpx.Client,
    proposals: ProposalStore | None = None,
    conversations: ConversationStore | None = None,
    today: Callable[[], str] = lambda: date.today().isoformat(),
) -> FastAPI:
    if proposals is None or conversations is None:
        db = Database(settings.database_path)
        proposals = proposals or ProposalStore(db)
        conversations = conversations or ConversationStore(db)
    agent = Agent(llm)
    app = FastAPI(title="Ledgerly assistant")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[settings.allowed_origin],
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Authorization", "Content-Type"],
        expose_headers=["Retry-After"],
    )

    @app.exception_handler(_Problem)
    def problem_handler(request, problem: _Problem):
        return _problem(problem.status, problem.slug, problem.title)

    def verified(authorization: str | None) -> tuple[LedgerClient, str]:
        """The caller, once ledger-app has accepted their token. The token is only decoded here, so this runs before
        anything is read from the database or costs any LLM quota."""
        token, sub = _caller(authorization)
        ledger = LedgerClient(settings.ledgerly_api_url, token, http)
        try:
            ledger.list_wallets()
        except (LedgerError, LedgerUnavailable) as error:
            raise _translate(error) from error
        return ledger, sub

    @app.post("/assistant/chat")
    def chat(request: ChatRequest, authorization: Annotated[str | None, Header()] = None):
        ledger, sub = verified(authorization)
        conversation = conversations.get_or_create(request.conversationId, sub)
        if not conversations.begin(conversation.id):
            raise _Problem(409, "conversation-busy", "A reply in this conversation is still being written")
        try:
            conversation.messages.append(user_message(request.message, today(), request.language))
            try:
                reply = agent.reply(conversation.messages, ToolBox(ledger, proposals, sub), request.language)
            except Exception as error:
                # Nothing is saved: a turn that failed half way is dropped, so the conversation stays valid.
                raise _translate(error) from error
            conversation.transcript.append({"role": "user", "text": request.message, "proposalIds": []})
            conversation.transcript.append(
                {"role": "assistant", "text": reply.text, "proposalIds": [p.id for p in reply.proposals]}
            )
            conversations.save(conversation)
        finally:
            conversations.end(conversation.id)
        return {
            "conversationId": conversation.id,
            "reply": reply.text,
            "proposals": [{**p.summary(), "status": "open"} for p in reply.proposals],
        }

    @app.get("/assistant/conversations")
    def list_conversations(authorization: Annotated[str | None, Header()] = None):
        _, sub = verified(authorization)
        return {
            "items": [
                {"id": c.id, "title": c.title, "updatedAt": c.updated_at.isoformat()} for c in conversations.recent(sub)
            ]
        }

    @app.get("/assistant/conversations/{conversation_id}")
    def get_conversation(conversation_id: str, authorization: Annotated[str | None, Header()] = None):
        _, sub = verified(authorization)
        conversation = conversations.find(conversation_id, sub)
        if conversation is None:
            raise _Problem(404, "conversation-not-found", "No such conversation")
        return {
            "id": conversation.id,
            "title": conversation.title,
            "messages": [
                {
                    "role": entry["role"],
                    "text": entry["text"],
                    "proposals": [card for pid in entry["proposalIds"] if (card := _card(proposals, pid, sub))],
                }
                for entry in conversation.transcript
            ],
        }

    @app.delete("/assistant/conversations/{conversation_id}", status_code=204)
    def delete_conversation(conversation_id: str, authorization: Annotated[str | None, Header()] = None):
        _, sub = verified(authorization)
        # A turn still running would save the conversation again when it ends.
        if not conversations.begin(conversation_id):
            raise _Problem(409, "conversation-busy", "A reply in this conversation is still being written")
        try:
            if not conversations.delete(conversation_id, sub):
                raise _Problem(404, "conversation-not-found", "No such conversation")
        finally:
            conversations.end(conversation_id)
        return Response(status_code=204)

    @app.post("/assistant/proposals/{proposal_id}/confirm")
    def confirm(proposal_id: str, authorization: Annotated[str | None, Header()] = None):
        ledger, sub = verified(authorization)
        if stored := proposals.outcome(proposal_id, sub):
            # Ledgerly already answered this proposal: give the same answer again without asking it twice.
            return _json(stored.http_status, stored.body)
        proposal = proposals.get(proposal_id, sub)
        if proposal is None:
            raise _Problem(404, "proposal-not-found", "The proposal has expired")
        try:
            # The proposal's own key: if this request is sent again, Ledgerly replays the first result.
            outcome = ledger.create_order(
                proposal.ticket_class_id, proposal.wallet_id, proposal.quantity, proposal.idempotency_key
            )
        except LedgerUnavailable as error:
            raise _translate(error) from error
        if outcome.status < 300:
            proposals.record(proposal_id, "confirmed", outcome.status, outcome.body)
        elif 400 <= outcome.status < 500 and outcome.status not in (409, 429):
            # A refusal (not enough money, sold out...) is final: Ledgerly keeps it under the same key.
            proposals.record(proposal_id, "refused", outcome.status, outcome.body)
        headers = {"Retry-After": outcome.retry_after} if outcome.retry_after else None
        return _json(outcome.status, outcome.body, headers)

    @app.get("/health")
    def health():
        return {"status": "UP"}

    return app


def _json(status: int, body: dict, headers: dict | None = None) -> Response:
    media_type = "application/problem+json" if status >= 400 else "application/json"
    return Response(json.dumps(body), status, headers=headers, media_type=media_type)


def _card(proposals: ProposalStore, proposal_id: str, user_sub: str) -> dict | None:
    """A proposal as the page shows it in the history: what it was, and where it stands now."""
    found = proposals.state(proposal_id, user_sub)
    if found is None:
        return None
    proposal, state = found
    card = {**proposal.summary(), "status": state.status}
    if state.status == "confirmed":
        card["orderId"] = state.body.get("id")
    elif state.status == "refused":
        card["problem"] = state.body
    return card


def _translate(error: Exception) -> Exception:
    if isinstance(error, LedgerError) and error.status == 401:
        return _Problem(401, "unauthenticated", "The session has expired")
    if isinstance(error, LedgerUnavailable | LedgerError):
        return _Problem(502, "ledgerly-unavailable", "Ledgerly could not be reached")
    if isinstance(error, LlmUnavailable):
        return _llm_problem(error)
    return error


def _llm_problem(error: LlmUnavailable) -> _Problem:
    if error.status == 413:
        return _Problem(503, "conversation-too-long", "The conversation is too long for the model")
    if error.status == 400:
        return _Problem(503, "assistant-failed", "The model could not handle this message")
    if error.status in (401, 403, 404):
        log.error("the LLM provider refused the configuration: %s", error)
        return _Problem(503, "assistant-misconfigured", "The assistant is misconfigured")
    return _Problem(503, "assistant-unavailable", "The assistant is busy")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    llm = OpenAICompatClient(
        settings.llm_base_url, settings.llm_api_key, settings.llm_model, timeout=settings.llm_timeout_seconds
    )
    app = create_app(settings, llm, httpx.Client(timeout=10))
    uvicorn.run(app, host="0.0.0.0", port=8090)
