import pathlib
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from fastapi.testclient import TestClient

from assistant.app import create_app
from assistant.conversations import ConversationStore
from assistant.llm import LlmUnavailable
from assistant.proposals import Proposal, ProposalStore
from assistant.settings import Settings
from tests.conftest import LEDGER_URL
from tests.factories import event_json, problem_json, ticket_class_json, token_for, wallet_json
from tests.fake_llm import FakeLlm, call, say

SETTINGS = Settings(
    ledgerly_api_url=LEDGER_URL,
    allowed_origin="http://localhost:8080",
    llm_base_url="http://llm.test/v1",
    llm_model="m",
    llm_api_key="k",
    database_path=":memory:",
)


class Clock:
    def __init__(self):
        self.now = datetime(2026, 11, 7, 12, 0, tzinfo=UTC)

    def __call__(self):
        return self.now


class Unavailable:
    def __init__(self, status=429):
        self.status = status

    def complete(self, messages, tools):
        raise LlmUnavailable("quota", self.status)


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture(autouse=True)
def signed_in(ledger):
    ledger.get("/v1/wallets").respond(200, json={"items": [wallet_json()]})


@pytest.fixture
def proposals(clock):
    return ProposalStore(clock=clock)


def make_client(http, proposals, llm, **kwargs):
    # Titles are written by the model too; tests that are not about them keep the plain fallback.
    kwargs.setdefault("titles", lambda question, answer, language: None)
    app = create_app(SETTINGS, llm, http, proposals=proposals, today=lambda: "2026-11-07", **kwargs)
    return TestClient(app)


def auth(sub="u1"):
    return {"Authorization": f"Bearer {token_for(sub)}"}


def chat(client, message, conversation_id=None, sub="u1"):
    return client.post(
        "/assistant/chat", json={"conversationId": conversation_id, "message": message}, headers=auth(sub)
    )


def stored_proposal(proposals, user_sub="u1"):
    proposal = Proposal(
        id="p1",
        user_sub=user_sub,
        event_id="e1",
        event_name="Ledgerly Live",
        starts_at="2027-03-01T12:00:00Z",
        ticket_class_id="tc1",
        class_name="Thường",
        wallet_id="w1",
        quantity=2,
        unit_price=300_000,
        total=600_000,
        balance_after=400_000,
        idempotency_key="key-1",
        created_at=proposals.now(),
    )
    proposals.add(proposal)
    return proposal


def test_chat_returns_reply_and_proposals(ledger, http, proposals):
    event = event_json(classes=[ticket_class_json("VIP", 100_000)])
    wallet = wallet_json()
    ledger.get(f"/v1/events/{event['id']}").respond(200, json=event)
    ledger.get("/v1/wallets").respond(200, json={"items": [wallet]})
    args = {
        "event_id": event["id"],
        "ticket_class_id": event["ticketClasses"][0]["id"],
        "wallet_id": wallet["id"],
        "quantity": 2,
    }
    llm = FakeLlm([call(("c1", "propose_purchase", args)), say("Bạn bấm Xác nhận nhé.")])

    response = chat(make_client(http, proposals, llm), "Mua 2 vé VIP")

    body = response.json()
    assert response.status_code == 200
    assert body["reply"] == "Bạn bấm Xác nhận nhé."
    assert body["conversationId"]
    assert [(p["className"], p["quantity"], p["total"]) for p in body["proposals"]] == [("VIP", 2, 200_000)]
    assert "idempotencyKey" not in body["proposals"][0]
    assert llm.requests[0]["messages"][1]["content"] == "[Hôm nay: 2026-11-07. Trả lời bằng tiếng Việt.]\nMua 2 vé VIP"


def test_chat_passes_language(http, proposals):
    llm = FakeLlm([say("Hello")])
    client = make_client(http, proposals, llm)

    response = client.post("/assistant/chat", json={"message": "Hi", "language": "en"}, headers=auth())

    assert response.status_code == 200
    assert llm.requests[0]["messages"][1]["content"].startswith("[Today: 2026-11-07. Reply in English.]")


def test_problems_use_ledger_style_types(ledger, http, proposals):
    no_token = make_client(http, proposals, FakeLlm([])).post("/assistant/chat", json={"message": "x"})
    unavailable = chat(make_client(http, proposals, Unavailable()), "x")
    ledger.get("/v1/wallets").mock(side_effect=httpx.ConnectError("refused"))
    down = chat(make_client(http, proposals, FakeLlm([])), "x")

    for response, slug in [
        (no_token, "unauthenticated"),
        (unavailable, "assistant-unavailable"),
        (down, "ledgerly-unavailable"),
    ]:
        assert response.json()["type"] == f"/problems/{slug}"


