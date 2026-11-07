import json

import pytest

from assistant.ledger_client import LedgerClient
from assistant.proposals import ProposalStore
from assistant.tools import (
    TOOLS,
    GetEventArgs,
    ListWalletsArgs,
    ProposePurchaseArgs,
    SearchEventsArgs,
    ToolBox,
    ToolError,
)
from tests.conftest import LEDGER_URL
from tests.factories import event_json, event_page_json, ticket_class_json, wallet_json


@pytest.fixture
def store():
    return ProposalStore()


@pytest.fixture
def toolbox(http, store):
    return ToolBox(LedgerClient(LEDGER_URL, "tok", http), store, "u1")


def run(toolbox, name, **args):
    return toolbox.run(name, json.dumps(args))


def search_args(**overrides):
    return {"text": None, "max_price": None, "starts_after": None, "starts_before": None, **overrides}


def serve_events(ledger, events):
    ledger.get("/v1/events").respond(200, json=event_page_json(events))


def test_search_filters_by_text_price_and_date(ledger, toolbox):
    live = event_json("Ledgerly Live", "2027-03-01T12:00:00Z", [ticket_class_json(price=300_000)])
    jazz = event_json("Đêm nhạc Jazz", "2027-03-02T12:00:00Z", [ticket_class_json(price=900_000)])
    late = event_json("ledgerly After Party", "2027-05-01T12:00:00Z", [ticket_class_json(price=200_000)])
    serve_events(ledger, [live, jazz, late])

    by_text = run(toolbox, "search_events", **search_args(text="LEDGERLY"))
    by_price = run(toolbox, "search_events", **search_args(max_price=500_000))
    by_date = run(toolbox, "search_events", **search_args(starts_after="2027-03-01", starts_before="2027-03-31"))

    assert [e["name"] for e in by_text["events"]] == ["Ledgerly Live", "ledgerly After Party"]
    assert [e["name"] for e in by_price["events"]] == ["Ledgerly Live", "ledgerly After Party"]
    assert [e["name"] for e in by_date["events"]] == ["Ledgerly Live", "Đêm nhạc Jazz"]
    assert by_text["events"][0]["minPrice"] == 300_000


def test_search_reads_at_most_five_pages_and_returns_ten(ledger, toolbox):
    pages = ledger.get("/v1/events").mock(
        side_effect=lambda request: __import__("httpx").Response(
            200, json=event_page_json([event_json(f"E{i}") for i in range(100)], total=10_000)
        )
    )

    result = run(toolbox, "search_events", **search_args())

    assert pages.call_count == 5
    assert len(result["events"]) == 10
    assert result["matches"] == 500


def test_get_event_returns_classes_with_integer_prices(ledger, toolbox):
    event = event_json(classes=[ticket_class_json("VIP", 2_000_000, remaining=3)])
    ledger.get(f"/v1/events/{event['id']}").respond(200, json=event)

    result = run(toolbox, "get_event", event_id=event["id"])

    assert result["ticketClasses"][0] == {
        "id": event["ticketClasses"][0]["id"],
        "name": "VIP",
        "price": 2_000_000,
        "remaining": 3,
    }


def test_list_wallets_returns_integer_balances(ledger, toolbox):
    wallet = wallet_json(750_000)
    ledger.get("/v1/wallets").respond(200, json={"items": [wallet]})

    assert run(toolbox, "list_wallets") == {"wallets": [{"id": wallet["id"], "balance": 750_000}]}


def purchase_setup(ledger, price=300_000, remaining=100, balance=1_000_000):
    event = event_json(classes=[ticket_class_json(price=price, remaining=remaining)])
    wallet = wallet_json(balance)
    ledger.get(f"/v1/events/{event['id']}").respond(200, json=event)
    ledger.get("/v1/wallets").respond(200, json={"items": [wallet]})
    orders = ledger.post("/v1/orders").respond(201, json={})
    args = {
        "event_id": event["id"],
        "ticket_class_id": event["ticketClasses"][0]["id"],
        "wallet_id": wallet["id"],
        "quantity": 2,
    }
    return args, orders


