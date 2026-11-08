# Evals

15 conversations in Vietnamese ([`cases.jsonl`](cases.jsonl)), each with what a good answer does: prepare the right
proposal, ask a question when something is missing, or prepare nothing (too many tickets, not enough money, sold out,
prompt injection, requests outside ticketing). Ledgerly is played by a mock with a fixed set of events and wallets, so
the eval needs no Docker; the model is real. Answers are scored by code, not by another model.

```bash
export GROQ_API_KEY=...       # and GEMINI_API_KEY=... to include Gemini
uv run python -m evals.run                       # every model in models.toml that has a key
uv run python -m evals.run --only groq-gpt-oss-20b --case ask-which-wallet
```

It uses the providers' quota (about 5,000 tokens per case), so it runs by hand, not in CI. The exit code is 1 when
the default model passes fewer than 90% of the cases.

## Results

2026-11-07, Groq free tier, Ledgerly mocked, `temperature=0`:

| Model | Passed | p50 latency | Tokens |
|---|---|---|---|
| `openai/gpt-oss-120b` | 15/15 | 8.2 s | 53,311 |
| `openai/gpt-oss-20b` | 11/15 | 6.7 s | 36,671 |

`gpt-oss-20b` prepared no proposal in four of the five clear purchase cases, so `gpt-oss-120b` is the default.

The first run found a real bug: models send dates as timestamps (`2027-04-01T00:00:00Z`), and Groq checks tool calls
against the schema before the service sees them, so `format: date` turned two cases into provider errors. The date
arguments are now plain strings in the schema and are cut to their date part when validated.

A first end-to-end run against the real ledger-app showed the model writing "Đặt hàng thành công" (order succeeded)
after only preparing a proposal, and replying in Markdown, which the page shows as raw asterisks. The grader now fails
a reply that claims a purchase or uses Markdown, the prompt says so, and the service strips Markdown emphasis from
replies. With the stricter grader and before the stripping, `gpt-oss-120b` passed 12/15: no purchase claims, two
Markdown replies (now stripped), and `buy-no-accents`, where the model narrowed its own search to the next month and
missed the event; that case passed in the run above, so it varies from run to run.

`qwen2:7b` on Ollama was also tried. It calls tools, but on a laptop with a 4 GB GPU it read prompts at about 5 tokens
per second, several minutes per answer, so it was not run through the cases.