def test_chat_requires_bearer(http, proposals):
    client = make_client(http, proposals, FakeLlm([]))

    assert client.post("/assistant/chat", json={"message": "Chào"}).status_code == 401
    assert (
        client.post("/assistant/chat", json={"message": "Chào"}, headers={"Authorization": "Bearer x"}).status_code
        == 401
    )


@pytest.mark.parametrize("message", ["", "x" * 2001])
def test_chat_rejects_bad_message_length(http, proposals, message):
    assert chat(make_client(http, proposals, FakeLlm([])), message).status_code == 422


def test_chat_expired_token_returns_401(ledger, http, proposals):
    ledger.get("/v1/wallets").respond(401, json=problem_json(401, "unauthenticated", "Unauthenticated"))
    llm = FakeLlm([call(("c1", "list_wallets", {}))])

    response = chat(make_client(http, proposals, llm), "Ví của tôi?")

    assert response.status_code == 401
    assert response.headers["content-type"] == "application/problem+json"


def test_chat_ledger_down_returns_502(ledger, http, proposals):
    ledger.get("/v1/wallets").mock(side_effect=httpx.ConnectError("refused"))
    llm = FakeLlm([call(("c1", "list_wallets", {}))])

    response = chat(make_client(http, proposals, llm), "Ví của tôi?")

    assert response.status_code == 502
    assert response.json()["title"]


def test_chat_llm_unavailable_returns_503(http, proposals):
    response = chat(make_client(http, proposals, Unavailable()), "Chào")

    assert response.status_code == 503
    assert response.json()["type"] == "/problems/assistant-unavailable"


@pytest.mark.parametrize(
    ("status", "slug"),
    [
        (413, "conversation-too-long"),
        (400, "assistant-failed"),
        (401, "assistant-misconfigured"),
        (404, "assistant-misconfigured"),
        (None, "assistant-unavailable"),
    ],
)
def test_llm_errors_have_their_own_types(http, proposals, status, slug):
    response = chat(make_client(http, proposals, Unavailable(status)), "Chào")

    assert response.status_code == 503
    assert response.json()["type"] == f"/problems/{slug}"


def test_forged_token_never_reaches_the_llm(ledger, http, proposals):
    ledger.get("/v1/wallets").respond(401, json=problem_json(401, "unauthenticated", "Unauthenticated"))
    llm = FakeLlm([say("Chào")])

    response = chat(make_client(http, proposals, llm), "Chào")

    assert response.status_code == 401
    assert llm.requests == []


def test_failed_turn_leaves_history_clean(http, proposals):
    conversations = ConversationStore()
    failing = make_client(http, proposals, Unavailable(), conversations=conversations)
    first = chat(failing, "Chào")
    assert first.status_code == 503

    llm = FakeLlm([say("Chào bạn")])
    conversation_id = chat(make_client(http, proposals, llm, conversations=conversations), "Chào").json()[
        "conversationId"
    ]

    assert conversation_id
    assert [m["role"] for m in llm.requests[0]["messages"]] == ["system", "user"]


def test_chat_keeps_history_per_conversation(http, proposals):
    llm = FakeLlm([say("Chào bạn"), say("Mình là trợ lý")])
    client = make_client(http, proposals, llm)

    conversation_id = chat(client, "Chào").json()["conversationId"]
    second = chat(client, "Bạn là ai?", conversation_id)

    assert second.json()["conversationId"] == conversation_id
    roles = [m["role"] for m in llm.requests[1]["messages"]]
    assert roles == ["system", "user", "assistant", "user"]


def test_conversation_of_other_user_is_new(http, proposals):
    llm = FakeLlm([say("Chào u1"), say("Chào u2")])
    client = make_client(http, proposals, llm)

    conversation_id = chat(client, "Chào", sub="u1").json()["conversationId"]
    other = chat(client, "Chào", conversation_id, sub="u2")

    assert other.json()["conversationId"] != conversation_id
    assert [m["role"] for m in llm.requests[1]["messages"]] == ["system", "user"]


def confirm(client, proposal_id="p1", sub="u1"):
    return client.post(f"/assistant/proposals/{proposal_id}/confirm", headers=auth(sub))


