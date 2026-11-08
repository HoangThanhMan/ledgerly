"""Conversations, kept in memory for a while. Each belongs to the user who started it."""

import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from threading import Lock


@dataclass
class _Conversation:
    user_sub: str
    touched: datetime
    messages: list[dict] = field(default_factory=list)


def _utc_now() -> datetime:
    return datetime.now(UTC)


class ConversationStore:
    def __init__(self, ttl: timedelta = timedelta(minutes=30), clock: Callable[[], datetime] = _utc_now):
        self._ttl = ttl
        self._clock = clock
        self._conversations: dict[str, _Conversation] = {}
        self._lock = Lock()

    def get_or_create(self, conversation_id: str | None, user_sub: str) -> tuple[str, list[dict]]:
        """Returns the user's conversation, or a new one when the id is unknown, expired or someone else's."""
        with self._lock:
            now = self._clock()
            for key in [k for k, c in self._conversations.items() if c.touched < now - self._ttl]:
                del self._conversations[key]
            conversation = self._conversations.get(conversation_id) if conversation_id else None
            if conversation is None or conversation.user_sub != user_sub:
                conversation_id = str(uuid.uuid4())
                conversation = self._conversations[conversation_id] = _Conversation(user_sub, now)
            conversation.touched = now
            return conversation_id, conversation.messages
