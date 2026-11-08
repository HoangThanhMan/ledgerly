"""The agent loop: ask the model, run the tools it calls, and repeat until it answers."""

import json
import logging
import re
import time
from dataclasses import dataclass
from typing import Literal

from assistant.ledger_client import LedgerError
from assistant.llm import LlmClient
from assistant.prompts import SYSTEM_PROMPT
from assistant.proposals import Proposal
from assistant.tools import TOOLS, ToolBox, ToolError

Language = Literal["vi", "en"]

GAVE_UP: dict[Language, str] = {
    "vi": "Mình chưa xử lý xong yêu cầu này, bạn thử nói cụ thể hơn nhé.",
    "en": "I couldn't finish that request. Could you say it more specifically?",
}
STOPPED: dict[Language, str] = {
    "vi": "Mình không thể trả lời yêu cầu này, bạn thử hỏi cách khác nhé.",
    "en": "I can't answer that request. Could you ask it another way?",
}
_PREFIX: dict[Language, str] = {
    "vi": "[Hôm nay: {today}. Trả lời bằng tiếng Việt.]",
    "en": "[Today: {today}. Reply in English.]",
}

log = logging.getLogger(__name__)

# Models write Markdown even when told not to, and the page shows replies as plain text.
_EMPHASIS = re.compile(r"\*\*(.+?)\*\*|__(.+?)__", re.DOTALL)
_HEADING = re.compile(r"^#{1,6} +", re.MULTILINE)
# Models write amounts as "400.000\u202f₫". Sent back in the history, such spaces make gpt-oss repeat itself and
# garble its next reply, so the conversation keeps plain spaces only.
_ODD_SPACES = re.compile(r"[\u00a0\u2000-\u200a\u202f\u205f\u3000]")


def plain_spaces(text: str) -> str:
    return _ODD_SPACES.sub(" ", text)


def plain_text(text: str) -> str:
    return plain_spaces(_HEADING.sub("", _EMPHASIS.sub(lambda m: m.group(1) or m.group(2), text)))


def context(messages: list[dict], turns: int = 10) -> list[dict]:
    """What the model is sent: the system prompt and the last `turns` user turns, each kept whole, so that a long
    conversation stays within the model's limits and no tool call is separated from its results."""
    starts = [i for i, message in enumerate(messages) if message["role"] == "user"]
    if len(starts) > turns:
        system = messages[:1] if messages and messages[0]["role"] == "system" else []
        messages = system + messages[starts[-turns] :]
    # Conversations saved before replies were cleaned may still hold odd spaces.
    return [
        {**m, "content": plain_spaces(m["content"])}
        if m["role"] == "assistant" and isinstance(m.get("content"), str)
        else m
        for m in messages
    ]


@dataclass(frozen=True)
class AgentReply:
    text: str
    proposals: list[Proposal]
    input_tokens: int
    output_tokens: int
    steps: int


def user_message(text: str, today: str, language: Language = "vi") -> dict:
    """The date and the reply language go in the user's message, so the system prompt never changes."""
    return {"role": "user", "content": _PREFIX[language].format(today=today) + "\n" + text}


class Agent:
    def __init__(self, llm: LlmClient, max_steps: int = 8):
        self._llm = llm
        self._max_steps = max_steps

    def reply(self, messages: list[dict], toolbox: ToolBox, language: Language = "vi") -> AgentReply:
        """Answers the last user message. Appends every turn to `messages`, which is the conversation."""
        if not messages or messages[0]["role"] != "system":
            messages.insert(0, {"role": "system", "content": SYSTEM_PROMPT})
        started = time.monotonic()
        input_tokens = output_tokens = steps = 0
        tools_called: list[str] = []
        text = GAVE_UP[language]
        closing: dict = {"role": "assistant", "content": text}

        while steps < self._max_steps:
            turn = self._llm.complete(context(messages), TOOLS)
            steps += 1
            input_tokens += turn.input_tokens
            output_tokens += turn.output_tokens
            if turn.finish in ("length", "content_filter"):
                text = STOPPED[language]
                closing = {"role": "assistant", "content": text}
                break
            if not turn.tool_calls:
                if turn.text:
                    text = plain_text(turn.text)
                    closing = {**turn.message, "content": text}
                break
            messages.append(turn.message)
            for tool_call in turn.tool_calls:
                tools_called.append(tool_call.name)
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": _run(toolbox, tool_call.name, tool_call.arguments),
                    }
                )

        # Every turn ends with a plain assistant message. A cut-off or empty answer is replaced by our own text:
        # stored as is, content null would make the provider reject every later turn of the conversation.
        messages.append(closing)
        log.info(
            "reply steps=%d tools=%s input_tokens=%d output_tokens=%d seconds=%.2f",
            steps,
            ",".join(tools_called) or "-",
            input_tokens,
            output_tokens,
            time.monotonic() - started,
        )
        return AgentReply(text, list(toolbox.created), input_tokens, output_tokens, steps)


def _run(toolbox: ToolBox, name: str, arguments: str) -> str:
    try:
        return json.dumps(toolbox.run(name, arguments), ensure_ascii=False)
    except ToolError as error:
        return f"Error: {error}"
    except LedgerError as error:
        if error.status == 401:
            raise  # the user's session ended: that is for the user to fix, not the model
        return f"Error: Ledgerly answered {error.status} {error.title}"
