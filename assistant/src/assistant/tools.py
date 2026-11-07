"""The tools the model may call. None of them moves money: the most they do is prepare a proposal."""

import uuid
from datetime import date, datetime
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, ValidationError, WithJsonSchema

from assistant.ledger_client import LedgerClient
from assistant.proposals import Proposal, ProposalStore

MAX_QUANTITY = 4
SEARCH_PAGES = 10
SEARCH_PAGE_SIZE = 50  # the most GET /v1/events accepts
SEARCH_RESULTS = 10


class ToolError(Exception):
    """A problem the model should read and can fix, such as a wrong argument or an impossible purchase."""


class _Args(BaseModel):
    model_config = ConfigDict(extra="forbid")


def _date_part(value):
    """Models often send a date as a full timestamp: keep its YYYY-MM-DD part."""
    return value[:10] if isinstance(value, str) and len(value) > 10 and value[10] in "T " else value


# A plain string in the schema: providers that check tool calls against it would reject a timestamp for format: date.
LenientDate = Annotated[
    date | None,
    BeforeValidator(_date_part),
    WithJsonSchema({"anyOf": [{"type": "string"}, {"type": "null"}]}),
]


class SearchEventsArgs(_Args):
    text: Annotated[str | None, Field(description="Words the event name must contain.")] = None
    max_price: Annotated[int | None, Field(gt=0, description="Highest price of the cheapest ticket, in VND.")] = None
    starts_after: Annotated[LenientDate, Field(description="First day the event may start on (YYYY-MM-DD).")] = None
    starts_before: Annotated[LenientDate, Field(description="Last day the event may start on (YYYY-MM-DD).")] = None


class GetEventArgs(_Args):
    event_id: UUID


class ListWalletsArgs(_Args):
    pass


class ProposePurchaseArgs(_Args):
    event_id: UUID
    ticket_class_id: UUID
    wallet_id: UUID
    quantity: Annotated[int, Field(description="Number of tickets, from 1 to 4.")]


_SPECS = [
    (
        "search_events",
        SearchEventsArgs,
        "Find upcoming events by name, price and start date. Returns at most 10 events, soonest first.",
    ),
    ("get_event", GetEventArgs, "Get an event with its ticket classes, their prices and the tickets left."),
    ("list_wallets", ListWalletsArgs, "List the user's wallets and their balances in VND."),
    (
        "propose_purchase",
        ProposePurchaseArgs,
        "Prepare a ticket order for the user to confirm. This does NOT buy anything and moves no money: "
        "the user must press Confirm themselves. Only call it once the user has said which event, "
        "which ticket class and how many tickets they want.",
    ),
]

TOOLS: list[dict] = [
    {
        "type": "function",
        "function": {"name": name, "description": description, "parameters": model.model_json_schema()},
    }
    for name, model, description in _SPECS
]

_MODELS = {name: model for name, model, _ in _SPECS}


class ToolBox:
    """Runs tools for one user, through Ledgerly's API with that user's own token."""

    def __init__(self, ledger: LedgerClient, proposals: ProposalStore, user_sub: str):
        self._ledger = ledger
        self._proposals = proposals
        self._user_sub = user_sub
        self.created: list[Proposal] = []

    def run(self, name: str, arguments: str) -> dict:
        model = _MODELS.get(name)
        if model is None:
            raise ToolError(f"Unknown tool {name!r}.")
        try:
            args = model.model_validate_json(arguments or "{}")
        except ValidationError as error:
            raise ToolError("Invalid arguments: " + _describe(error)) from error
        return getattr(self, "_" + name)(args)

    def _search_events(self, args: SearchEventsArgs) -> dict:
        matches = []
        for page in range(SEARCH_PAGES):
            body = self._ledger.list_events(page, SEARCH_PAGE_SIZE)
            matches += [_summary(event) for event in body["items"] if _matches(event, args)]
            if (page + 1) * SEARCH_PAGE_SIZE >= body["totalItems"]:
                break
        return {"events": matches[:SEARCH_RESULTS], "matches": len(matches)}

    def _get_event(self, args: GetEventArgs) -> dict:
        event = self._ledger.get_event(str(args.event_id))
        return {
            "id": event["id"],
            "name": event["name"],
            "startsAt": event["startsAt"],
            "ticketClasses": [
                {"id": c["id"], "name": c["name"], "price": int(c["price"]), "remaining": c["remaining"]}
                for c in event["ticketClasses"]
            ],
        }

    def _list_wallets(self, args: ListWalletsArgs) -> dict:
        return {"wallets": [{"id": w["id"], "balance": int(w["balance"])} for w in self._ledger.list_wallets()]}

    def _propose_purchase(self, args: ProposePurchaseArgs) -> dict:
        if not 1 <= args.quantity <= MAX_QUANTITY:
            raise ToolError(f"quantity must be from 1 to {MAX_QUANTITY}, got {args.quantity}.")
        event = self._ledger.get_event(str(args.event_id))
        ticket_class = next((c for c in event["ticketClasses"] if c["id"] == str(args.ticket_class_id)), None)
        if ticket_class is None:
            raise ToolError("This event has no ticket class with that id. Call get_event to see its classes.")
        if ticket_class["remaining"] < args.quantity:
            raise ToolError(f"Only {ticket_class['remaining']} tickets of {ticket_class['name']} are left.")
        wallet = next((w for w in self._ledger.list_wallets() if w["id"] == str(args.wallet_id)), None)
        if wallet is None:
            raise ToolError("That wallet is not one of the user's wallets. Call list_wallets.")
        unit_price = int(ticket_class["price"])
        total = unit_price * args.quantity
        if int(wallet["balance"]) < total:
            raise ToolError(f"The wallet's balance {int(wallet['balance'])} VND is less than the total {total} VND.")

        proposal = Proposal(
            id=str(uuid.uuid4()),
            user_sub=self._user_sub,
            event_id=event["id"],
            event_name=event["name"],
            ticket_class_id=ticket_class["id"],
            class_name=ticket_class["name"],
            wallet_id=wallet["id"],
            quantity=args.quantity,
            unit_price=unit_price,
            total=total,
            idempotency_key=str(uuid.uuid4()),
            created_at=self._proposals.now(),
        )
        self._proposals.add(proposal)
        self.created.append(proposal)
        return {
            "proposal": proposal.summary(),
            "status": "Not bought yet. The user must press Confirm to buy these tickets.",
        }


def _summary(event: dict) -> dict:
    return {"id": event["id"], "name": event["name"], "startsAt": event["startsAt"], "minPrice": int(event["minPrice"])}


def _matches(event: dict, args: SearchEventsArgs) -> bool:
    starts_on = datetime.fromisoformat(event["startsAt"]).date()
    return (
        (args.text is None or args.text.casefold() in event["name"].casefold())
        and (args.max_price is None or int(event["minPrice"]) <= args.max_price)
        and (args.starts_after is None or starts_on >= args.starts_after)
        and (args.starts_before is None or starts_on <= args.starts_before)
    )


def _describe(error: ValidationError) -> str:
    return "; ".join(f"{'.'.join(map(str, e['loc'])) or 'arguments'}: {e['msg']}" for e in error.errors())