def test_confirm_posts_order_with_proposal_key(ledger, http, proposals):
    stored_proposal(proposals)
    orders = ledger.post("/v1/orders").respond(201, json={"id": "o1"})

    response = confirm(make_client(http, proposals, FakeLlm([])))

    request = orders.calls.last.request
    assert response.status_code == 201
    assert response.json() == {"id": "o1"}
    assert request.headers["Idempotency-Key"] == "key-1"
    assert request.headers["Authorization"] == auth()["Authorization"]


def test_confirm_twice_sends_same_key(ledger, http, proposals):
    stored_proposal(proposals)
    orders = ledger.post("/v1/orders").respond(201, json={"id": "o1"})
    client = make_client(http, proposals, FakeLlm([]))

    first, second = confirm(client), confirm(client)

    assert [c.request.headers["Idempotency-Key"] for c in orders.calls] == ["key-1"]
    assert first.json() == second.json() == {"id": "o1"}
    assert second.status_code == 201


def test_confirm_passes_ledger_rejection_through(ledger, http, proposals):
    stored_proposal(proposals)
    body = problem_json(422, "insufficient-funds", "Insufficient funds", "Balance too low")
    ledger.post("/v1/orders").respond(422, json=body)

    response = confirm(make_client(http, proposals, FakeLlm([])))

    assert (response.status_code, response.json()) == (422, body)
    assert response.headers["content-type"] == "application/problem+json"


def test_confirm_passes_rate_limit_through(ledger, http, proposals):
    stored_proposal(proposals)
    ledger.post("/v1/orders").respond(
        429, json=problem_json(429, "rate-limited", "Too many orders"), headers={"Retry-After": "42"}
    )

    response = confirm(make_client(http, proposals, FakeLlm([])))

    assert (response.status_code, response.headers["Retry-After"]) == (429, "42")


def test_confirm_ledger_down_returns_502(ledger, http, proposals):
    stored_proposal(proposals)
    ledger.post("/v1/orders").mock(side_effect=httpx.ConnectError("refused"))

    assert confirm(make_client(http, proposals, FakeLlm([]))).status_code == 502


def test_confirm_other_users_proposal_is_404(ledger, http, proposals):
    stored_proposal(proposals, user_sub="u1")
    orders = ledger.post("/v1/orders").respond(201, json={})

    assert confirm(make_client(http, proposals, FakeLlm([])), sub="u2").status_code == 404
    assert orders.call_count == 0


def test_confirm_expired_proposal_is_404(ledger, http, proposals, clock):
    stored_proposal(proposals)
    clock.now += timedelta(minutes=11)

    assert confirm(make_client(http, proposals, FakeLlm([]))).status_code == 404


def test_cors_allows_only_configured_origin(http, proposals):
    client = make_client(http, proposals, FakeLlm([]))

    def preflight(origin):
        return client.options(
            "/assistant/chat",
            headers={
                "Origin": origin,
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "authorization,content-type",
            },
        )

    assert preflight("http://localhost:8080").headers["access-control-allow-origin"] == "http://localhost:8080"
    assert "access-control-allow-origin" not in preflight("http://evil.test").headers


def test_health(http, proposals):
    assert make_client(http, proposals, FakeLlm([])).get("/health").json() == {"status": "UP"}


def test_settings_require_api_key():
    with pytest.raises(ValueError, match="LLM_API_KEY"):
        Settings.from_env({})
    settings = Settings.from_env({"LLM_API_KEY": "k"})
    assert (settings.llm_base_url, settings.llm_model) == ("https://api.groq.com/openai/v1", "openai/gpt-oss-120b")


def test_only_confirm_posts_orders():
    sources = pathlib.Path(__file__).parents[1] / "src" / "assistant"
    callers = {
        path.name: path.read_text().count(".create_order(")
        for path in sources.glob("*.py")
        if ".create_order(" in path.read_text()
    }
    assert set(callers) <= {"app.py", "mcp_server.py"}
    assert callers.get("app.py") == 1


def test_settings_llm_timeout():
    assert Settings.from_env({"LLM_API_KEY": "k"}).llm_timeout_seconds == 60
    assert Settings.from_env({"LLM_API_KEY": "k", "LLM_TIMEOUT_SECONDS": "300"}).llm_timeout_seconds == 300


def test_settings_database_path():
    assert Settings.from_env({"LLM_API_KEY": "k"}).database_path == "assistant.db"
    assert Settings.from_env({"LLM_API_KEY": "k", "ASSISTANT_DB_PATH": "/data/a.db"}).database_path == "/data/a.db"


