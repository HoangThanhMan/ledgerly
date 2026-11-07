from dataclasses import replace
from datetime import UTC, datetime, timedelta

from assistant.proposals import Proposal, ProposalState, ProposalStore


class Clock:
    def __init__(self):
        self.now = datetime(2026, 11, 7, 12, 0, tzinfo=UTC)

    def __call__(self):
        return self.now


def proposal(store, user_sub="u1"):
    return Proposal(
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
        idempotency_key="k1",
        created_at=store.now(),
    )


def test_store_hides_other_users_and_expired():
    clock = Clock()
    store = ProposalStore(ttl=timedelta(minutes=10), clock=clock)
    store.add(proposal(store))

    assert store.get("p1", "u1").total == 600_000
    assert store.get("p1", "u2") is None
    assert store.get("nope", "u1") is None
    clock.now += timedelta(minutes=10, seconds=1)
    assert store.get("p1", "u1") is None


def test_summary_hides_the_idempotency_key():
    store = ProposalStore()
    summary = proposal(store).summary()

    assert "k1" not in summary.values()
    assert summary["total"] == 600_000
    assert summary["className"] == "Thường"


def test_proposals_survive_a_new_store_on_the_same_file(tmp_path):
    from assistant.database import Database

    path = str(tmp_path / "a.db")
    first = ProposalStore(Database(path))
    first.add(proposal(first))

    again = ProposalStore(Database(path))

    assert again.get("p1", "u1").total == 600_000


def test_record_and_state():
    clock = Clock()
    store = ProposalStore(ttl=timedelta(minutes=10), clock=clock)
    for pid in ("open", "bought", "refused", "old"):
        store.add(replace(proposal(store), id=pid))
    store.record("bought", "confirmed", 201, {"id": "o1"})
    store.record("refused", "refused", 422, {"type": "/problems/insufficient-funds"})
    clock.now += timedelta(minutes=11)
    store.add(replace(proposal(store), id="open"))  # made again, so still fresh

    assert store.state("open", "u1")[1].status == "open"
    assert store.state("bought", "u1")[1] == ProposalState("confirmed", 201, {"id": "o1"})
    assert store.state("refused", "u1")[1].body == {"type": "/problems/insufficient-funds"}
    assert store.state("old", "u1")[1].status == "expired"
    assert store.state("bought", "u2") is None
    assert store.get("bought", "u1") is None  # only open proposals can be confirmed
    assert store.outcome("bought", "u1") == ProposalState("confirmed", 201, {"id": "o1"})
    assert store.outcome("open", "u1") is None
