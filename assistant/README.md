# Assistant

A chat assistant that helps Ledgerly users find events and prepare ticket orders. See the main
[README](../README.md#assistant) for what it does and why it cannot spend money.

## Run it

```bash
cp .env.example .env          # set LLM_API_KEY
uv run --env-file .env assistant   # http://localhost:8090, next to ledger-app on :8080
```

`POST /assistant/chat` takes `{"conversationId": null, "message": "..."}` with the user's
`Authorization: Bearer <token>`, and returns the reply and any proposals.
`POST /assistant/proposals/{id}/confirm` buys a proposal and passes Ledgerly's answer through unchanged.

## Choosing a model

Any OpenAI-compatible chat completions API works. Set three variables:

| Provider | `LLM_BASE_URL` | `LLM_MODEL` (example) | Notes |
|---|---|---|---|
| Groq (default) | `https://api.groq.com/openai/v1` | `openai/gpt-oss-120b` | Free tier, no card |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` | a Gemini Flash model | Free tier |
| Ollama | `http://localhost:11434/v1` | a local model with tool calling | Runs offline; small models handle Vietnamese and tools poorly |
| Azure OpenAI | your deployment's `/openai/v1` URL | your deployment | Paid |

[`evals`](evals) compares models on the same cases; pick the default from its numbers.

## Tests

```bash
uv run pytest -q                                   # no network: a scripted model and a mocked Ledgerly
uv run ruff check . && uv run ruff format --check .
```

## Use the tools from an MCP host

The same tools are available as an MCP server over stdio, for any MCP host (a desktop chat app, an IDE). The host
brings its own model; the server needs only a Ledgerly access token.

```json
{
  "mcpServers": {
    "ledgerly": {
      "command": "uv",
      "args": ["run", "--directory", "/path/to/ledgerly/assistant", "assistant-mcp"],
      "env": {
        "LEDGERLY_API_URL": "http://localhost:8080",
        "LEDGERLY_TOKEN": "<accessToken from POST /v1/auth/login>"
      }
    }
  }
}
```

`confirm_purchase` is the only tool that spends money. It is marked destructive, so the host asks before running it:
always review that request yourself, never allow it permanently.