def propose(ledger, http, proposals, conversations, clock=None):
    """Chats once and gets a proposal of 2 VIP tickets. Returns the client, the conversation id and the proposal id."""
    event = event_json(classes=[ticket_class_json("VIP", 100_000)])
    wallet = wallet_json()
    ledger.get(f"/v1/events/{event['id']}").respond(200, json=event)
    ledger.get("/v1/wallets").respond(200, json={"items": [wallet]})
    args = {
        "event_id": event["id"],
        "ticket_class_id": event["ticketClasses"][0]["id"],
        "wallet_id": wallet["id"],
        "quantity": 2,
    }
    llm = FakeLlm([call(("c1", "propose_purchase", args)), say("Bấm Xác nhận nhé.")])
    client = make_client(http, proposals, llm, conversations=conversations)
    body = chat(client, "Mua 2 vé VIP").json()
    return client, body["conversationId"], body["proposals"][0]["id"]


def history(client, conversation_id=None, sub="u1"):
    path = "/assistant/conversations" + (f"/{conversation_id}" if conversation_id else "")
    return client.get(path, headers=auth(sub))


def test_history_lists_and_reopens_a_conversation(ledger, http, proposals):
    client, cid, pid = propose(ledger, http, proposals, ConversationStore())

    listed = history(client).json()["items"]
    opened = history(client, cid).json()

    assert [(c["id"], c["title"]) for c in listed] == [(cid, "Mua 2 vé VIP")]
    assert listed[0]["updatedAt"]
    assert [(m["role"], m["text"]) for m in opened["messages"]] == [
        ("user", "Mua 2 vé VIP"),
        ("assistant", "Bấm Xác nhận nhé."),
    ]
    card = opened["messages"][1]["proposals"][0]
    assert (card["id"], card["className"], card["status"]) == (pid, "VIP", "open")


def test_history_requires_a_verified_token(ledger, http, proposals):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())
    ledger.get("/v1/wallets").respond(401, json=problem_json(401, "unauthenticated", "Unauthenticated"))

    for response in (history(client), history(client, cid)):
        assert response.status_code == 401
        assert "Mua 2 vé" not in response.text


def test_history_of_other_user_is_404(ledger, http, proposals):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())

    response = history(client, cid, sub="u2")

    assert response.status_code == 404
    assert response.json()["type"] == "/problems/conversation-not-found"
    assert history(client, sub="u2").json() == {"items": []}


def test_conversation_shows_confirmed_proposal(ledger, http, proposals):
    client, cid, pid = propose(ledger, http, proposals, ConversationStore())
    ledger.post("/v1/orders").respond(201, json={"id": "o1"})
    confirm(client, pid)

    card = history(client, cid).json()["messages"][1]["proposals"][0]

    assert (card["status"], card["orderId"]) == ("confirmed", "o1")


def test_conversation_shows_refused_proposal(ledger, http, proposals):
    client, cid, pid = propose(ledger, http, proposals, ConversationStore())
    refusal = problem_json(422, "insufficient-funds", "Insufficient funds")
    orders = ledger.post("/v1/orders").respond(422, json=refusal)
    first = confirm(client, pid)
    again = confirm(client, pid)

    card = history(client, cid).json()["messages"][1]["proposals"][0]

    assert (card["status"], card["problem"]["type"]) == ("refused", refusal["type"])
    assert (first.status_code, again.status_code, again.json()) == (422, 422, refusal)
    assert orders.call_count == 1


def test_confirm_after_success_replays_without_calling_ledger(ledger, http, proposals, clock):
    stored_proposal(proposals)
    orders = ledger.post("/v1/orders").respond(201, json={"id": "o1"})
    client = make_client(http, proposals, FakeLlm([]))
    confirm(client)
    clock.now += timedelta(minutes=11)

    again = confirm(client)

    assert (again.status_code, again.json()) == (201, {"id": "o1"})
    assert orders.call_count == 1


def test_temporary_failures_are_not_stored(ledger, http, proposals):
    stored_proposal(proposals)
    ledger.post("/v1/orders").mock(
        side_effect=[
            httpx.Response(
                429, json=problem_json(429, "rate-limited", "Too many orders"), headers={"Retry-After": "1"}
            ),
            httpx.Response(201, json={"id": "o1"}),
        ]
    )
    client = make_client(http, proposals, FakeLlm([]))

    assert confirm(client).status_code == 429
    assert confirm(client).status_code == 201
    assert proposals.state("p1", "u1")[1].status == "confirmed"


