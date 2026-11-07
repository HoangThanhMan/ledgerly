"""Conversations, kept for a while for the history sidebar. Each belongs to the user who started it."""

import json
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from threading import Lock

from assistant.database import Database

TITLE_LENGTH = 60


@dataclass
class Conversation:
    id: str
    user_sub: str
    title: str
    created_at: datetime
    updated_at: datetime
    # What the model is sent: system prompt, user and assistant turns, tool calls and their results.
    messages: list[dict] = field(default_factory=list)
    # What the page shows: {"role": "user" | "assistant", "text": ..., "proposalIds": [...]}.
    transcript: list[dict] = field(default_factory=list)


def _utc_now() -> datetime:
    return datetime.now(UTC)


class ConversationStore:
    def __init__(
        self,
        db: Database | None = None,
        ttl: timedelta = timedelta(days=30),
        clock: Callable[[], datetime] = _utc_now,
    ):
        self._db = db or Database()
        self._ttl = ttl
        self._clock = clock
        self._running: set[str] = set()
        self._lock = Lock()

    def get_or_create(self, conversation_id: str | None, user_sub: str) -> Conversation:
        """The user's conversation, or a new one when the id is unknown, expired or someone else's."""
        found = self.find(conversation_id, user_sub) if conversation_id else None
        if found:
            return found
        now = self._clock()
        return Conversation(str(uuid.uuid4()), user_sub, "", now, now)

    def save(self, conversation: Conversation) -> None:
        conversation.updated_at = self._clock()
        if not conversation.title:
            first = next((t["text"] for t in conversation.transcript if t["role"] == "user"), "")
            conversation.title = _shorten(first)
        self._db.execute(
            "INSERT OR REPLACE INTO conversations (id, user_sub, title, messages, transcript, created_at, updated_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?)",
            (
                conversation.id,
                conversation.user_sub,
                conversation.title,
                json.dumps(conversation.messages, ensure_ascii=False),
                json.dumps(conversation.transcript, ensure_ascii=False),
                conversation.created_at.isoformat(),
                conversation.updated_at.isoformat(),
            ),
        )

    def find(self, conversation_id: str, user_sub: str) -> Conversation | None:
        self._evict()
        rows = self._db.execute(
            "SELECT * FROM conversations WHERE id = ? AND user_sub = ?", (conversation_id, user_sub)
        )
        return _from_row(rows[0]) if rows else None

    def recent(self, user_sub: str, limit: int = 50) -> list[Conversation]:
        self._evict()
        rows = self._db.execute(
            "SELECT * FROM conversations WHERE user_sub = ? ORDER BY updated_at DESC LIMIT ?", (user_sub, limit)
        )
        return [_from_row(row) for row in rows]

    def delete(self, conversation_id: str, user_sub: str) -> bool:
        """Deletes the user's conversation. False when there is none of theirs with that id."""
        rows = self._db.execute(
            "DELETE FROM conversations WHERE id = ? AND user_sub = ? RETURNING id", (conversation_id, user_sub)
        )
        return bool(rows)

    def begin(self, conversation_id: str) -> bool:
        """Marks a turn as running. False when one already is: two turns at once would interleave their messages."""
        with self._lock:
            if conversation_id in self._running:
                return False
            self._running.add(conversation_id)
            return True

    def end(self, conversation_id: str) -> None:
        with self._lock:
            self._running.discard(conversation_id)

    def _evict(self) -> None:
        self._db.execute("DELETE FROM conversations WHERE updated_at < ?", ((self._clock() - self._ttl).isoformat(),))


def _shorten(text: str) -> str:
    text = " ".join(text.split())
    return text if len(text) <= TITLE_LENGTH else text[: TITLE_LENGTH - 1].rstrip() + "…"


def _from_row(row) -> Conversation:
    return Conversation(
        id=row["id"],
        user_sub=row["user_sub"],
        title=row["title"],
        created_at=datetime.fromisoformat(row["created_at"]),
        updated_at=datetime.fromisoformat(row["updated_at"]),
        messages=json.loads(row["messages"]),
        transcript=json.loads(row["transcript"]),
    )
