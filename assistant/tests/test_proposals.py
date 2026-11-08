from datetime import UTC, datetime, timedelta

from assistant.proposals import Proposal, ProposalStore


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
        ticket_class_id="tc1",
        class_name="Thường",
        wallet_id="w1",
        quantity=2,
        unit_price=300_000,
        total=600_000,
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
