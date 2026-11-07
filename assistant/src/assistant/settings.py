import os
from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    ledgerly_api_url: str
    allowed_origin: str
    llm_base_url: str
    llm_model: str
    llm_api_key: str
    # Local models (Ollama) can take minutes per answer; hosted APIs take seconds.
    llm_timeout_seconds: float = 60
    database_path: str = "assistant.db"

    @staticmethod
    def from_env(env: Mapping[str, str] = os.environ) -> "Settings":
        api_key = env.get("LLM_API_KEY", "")
        if not api_key:
            raise ValueError("LLM_API_KEY is not set. Groq gives a free key at https://console.groq.com/keys")
        return Settings(
            ledgerly_api_url=env.get("LEDGERLY_API_URL", "http://localhost:8080"),
            allowed_origin=env.get("ASSISTANT_ALLOWED_ORIGIN", "http://localhost:8080"),
            llm_base_url=env.get("LLM_BASE_URL", "https://api.groq.com/openai/v1"),
            llm_model=env.get("LLM_MODEL", "openai/gpt-oss-120b"),
            llm_api_key=api_key,
            llm_timeout_seconds=float(env.get("LLM_TIMEOUT_SECONDS", "60")),
            database_path=env.get("ASSISTANT_DB_PATH", "assistant.db"),
        )