def test_propose_computes_total_and_stores(ledger, toolbox, store):
    args, _ = purchase_setup(ledger)

    result = run(toolbox, "propose_purchase", **args)

    proposal = store.get(result["proposal"]["id"], "u1")
    assert proposal.total == 600_000
    assert proposal.unit_price == 300_000
    assert proposal.idempotency_key
    assert toolbox.created == [proposal]


@pytest.mark.parametrize(
    ("setup", "change", "message"),
    [
        ({}, {"quantity": 5}, "1 to 4"),
        ({}, {"quantity": 0}, "1 to 4"),
        ({"remaining": 1}, {}, "left"),
        ({}, {"wallet_id": "00000000-0000-0000-0000-000000000000"}, "wallet"),
        ({"balance": 599_999}, {}, "balance"),
        ({}, {"ticket_class_id": "00000000-0000-0000-0000-000000000000"}, "ticket class"),
    ],
)
def test_propose_rejections_store_nothing(ledger, toolbox, setup, change, message):
    args, orders = purchase_setup(ledger, **setup)

    with pytest.raises(ToolError, match=message):
        run(toolbox, "propose_purchase", **{**args, **change})

    assert toolbox.created == []
    assert orders.call_count == 0


def test_propose_never_posts_orders(ledger, toolbox):
    args, orders = purchase_setup(ledger)

    run(toolbox, "propose_purchase", **args)

    assert orders.call_count == 0


def test_bad_json_raises_tool_error(toolbox):
    with pytest.raises(ToolError, match="Invalid arguments"):
        toolbox.run("get_event", "{oops")


@pytest.mark.parametrize(
    ("arguments", "field"),
    [
        ({"event_id": "e", "ticket_class_id": "t", "wallet_id": "w", "quantity": "hai"}, "quantity"),
        ({"event_id": "00000000-0000-0000-0000-000000000001", "ticket_class_id": "t", "quantity": 1}, "wallet_id"),
        ({"event_id": "x", "extra": 1}, "extra"),
    ],
)
def test_schema_violation_raises_tool_error(toolbox, arguments, field):
    name = "get_event" if "extra" in arguments else "propose_purchase"
    with pytest.raises(ToolError, match=field):
        toolbox.run(name, json.dumps(arguments))


def test_unknown_tool_raises_tool_error(toolbox):
    with pytest.raises(ToolError, match="Unknown tool"):
        toolbox.run("transfer_money", "{}")


def test_tool_schemas_come_from_models():
    models = {
        "search_events": SearchEventsArgs,
        "get_event": GetEventArgs,
        "list_wallets": ListWalletsArgs,
        "propose_purchase": ProposePurchaseArgs,
    }
    assert [tool["function"]["name"] for tool in TOOLS] == list(models)
    for tool in TOOLS:
        assert tool["type"] == "function"
        assert tool["function"]["parameters"] == models[tool["function"]["name"]].model_json_schema()


def test_search_accepts_datetimes_for_dates(ledger, toolbox):
    serve_events(ledger, [event_json("Ledgerly Live", "2027-03-01T12:00:00Z")])

    result = run(
        toolbox,
        "search_events",
        **search_args(starts_after="2027-03-01T00:00:00Z", starts_before="2027-03-31T23:59:59Z"),
    )

    assert [e["name"] for e in result["events"]] == ["Ledgerly Live"]


def test_date_schema_is_a_plain_string():
    # Providers such as Groq check tool calls against the schema before we see them: a `format: date` there turns a
    # model's "2027-03-01T00:00:00Z" into a provider error instead of something we can accept.
    schema = SearchEventsArgs.model_json_schema()["properties"]["starts_after"]
    assert "format" not in json.dumps(schema)
