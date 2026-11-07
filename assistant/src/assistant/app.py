"""The assistant's HTTP API: chat with the assistant, and confirm a proposal it prepared."""

import json
import logging
from collections.abc import Callable
from datetime import date
from typing import Annotated

import httpx
import uvicorn
from fastapi import FastAPI, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

from assistant.agent import Agent, user_message
from assistant.auth import subject_of
from assistant.conversations import ConversationStore
from assistant.ledger_client import LedgerClient, LedgerError, LedgerUnavailable
from assistant.llm import LlmClient, LlmUnavailable, OpenAICompatClient
from assistant.proposals import ProposalStore
from assistant.settings import Settings
from assistant.tools import ToolBox

log = logging.getLogger(__name__)


class ChatRequest(BaseModel):
    conversationId: str | None = None
    message: Annotated[str, Field(min_length=1, max_length=2000)]


class _Problem(Exception):
    def __init__(self, status: int, slug: str, title: str):
        self.status, self.slug, self.title = status, slug, title


def _problem(status: int, slug: str, title: str) -> JSONResponse:
    return JSONResponse(
        {"type": f"https://ledgerly.dev/problems/{slug}", "title": title, "status": status},
        status_code=status,
        media_type="application/problem+json",
    )


def _caller(authorization: str | None) -> tuple[str, str]:
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise _Problem(401, "unauthenticated", "Bạn cần đăng nhập")
    try:
        return token, subject_of(token)
    except ValueError as error:
        raise _Problem(401, "unauthenticated", "Bạn cần đăng nhập") from error


def create_app(
    settings: Settings,
    llm: LlmClient,
    http: httpx.Client,
    proposals: ProposalStore | None = None,
    conversations: ConversationStore | None = None,
    today: Callable[[], str] = lambda: date.today().isoformat(),
) -> FastAPI:
    proposals = proposals or ProposalStore()
    conversations = conversations or ConversationStore()
    agent = Agent(llm)
    app = FastAPI(title="Ledgerly assistant")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[settings.allowed_origin],
        allow_methods=["GET", "POST"],
        allow_headers=["Authorization", "Content-Type"],
        expose_headers=["Retry-After"],
    )

    @app.exception_handler(_Problem)
    def problem_handler(request, problem: _Problem):
        return _problem(problem.status, problem.slug, problem.title)

    def ledger_for(token: str) -> LedgerClient:
        return LedgerClient(settings.ledgerly_api_url, token, http)

    @app.post("/assistant/chat")
    def chat(request: ChatRequest, authorization: Annotated[str | None, Header()] = None):
        token, sub = _caller(authorization)
        ledger = ledger_for(token)
        try:
            # The token is only decoded here, so let ledger-app verify it before it costs any LLM quota.
            ledger.list_wallets()
        except (LedgerError, LedgerUnavailable) as error:
            raise _translate(error) from error
        conversation_id, messages = conversations.get_or_create(request.conversationId, sub)
        before = len(messages)
        messages.append(user_message(request.message, today()))
        try:
            reply = agent.reply(messages, ToolBox(ledger, proposals, sub))
        except Exception as error:
            # A turn that failed half way is dropped, so the conversation stays valid for the next one.
            del messages[before:]
            raise _translate(error) from error
        return {
            "conversationId": conversation_id,
            "reply": reply.text,
            "proposals": [p.summary() for p in reply.proposals],
        }

    @app.post("/assistant/proposals/{proposal_id}/confirm")
    def confirm(proposal_id: str, authorization: Annotated[str | None, Header()] = None):
        token, sub = _caller(authorization)
        proposal = proposals.get(proposal_id, sub)
        if proposal is None:
            raise _Problem(404, "proposal-not-found", "Đề xuất không còn hiệu lực, hãy hỏi lại trợ lý")
        try:
            # The proposal's own key: confirming it again replays the first result instead of charging twice.
            outcome = ledger_for(token).create_order(
                proposal.ticket_class_id, proposal.wallet_id, proposal.quantity, proposal.idempotency_key
            )
        except LedgerUnavailable as error:
            raise _translate(error) from error
        headers = {"Retry-After": outcome.retry_after} if outcome.retry_after else None
        media_type = "application/problem+json" if outcome.status >= 400 else "application/json"
        return Response(json.dumps(outcome.body), outcome.status, headers=headers, media_type=media_type)

    @app.get("/health")
    def health():
        return {"status": "UP"}

    return app


def _translate(error: Exception) -> Exception:
    if isinstance(error, LedgerError) and error.status == 401:
        return _Problem(401, "unauthenticated", "Phiên đăng nhập đã hết hạn")
    if isinstance(error, LedgerUnavailable | LedgerError):
        return _Problem(502, "ledgerly-unavailable", "Không kết nối được tới Ledgerly, thử lại sau")
    if isinstance(error, LlmUnavailable):
        return _llm_problem(error)
    return error


def _llm_problem(error: LlmUnavailable) -> _Problem:
    if error.status == 413:
        return _Problem(503, "conversation-too-long", "Cuộc trò chuyện đã quá dài, hãy tải lại trang để bắt đầu lại")
    if error.status == 400:
        return _Problem(503, "assistant-failed", "Trợ lý chưa xử lý được câu này, bạn thử nói lại nhé")
    if error.status in (401, 403, 404):
        log.error("the LLM provider refused the configuration: %s", error)
        return _Problem(503, "assistant-misconfigured", "Trợ lý đang bị cấu hình sai, hãy báo người quản trị")
    return _Problem(503, "assistant-unavailable", "Trợ lý đang quá tải, thử lại sau ít phút")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    llm = OpenAICompatClient(settings.llm_base_url, settings.llm_api_key, settings.llm_model)
    app = create_app(settings, llm, httpx.Client(timeout=10))
    uvicorn.run(app, host="0.0.0.0", port=8090)
