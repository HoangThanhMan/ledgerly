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


def test_conversations_expire(clock):
    store = ConversationStore(ttl=timedelta(minutes=30), clock=clock)
    conversation_id, messages = store.get_or_create(None, "u1")
    messages.append({"role": "user", "content": "x"})

    assert store.get_or_create(conversation_id, "u1") == (conversation_id, messages)
    clock.now += timedelta(minutes=31)
    assert store.get_or_create(conversation_id, "u1")[0] != conversation_id


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

    assert [c.request.headers["Idempotency-Key"] for c in orders.calls] == ["key-1", "key-1"]
    assert first.json() == second.json() == {"id": "o1"}


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
