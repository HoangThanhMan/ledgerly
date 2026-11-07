"""Purchase proposals: orders the assistant prepared and only the user can confirm."""

import json
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, timedelta
from typing import Literal

from assistant.database import Database


@dataclass(frozen=True)
class Proposal:
    id: str
    user_sub: str
    event_id: str
    event_name: str
    starts_at: str
    ticket_class_id: str
    class_name: str
    wallet_id: str
    quantity: int
    unit_price: int
    total: int
    # What the wallet would hold after this order, at the time it was proposed.
    balance_after: int
    # Sent with every confirmation of this proposal, so confirming twice charges once.
    idempotency_key: str
    created_at: datetime

    def summary(self) -> dict:
        """What the user is shown and asked to confirm."""
        return {
            "id": self.id,
            "eventId": self.event_id,
            "eventName": self.event_name,
            "startsAt": self.starts_at,
            "ticketClassId": self.ticket_class_id,
            "className": self.class_name,
            "walletId": self.wallet_id,
            "quantity": self.quantity,
            "unitPrice": self.unit_price,
            "total": self.total,
            "balanceAfter": self.balance_after,
            "currency": "VND",
        }


@dataclass(frozen=True)
class ProposalState:
    """Where a proposal stands: still to confirm, bought, refused by Ledgerly, or too old to confirm."""

    status: Literal["open", "confirmed", "refused", "expired"]
    http_status: int | None = None
    body: dict | None = None


def _utc_now() -> datetime:
    return datetime.now(UTC)


class ProposalStore:
    """Proposals, kept for the conversation history. A proposal is only ever visible to the user it was made for,
    and can only be confirmed for a while after it was made."""

    def __init__(
        self,
        db: Database | None = None,
        ttl: timedelta = timedelta(minutes=10),
        clock: Callable[[], datetime] = _utc_now,
        retention: timedelta = timedelta(days=30),
    ):
        self._db = db or Database()
        self._ttl = ttl
        self._clock = clock
        self._retention = retention

    def now(self) -> datetime:
        return self._clock()

    def add(self, proposal: Proposal) -> None:
        self._db.execute("DELETE FROM proposals WHERE created_at < ?", ((self._clock() - self._retention).isoformat(),))
        self._db.execute(
            "INSERT OR REPLACE INTO proposals (id, user_sub, data, created_at) VALUES (?, ?, ?, ?)",
            (proposal.id, proposal.user_sub, _to_json(proposal), proposal.created_at.isoformat()),
        )

    def get(self, proposal_id: str, user_sub: str) -> Proposal | None:
        """The proposal, if it can still be confirmed: open and recent enough."""
        found = self.state(proposal_id, user_sub)
        return found[0] if found and found[1].status == "open" else None

    def record(self, proposal_id: str, status: Literal["confirmed", "refused"], http_status: int, body: dict) -> None:
        self._db.execute(
            "UPDATE proposals SET status = ?, http_status = ?, body = ? WHERE id = ?",
            (status, http_status, json.dumps(body, ensure_ascii=False), proposal_id),
        )

    def outcome(self, proposal_id: str, user_sub: str) -> ProposalState | None:
        """Ledgerly's final answer to this proposal, if it has one."""
        found = self.state(proposal_id, user_sub)
        return found[1] if found and found[1].status in ("confirmed", "refused") else None

    def state(self, proposal_id: str, user_sub: str) -> tuple[Proposal, ProposalState] | None:
        rows = self._db.execute(
            "SELECT data, status, http_status, body FROM proposals WHERE id = ? AND user_sub = ?",
            (proposal_id, user_sub),
        )
        if not rows:
            return None
        row = rows[0]
        proposal = _from_json(row["data"])
        if row["status"] != "open":
            return proposal, ProposalState(row["status"], row["http_status"], json.loads(row["body"]))
        expired = proposal.created_at < self._clock() - self._ttl
        return proposal, ProposalState("expired" if expired else "open")


def _to_json(proposal: Proposal) -> str:
    data = asdict(proposal)
    data["created_at"] = proposal.created_at.isoformat()
    return json.dumps(data, ensure_ascii=False)


def _from_json(text: str) -> Proposal:
    data = json.loads(text)
    data["created_at"] = datetime.fromisoformat(data["created_at"])
    return Proposal(**data)
