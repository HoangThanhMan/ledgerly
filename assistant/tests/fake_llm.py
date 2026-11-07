"""A scripted stand-in for the model, so agent tests never call a real LLM."""

import copy
import json

from assistant.llm import LlmTurn, ToolCall


def say(text, finish="stop"):
    return LlmTurn(text, [], finish, 10, 5, {"role": "assistant", "content": text})


def call(*calls, finish="tool_calls"):
    """Each call is (id, name, arguments), where arguments is a dict or an already-encoded string."""
    tool_calls = [ToolCall(id, name, args if isinstance(args, str) else json.dumps(args)) for id, name, args in calls]
    message = {
        "role": "assistant",
        "content": None,
        "tool_calls": [
            {"id": c.id, "type": "function", "function": {"name": c.name, "arguments": c.arguments}} for c in tool_calls
        ],
    }
    return LlmTurn(None, tool_calls, finish, 10, 5, message)


class FakeLlm:
    def __init__(self, script):
        self._script = list(script)
        self.requests = []

    def complete(self, messages, tools):
        self.requests.append({"messages": copy.deepcopy(messages), "tools": tools})
        if not self._script:
            raise AssertionError("the model was called more often than scripted")
        return self._script.pop(0)
