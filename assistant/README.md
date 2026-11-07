# Assistant

A chat assistant that helps Ledgerly users find events and prepare ticket orders.

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