def test_confirm_requires_a_verified_token(ledger, http, proposals):
    stored_proposal(proposals)
    client = make_client(http, proposals, FakeLlm([]))
    ledger.post("/v1/orders").respond(201, json={"id": "o1"})
    confirm(client)
    ledger.get("/v1/wallets").respond(401, json=problem_json(401, "unauthenticated", "Unauthenticated"))

    assert confirm(client).status_code == 401


def test_busy_conversation_returns_409(ledger, http, proposals):
    conversations = ConversationStore()
    client, cid, _ = propose(ledger, http, proposals, conversations)
    conversations.begin(cid)

    response = chat(client, "Còn gì nữa?", cid)

    assert response.status_code == 409
    assert response.json()["type"] == "/problems/conversation-busy"


def delete(client, conversation_id, sub="u1"):
    return client.delete(f"/assistant/conversations/{conversation_id}", headers=auth(sub))


def test_delete_conversation(ledger, http, proposals):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())

    response = delete(client, cid)

    assert response.status_code == 204
    assert history(client, cid).status_code == 404
    assert history(client).json() == {"items": []}


def test_delete_other_users_conversation_is_404(ledger, http, proposals):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())

    response = delete(client, cid, sub="u2")

    assert response.status_code == 404
    assert response.json()["type"] == "/problems/conversation-not-found"
    assert history(client, cid).status_code == 200


def test_delete_requires_a_verified_token(ledger, http, proposals):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())
    ledger.get("/v1/wallets").respond(401, json=problem_json(401, "unauthenticated", "Unauthenticated"))

    assert delete(client, cid).status_code == 401


def test_delete_busy_conversation_is_409(ledger, http, proposals):
    conversations = ConversationStore()
    client, cid, _ = propose(ledger, http, proposals, conversations)
    conversations.begin(cid)

    response = delete(client, cid)

    assert response.status_code == 409
    conversations.end(cid)
    assert history(client, cid).status_code == 200


def test_cors_allows_delete(http, proposals):
    client = make_client(http, proposals, FakeLlm([]))

    preflight = client.options(
        "/assistant/conversations/x",
        headers={"Origin": "http://localhost:8080", "Access-Control-Request-Method": "DELETE"},
    )

    assert "DELETE" in preflight.headers["access-control-allow-methods"]


def test_new_conversation_gets_a_written_title(http, proposals):
    from assistant.titles import TitleWriter

    llm = FakeLlm([say("Có 7 sự kiện sắp tới."), say("Sự kiện sắp tới"), say("Còn nữa.")])
    client = make_client(http, proposals, llm, titles=TitleWriter(llm), conversations=ConversationStore())

    first = chat(client, "Có những sự kiện nào sắp diễn ra trong vài tháng tới vậy?").json()
    second = chat(client, "Còn gì nữa?", first["conversationId"]).json()

    assert first["title"] == "Sự kiện sắp tới"
    assert second["title"] == "Sự kiện sắp tới"
    assert len(llm.requests) == 3  # no new title for later turns
    assert history(client).json()["items"][0]["title"] == "Sự kiện sắp tới"


def test_title_falls_back_to_the_question(http, proposals):
    client = make_client(http, proposals, FakeLlm([say("Chào")]))

    body = chat(client, "Mua 2 vé hạng B Ledgerly Live").json()

    assert body["title"] == "Mua 2 vé hạng B Ledgerly Live"


def rename(client, conversation_id, title, sub="u1"):
    return client.patch(f"/assistant/conversations/{conversation_id}", json={"title": title}, headers=auth(sub))


def test_rename_conversation(ledger, http, proposals):
    conversations = ConversationStore()
    client, cid, _ = propose(ledger, http, proposals, conversations)

    response = rename(client, cid, "  Vé VIP cho nhóm  ")

    assert response.status_code == 200
    assert response.json()["title"] == "Vé VIP cho nhóm"
    assert history(client).json()["items"][0]["title"] == "Vé VIP cho nhóm"
    assert conversations.find(cid, "u1").renamed is True


@pytest.mark.parametrize("title", ["", "   ", "x" * 61])
def test_rename_rejects_bad_titles(ledger, http, proposals, title):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())

    assert rename(client, cid, title).status_code == 422


def test_rename_other_users_conversation_is_404(ledger, http, proposals):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())

    assert rename(client, cid, "x", sub="u2").status_code == 404


def test_rename_requires_a_verified_token(ledger, http, proposals):
    client, cid, _ = propose(ledger, http, proposals, ConversationStore())
    ledger.get("/v1/wallets").respond(401, json=problem_json(401, "unauthenticated", "Unauthenticated"))

    assert rename(client, cid, "x").status_code == 401
