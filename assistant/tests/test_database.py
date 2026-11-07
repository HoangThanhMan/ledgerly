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
