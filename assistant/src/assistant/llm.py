"""The language model, behind a small interface. Any OpenAI-compatible chat completions API works:
Groq, Gemini, Ollama, Azure OpenAI and others differ only by base URL, model name and key."""

from dataclasses import dataclass
from typing import Protocol

import openai


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    # Raw JSON text as the model wrote it: it may be invalid, and is validated by the tool.
    arguments: str


@dataclass(frozen=True)
class LlmTurn:
    text: str | None
    tool_calls: list[ToolCall]
    finish: str
    input_tokens: int
    output_tokens: int
    # The assistant message to append to the conversation, in chat completions format.
    message: dict


class LlmUnavailable(Exception):
    """The model could not answer. `status` is the provider's HTTP status, None when it could not be reached."""

    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


class LlmClient(Protocol):
    def complete(self, messages: list[dict], tools: list[dict]) -> LlmTurn: ...


class OpenAICompatClient:
    def __init__(self, base_url: str, api_key: str, model: str, client: openai.OpenAI | None = None):
        self._model = model
        self._client = client or openai.OpenAI(base_url=base_url, api_key=api_key, timeout=60, max_retries=2)

    @property
    def model(self) -> str:
        return self._model

    def complete(self, messages: list[dict], tools: list[dict]) -> LlmTurn:
        try:
            response = self._client.chat.completions.create(
                model=self._model, messages=messages, tools=tools, tool_choice="auto", temperature=0
            )
        except openai.APIConnectionError as error:
            raise LlmUnavailable(str(error)) from error
        except openai.APIStatusError as error:
            # 429 and 5xx after the SDK's retries, but also 400 (a tool call the provider could not parse),
            # 413 (a conversation over the free tier's token limit), 401 or 404 (a wrong key or model name).
            raise LlmUnavailable(str(error), error.status_code) from error

        choice = response.choices[0]
        calls = [
            ToolCall(c.id, c.function.name, c.function.arguments or "")
            for c in choice.message.tool_calls or []
            if c.type == "function"
        ]
        # Built by hand rather than dumped: providers add fields (reasoning, refusal...) that others reject.
        message: dict = {"role": "assistant", "content": choice.message.content}
        if calls:
            message["tool_calls"] = [
                {"id": c.id, "type": "function", "function": {"name": c.name, "arguments": c.arguments}} for c in calls
            ]
        usage = response.usage
        return LlmTurn(
            text=choice.message.content,
            tool_calls=calls,
            finish=choice.finish_reason or "stop",
            input_tokens=(usage.prompt_tokens or 0) if usage else 0,
            output_tokens=(usage.completion_tokens or 0) if usage else 0,
            message=message,
        )
