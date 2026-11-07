from datetime import UTC, datetime, timedelta

from assistant.conversations import ConversationStore
from assistant.database import Database


class Clock:
    def __init__(self):
        self.now = datetime(2026, 11, 7, 12, 0, tzinfo=UTC)

    def __call__(self):
        return self.now


def test_conversations_round_trip_and_title(tmp_path):
    path = str(tmp_path / "a.db")
    store = ConversationStore(Database(path))
    conversation = store.get_or_create(None, "u1")
    conversation.messages.append({"role": "user", "content": "x"})
    conversation.transcript.append({"role": "user", "text": "Mua 2 vé " + "rất " * 30, "proposalIds": []})
    store.save(conversation)

    again = ConversationStore(Database(path)).find(conversation.id, "u1")

    assert again.messages == [{"role": "user", "content": "x"}]
    assert len(again.title) <= 60
    assert again.title.startswith("Mua 2 vé rất")
    assert again.title.endswith("…")


def test_unknown_or_other_users_conversation_is_new():
    store = ConversationStore()
    mine = store.get_or_create(None, "u1")
    store.save(mine)

    assert store.get_or_create(mine.id, "u1").id == mine.id
    assert store.get_or_create(mine.id, "u2").id != mine.id
    assert store.find(mine.id, "u2") is None
    assert store.get_or_create("nope", "u1").id != "nope"


def test_recent_is_newest_first_and_per_user():
    clock = Clock()
    store = ConversationStore(clock=clock)
    ids = []
    for _ in range(3):
        conversation = store.get_or_create(None, "u1")
        store.save(conversation)
        ids.append(conversation.id)
        clock.now += timedelta(minutes=1)
    store.save(store.get_or_create(None, "u2"))

    assert [c.id for c in store.recent("u1")] == list(reversed(ids))
    assert len(store.recent("u1", limit=2)) == 2


def test_conversations_expire_after_30_days():
    clock = Clock()
    store = ConversationStore(clock=clock)
    conversation = store.get_or_create(None, "u1")
    store.save(conversation)
    clock.now += timedelta(days=30, minutes=1)

    assert store.find(conversation.id, "u1") is None
    assert store.recent("u1") == []


def test_begin_is_exclusive():
    store = ConversationStore()

    assert store.begin("c1") is True
    assert store.begin("c1") is False
    store.end("c1")
    assert store.begin("c1") is True


def test_delete_removes_only_the_users_own_conversation():
    store = ConversationStore()
    mine = store.get_or_create(None, "u1")
    store.save(mine)

    assert store.delete(mine.id, "u2") is False
    assert store.find(mine.id, "u1") is not None
    assert store.delete(mine.id, "u1") is True
    assert store.find(mine.id, "u1") is None
    assert store.delete(mine.id, "u1") is False
