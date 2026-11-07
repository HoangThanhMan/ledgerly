# syntax=docker/dockerfile:1

# The assistant service. compose.yaml builds it in the "assistant" profile:
#
#   docker build -t assistant assistant

FROM python:3.13-slim
COPY --from=ghcr.io/astral-sh/uv:0.12.7 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never PATH="/app/.venv/bin:$PATH"
WORKDIR /app
# Dependencies first, in their own layer: they change far less often than the code.
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev --no-install-project
COPY README.md ./
COPY src ./src
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev
# /data holds the SQLite file; a named volume mounted there takes this owner on first use.
RUN useradd --system --uid 1001 app && mkdir /data && chown app /data
USER app
EXPOSE 8090
CMD ["assistant"]
