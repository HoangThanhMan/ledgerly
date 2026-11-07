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

Not run yet.
