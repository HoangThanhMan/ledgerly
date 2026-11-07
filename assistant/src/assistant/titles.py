"""Short titles for conversations, written by the model from the first question and answer."""

import re

from assistant.agent import plain_text
from assistant.llm import LlmClient, LlmUnavailable

MAX_LENGTH = 60

_LANGUAGES = {"vi": "Vietnamese", "en": "English"}
_PROMPT = (
    "Write a title for this conversation with a ticketing assistant: at most 6 words, in {language}, naming what "
    "the user wants, in sentence case. Reply with the title only: no quotes, no label, no full stop."
)
_LABEL = re.compile(r"^(title|tiêu đề)\s*:\s*", re.IGNORECASE)


class TitleWriter:
    def __init__(self, llm: LlmClient):
        self._llm = llm

    def __call__(self, question: str, answer: str, language: str) -> str | None:
        """A short title, or None when the model is unavailable or gives nothing usable."""
        messages = [
            {"role": "system", "content": _PROMPT.format(language=_LANGUAGES.get(language, "Vietnamese"))},
            {"role": "user", "content": f"User: {question}\nAssistant: {answer[:500]}"},
        ]
        try:
            turn = self._llm.complete(messages, [])
        except LlmUnavailable:
            return None
        text = turn.text or ""
        if text.count("\n") > 1:
            return None
        title = _LABEL.sub("", " ".join(plain_text(text).split())).strip(" \"'“”‘’.")
        return title if 0 < len(title) <= MAX_LENGTH else None
