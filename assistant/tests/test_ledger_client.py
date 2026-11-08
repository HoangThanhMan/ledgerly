import json

import httpx
import pytest

from assistant.ledger_client import LedgerClient, LedgerError, LedgerUnavailable
from tests.conftest import LEDGER_URL
from tests.factories import event_json, problem_json


@pytest.fixture
def client(http):
    return LedgerClient(LEDGER_URL, "tok", http)


def test_create_order_sends_key_and_bearer(ledger, client):
    route = ledger.post("/v1/orders").respond(201, json={"id": "o1"})

    outcome = client.create_order("tc1", "w1", 2, "key-1")

    request = route.calls.last.request
    assert request.headers["Idempotency-Key"] == "key-1"
    assert request.headers["Authorization"] == "Bearer tok"
    assert json.loads(request.content) == {"ticketClassId": "tc1", "walletId": "w1", "quantity": 2}
    assert (outcome.status, outcome.body, outcome.retry_after) == (201, {"id": "o1"}, None)


def test_create_order_returns_rejection_as_outcome(ledger, client):
    body = problem_json(422, "insufficient-funds", "Insufficient funds")
    ledger.post("/v1/orders").respond(422, json=body)

    outcome = client.create_order("tc1", "w1", 2, "key-1")

    assert (outcome.status, outcome.body) == (422, body)


def test_create_order_keeps_retry_after(ledger, client):
    ledger.post("/v1/orders").respond(
        429, json=problem_json(429, "rate-limited", "Too many orders"), headers={"Retry-After": "30"}
    )

    assert client.create_order("tc1", "w1", 1, "key-1").retry_after == "30"


def test_read_error_raises_ledger_error(ledger, client):
    ledger.get("/v1/events/e1").respond(404, json=problem_json(404, "event-not-found", "Event not found", "No e1"))

    with pytest.raises(LedgerError) as error:
        client.get_event("e1")

    assert (error.value.status, error.value.title, error.value.detail) == (404, "Event not found", "No e1")


def test_reads_return_json(ledger, client):
    event = event_json()
    ledger.get(f"/v1/events/{event['id']}").respond(200, json=event)
    ledger.get("/v1/wallets").respond(200, json={"items": [{"id": "w1"}]})
    events = ledger.get("/v1/events", params={"page": "2", "size": "100"}).respond(200, json={"items": []})

    assert client.get_event(event["id"]) == event
    assert client.list_wallets() == [{"id": "w1"}]
    assert client.list_events(2, 100) == {"items": []}
    assert events.called


def test_connection_refused_raises_unavailable(ledger, client):
    ledger.get("/v1/wallets").mock(side_effect=httpx.ConnectError("refused"))

    with pytest.raises(LedgerUnavailable):
        client.list_wallets()
    with pytest.raises(LedgerUnavailable):
        ledger.post("/v1/orders").mock(side_effect=httpx.ConnectError("refused"))
        client.create_order("tc1", "w1", 1, "k")
