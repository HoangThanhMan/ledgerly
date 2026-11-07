import pytest

from assistant.llm import LlmUnavailable
from assistant.titles import TitleWriter
from tests.fake_llm import FakeLlm, say


class Unavailable:
    def complete(self, messages, tools):
        raise LlmUnavailable("quota")


def test_asks_for_a_short_title_in_the_language_without_tools():
    llm = FakeLlm([say("Sự kiện phần mềm")])

    title = TitleWriter(llm)("Hãy gợi ý sự kiện về phần mềm", "Có Hội thảo Kỹ sư Phần mềm 2027.", "vi")

    request = llm.requests[0]
    assert title == "Sự kiện phần mềm"
    assert request["tools"] == []
    assert "Vietnamese" in request["messages"][0]["content"]
    assert "Hãy gợi ý sự kiện về phần mềm" in request["messages"][1]["content"]


@pytest.mark.parametrize(
    ("raw", "title"),
    [
        ('"Software events"', "Software events"),
        ("**Vé Ledgerly Live.**", "Vé Ledgerly Live"),
        ("Title: Ví và số dư", "Ví và số dư"),
        ("  Mua   vé jazz \n", "Mua vé jazz"),
    ],
)
def test_cleans_the_title(raw, title):
    assert TitleWriter(FakeLlm([say(raw)]))("q", "a", "en") == title


@pytest.mark.parametrize("raw", ["", "   ", "x" * 61, "line one\nline two\nline three"])
def test_unusable_titles_give_none(raw):
    assert TitleWriter(FakeLlm([say(raw)]))("q", "a", "vi") is None


def test_unavailable_model_gives_none():
    assert TitleWriter(Unavailable())("q", "a", "vi") is None
