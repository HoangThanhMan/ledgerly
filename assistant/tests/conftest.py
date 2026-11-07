import httpx
import pytest
import respx

LEDGER_URL = "http://ledger.test"


@pytest.fixture
def ledger():
    with respx.mock(base_url=LEDGER_URL, assert_all_called=False) as router:
        yield router


@pytest.fixture
def http():
    with httpx.Client(timeout=10) as client:
        yield client
