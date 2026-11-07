"""The assistant's tools as an MCP server, for MCP hosts such as desktop chat apps.

The host brings its own model. confirm_purchase is the one tool that spends money: it is marked destructive so the
host asks the user before every call, and it can only confirm a proposal that propose_purchase prepared.
"""

import json
import os

import httpx
from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError as McpToolError
from mcp.types import ToolAnnotations

from assistant.auth import subject_of
from assistant.ledger_client import LedgerClient, LedgerError
from assistant.proposals import ProposalStore
from assistant.tools import ToolBox, ToolError

READ_ONLY = ToolAnnotations(read_only_hint=True, open_world_hint=False)


def build_server(ledger: LedgerClient, user_sub: str, proposals: ProposalStore | None = None) -> MCPServer:
    proposals = proposals or ProposalStore()
    server = MCPServer("ledgerly", instructions="Find Ledgerly events and buy tickets. Prices are in VND.")

    def run(name: str, args: dict) -> dict:
        try:
            return ToolBox(ledger, proposals, user_sub).run(name, json.dumps(args))
        except (ToolError, LedgerError) as error:
            raise McpToolError(str(error)) from error

    @server.tool(annotations=READ_ONLY)
    def search_events(
        text: str | None = None,
        max_price: int | None = None,
        starts_after: str | None = None,
        starts_before: str | None = None,
    ) -> dict:
        """Find upcoming events by name, highest cheapest-ticket price (VND) and start dates (YYYY-MM-DD)."""
        return run("search_events", locals())

    @server.tool(annotations=READ_ONLY)
    def get_event(event_id: str) -> dict:
        """Get an event with its ticket classes, their prices and the tickets left."""
        return run("get_event", {"event_id": event_id})

    @server.tool(annotations=READ_ONLY)
    def list_wallets() -> dict:
        """List the user's wallets and their balances in VND."""
        return run("list_wallets", {})

    @server.tool(annotations=ToolAnnotations(read_only_hint=False, destructive_hint=False, open_world_hint=False))
    def propose_purchase(event_id: str, ticket_class_id: str, wallet_id: str, quantity: int) -> dict:
        """Prepare a ticket order (1 to 4 tickets). Buys nothing: confirm_purchase buys it."""
        return run(
            "propose_purchase",
            {"event_id": event_id, "ticket_class_id": ticket_class_id, "wallet_id": wallet_id, "quantity": quantity},
        )

    @server.tool(
        annotations=ToolAnnotations(
            read_only_hint=False, destructive_hint=True, idempotent_hint=True, open_world_hint=False
        )
    )
    def confirm_purchase(proposal_id: str) -> dict:
        """Buy the tickets of a proposal, paying from its wallet. Spends money: only after the user agreed."""
        proposal = proposals.get(proposal_id, user_sub)
        if proposal is None:
            raise McpToolError("No such proposal, or it expired. Call propose_purchase again.")
        outcome = ledger.create_order(
            proposal.ticket_class_id, proposal.wallet_id, proposal.quantity, proposal.idempotency_key
        )
        if outcome.status >= 400:
            raise McpToolError(f"Ledgerly refused the order: {outcome.body.get('title', outcome.status)}")
        return {"order": outcome.body}

    return server


def main() -> None:
    token = os.environ.get("LEDGERLY_TOKEN", "")
    if not token:
        raise SystemExit("Set LEDGERLY_TOKEN to an access token from POST /v1/auth/login.")
    ledger = LedgerClient(os.environ.get("LEDGERLY_API_URL", "http://localhost:8080"), token, httpx.Client(timeout=10))
    build_server(ledger, subject_of(token)).run("stdio")
