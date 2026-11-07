import json
import pathlib

import httpx2
import openai
import pytest

from assistant.llm import LlmUnavailable, OpenAICompatClient, ToolCall

BASE_URL = "http://llm.test/v1"


class Completions:
    """Plays the chat completions endpoint. openai 3.x sends through httpx2, which respx does not patch."""

    def __init__(self):
        self.requests = []
        self._answer = None

    def respond(self, status, json):
        self._answer = lambda: httpx2.Response(status, json=json)

    def fail(self, error):
        def raise_it():
            raise error

        self._answer = raise_it

    def handle(self, request):
        assert request.url.path == "/v1/chat/completions"
        self.requests.append(request)
        return self._answer()


@pytest.fixture
def completions():
    return Completions()


@pytest.fixture
def llm(completions):
    http = httpx2.Client(transport=httpx2.MockTransport(completions.handle))
    sdk = openai.OpenAI(base_url=BASE_URL, api_key="secret", max_retries=0, http_client=http)
    return OpenAICompatClient(BASE_URL, "secret", "some-model", client=sdk)


def completion(message, finish="stop", usage=(12, 7)):
    return {
        "id": "c1",
        "object": "chat.completion",
        "created": 0,
        "model": "some-model",
        "choices": [{"index": 0, "message": {"role": "assistant", **message}, "finish_reason": finish}],
        "usage": {"prompt_tokens": usage[0], "completion_tokens": usage[1], "total_tokens": sum(usage)},
    }


def test_request_shape(llm, completions):
    completions.respond(200, json=completion({"content": "Chào bạn"}))
    tools = [{"type": "function", "function": {"name": "t", "parameters": {"type": "object"}}}]

    llm.complete([{"role": "user", "content": "hi"}], tools)

    request = completions.requests[-1]
    body = json.loads(request.content)
    assert body["model"] == "some-model"
    assert body["tools"] == tools
    assert body["tool_choice"] == "auto"
    assert body["temperature"] == 0
    assert body["messages"] == [{"role": "user", "content": "hi"}]
    assert request.headers["Authorization"] == "Bearer secret"


def test_parses_tool_calls_and_usage(llm, completions):
    tool_calls = [
        {"id": "a", "type": "function", "function": {"name": "get_event", "arguments": '{"event_id": "1"}'}},
        {"id": "b", "type": "function", "function": {"name": "list_wallets", "arguments": "{}"}},
    ]
    completions.respond(200, json=completion({"content": None, "tool_calls": tool_calls}, finish="tool_calls"))

    turn = llm.complete([], [])

    assert turn.tool_calls == [ToolCall("a", "get_event", '{"event_id": "1"}'), ToolCall("b", "list_wallets", "{}")]
    assert (turn.finish, turn.input_tokens, turn.output_tokens) == ("tool_calls", 12, 7)
    assert turn.message == {"role": "assistant", "content": None, "tool_calls": tool_calls}


def test_parses_plain_text(llm, completions):
    completions.respond(200, json=completion({"content": "Xin chào", "reasoning": "hidden"}))

    turn = llm.complete([], [])

    assert (turn.text, turn.tool_calls, turn.finish) == ("Xin chào", [], "stop")
    assert turn.message == {"role": "assistant", "content": "Xin chào"}


@pytest.mark.parametrize("status", [429, 500, 503])
def test_overload_raises_unavailable(llm, completions, status):
    completions.respond(status, json={"error": {"message": "slow down"}})

    with pytest.raises(LlmUnavailable):
        llm.complete([], [])


def test_connection_error_raises_unavailable(llm, completions):
    completions.fail(httpx2.ConnectError("refused"))

    with pytest.raises(LlmUnavailable):
        llm.complete([], [])


def test_only_llm_module_imports_openai():
    sources = pathlib.Path(__file__).parents[1] / "src" / "assistant"
    importers = sorted(
        path.name
        for path in sources.glob("*.py")
        if "import openai" in path.read_text() or "from openai" in path.read_text()
    )
    assert importers == ["llm.py"]


@pytest.mark.parametrize("status", [400, 401, 404, 413])
def test_other_provider_errors_raise_unavailable_with_status(llm, completions, status):
    completions.respond(status, json={"error": {"message": "nope", "code": "tool_use_failed"}})

    with pytest.raises(LlmUnavailable) as error:
        llm.complete([], [])

    assert error.value.status == status


def test_timeout_is_configurable():
    assert OpenAICompatClient(BASE_URL, "k", "m", timeout=300)._client.timeout == 300


def test_no_tools_sends_no_tool_fields(llm, completions):
    completions.respond(200, json=completion({"content": "Sự kiện phần mềm"}))

    llm.complete([{"role": "user", "content": "x"}], [])

    body = json.loads(completions.requests[-1].content)
    assert "tools" not in body
    assert "tool_choice" not in body
