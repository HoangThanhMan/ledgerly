import pytest

from assistant.auth import subject_of
from tests.factories import token_for


def test_subject_of_reads_sub():
    assert subject_of(token_for("user-1")) == "user-1"


@pytest.mark.parametrize("token", ["", "abc", "a.b.c", "a.e30.c"])
def test_subject_of_rejects_garbage(token):
    with pytest.raises(ValueError):
        subject_of(token)
