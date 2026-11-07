import asyncio
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError as McpToolError

from assistant.ledger_client import LedgerClient
from assistant.mcp_server import build_server
from tests.conftest import LEDGER_URL
from tests.factories import event_json, problem_json, ticket_class_json, wallet_json


@pytest.fixture
def server(http):
    return build_server(LedgerClient(LEDGER_URL, "tok", http), "u1")


def call(server, name, **args):
    result = asyncio.run(server.call_tool(name, args))
    return json.loads(result.content[0].text)


def test_lists_five_tools(server):
    tools = {tool.name: tool for tool in asyncio.run(server.list_tools())}

    assert set(tools) == {"search_events", "get_event", "list_wallets", "propose_purchase", "confirm_purchase"}
    assert tools["confirm_purchase"].annotations.destructive_hint is True
    assert tools["get_event"].annotations.read_only_hint is True


def purchase(ledger):
    event = event_json(classes=[ticket_class_json("VIP", 100_000)])
    wallet = wallet_json()
    ledger.get(f"/v1/events/{event['id']}").respond(200, json=event)
    ledger.get("/v1/wallets").respond(200, json={"items": [wallet]})
    return {
        "event_id": event["id"],
        "ticket_class_id": event["ticketClasses"][0]["id"],
        "wallet_id": wallet["id"],
        "quantity": 2,
    }


def test_propose_then_confirm_uses_proposal_key(ledger, server):
    orders = ledger.post("/v1/orders").respond(201, json={"id": "o1"})
    proposal = call(server, "propose_purchase", **purchase(ledger))["proposal"]
    assert orders.call_count == 0

    first = call(server, "confirm_purchase", proposal_id=proposal["id"])
    call(server, "confirm_purchase", proposal_id=proposal["id"])

    keys = {c.request.headers["Idempotency-Key"] for c in orders.calls}
    assert first == {"order": {"id": "o1"}}
    assert orders.call_count == 2
    assert len(keys) == 1


def test_confirm_unknown_proposal_errors(server):
    with pytest.raises(McpToolError, match="proposal"):
        call(server, "confirm_purchase", proposal_id="nope")


def test_confirm_rejection_errors(ledger, server):
    ledger.post("/v1/orders").respond(422, json=problem_json(422, "insufficient-funds", "Insufficient funds"))
    proposal = call(server, "propose_purchase", **purchase(ledger))["proposal"]

    with pytest.raises(McpToolError, match="Insufficient funds"):
        call(server, "confirm_purchase", proposal_id=proposal["id"])


def test_tool_errors_reach_the_host(ledger, server):
    with pytest.raises(McpToolError, match="1 to 4"):
        call(server, "propose_purchase", **{**purchase(ledger), "quantity": 9})
