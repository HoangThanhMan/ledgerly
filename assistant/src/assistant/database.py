"""The assistant's own small database: conversations and proposals, in one SQLite file."""

import sqlite3
from threading import Lock

_SCHEMA = """
CREATE TABLE IF NOT EXISTS proposals (
    id          TEXT PRIMARY KEY,
    user_sub    TEXT NOT NULL,
    data        TEXT NOT NULL,              -- the Proposal, as JSON
    created_at  TEXT NOT NULL,              -- ISO 8601, UTC
    status      TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'confirmed', 'refused')),
    http_status INTEGER,                    -- Ledgerly's answer to the confirmation, once there is one
    body        TEXT
);
CREATE TABLE IF NOT EXISTS conversations (
    id          TEXT PRIMARY KEY,
    user_sub    TEXT NOT NULL,
    title       TEXT NOT NULL,
    messages    TEXT NOT NULL,              -- what the model is sent, as JSON
    transcript  TEXT NOT NULL,              -- what the page shows, as JSON
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS conversations_by_user ON conversations (user_sub, updated_at);
"""


class Database:
    def __init__(self, path: str = ":memory:"):
        # One connection shared by FastAPI's worker threads, each statement under the lock.
        self._connection = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._connection.row_factory = sqlite3.Row
        self._lock = Lock()
        with self._lock:
            self._connection.executescript(_SCHEMA)

    def execute(self, sql: str, params: tuple = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self._connection.execute(sql, params).fetchall()
