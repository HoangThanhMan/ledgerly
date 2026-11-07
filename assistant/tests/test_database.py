from assistant.database import Database


def test_creates_its_tables(tmp_path):
    db = Database(str(tmp_path / "a.db"))

    tables = {row["name"] for row in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}

    assert {"proposals", "conversations"} <= tables


def test_execute_returns_rows():
    db = Database()
    db.execute("CREATE TABLE t (x INTEGER)")
    db.execute("INSERT INTO t VALUES (?)", (7,))

    assert [row["x"] for row in db.execute("SELECT x FROM t")] == [7]


def test_adds_the_renamed_column_to_an_old_file(tmp_path):
    import sqlite3

    path = str(tmp_path / "old.db")
    old = sqlite3.connect(path)
    old.execute(
        "CREATE TABLE conversations (id TEXT PRIMARY KEY, user_sub TEXT NOT NULL, title TEXT NOT NULL, "
        "messages TEXT NOT NULL, transcript TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)"
    )
    old.commit()
    old.close()

    db = Database(path)

    assert "renamed" in {row["name"] for row in db.execute("PRAGMA table_info(conversations)")}
