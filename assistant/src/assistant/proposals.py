"""Purchase proposals: orders the assistant prepared and only the user can confirm."""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from threading import Lock


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


def _utc_now() -> datetime:
    return datetime.now(UTC)


class ProposalStore:
    """Keeps proposals in memory for a while. A proposal is only ever visible to the user it was made for."""

    def __init__(self, ttl: timedelta = timedelta(minutes=10), clock: Callable[[], datetime] = _utc_now):
        self._ttl = ttl
        self._clock = clock
        self._proposals: dict[str, Proposal] = {}
        self._lock = Lock()

    def now(self) -> datetime:
        return self._clock()

    def add(self, proposal: Proposal) -> None:
        with self._lock:
            self._evict()
            self._proposals[proposal.id] = proposal

    def get(self, proposal_id: str, user_sub: str) -> Proposal | None:
        with self._lock:
            self._evict()
            proposal = self._proposals.get(proposal_id)
        return proposal if proposal and proposal.user_sub == user_sub else None

    def _evict(self) -> None:
        oldest = self._clock() - self._ttl
        for key in [key for key, p in self._proposals.items() if p.created_at < oldest]:
            del self._proposals[key]
