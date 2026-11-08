"""A small client for the Ledgerly REST API, acting as the user whose token it carries."""

from dataclasses import dataclass

import httpx


class LedgerError(Exception):
    """Ledgerly answered a read with an error (RFC 9457 problem)."""

    def __init__(self, status: int, title: str, detail: str | None = None):
        super().__init__(f"{status} {title}" + (f": {detail}" if detail else ""))
        self.status = status
        self.title = title
        self.detail = detail


class LedgerUnavailable(Exception):
    """Ledgerly could not be reached."""


@dataclass(frozen=True)
class OrderOutcome:
    """Ledgerly's answer to an order, whether it was accepted or refused."""

    status: int
    body: dict
    retry_after: str | None


class LedgerClient:
    def __init__(self, base_url: str, token: str, http: httpx.Client):
        self._base_url = base_url.rstrip("/")
        self._headers = {"Authorization": f"Bearer {token}"}
        self._http = http

    def list_events(self, page: int, size: int) -> dict:
        return self._get("/v1/events", params={"page": page, "size": size})

    def get_event(self, event_id: str) -> dict:
        return self._get(f"/v1/events/{event_id}")

    def list_wallets(self) -> list[dict]:
        return self._get("/v1/wallets")["items"]

    def create_order(self, ticket_class_id: str, wallet_id: str, quantity: int, idempotency_key: str) -> OrderOutcome:
        response = self._send(
            "POST",
            "/v1/orders",
            json={"ticketClassId": ticket_class_id, "walletId": wallet_id, "quantity": quantity},
            headers={"Idempotency-Key": idempotency_key},
        )
        return OrderOutcome(response.status_code, _json_or_empty(response), response.headers.get("Retry-After"))

    def _get(self, path: str, params: dict | None = None):
        response = self._send("GET", path, params=params)
        if response.is_success:
            return response.json()
        body = _json_or_empty(response)
        raise LedgerError(response.status_code, body.get("title") or response.reason_phrase, body.get("detail"))

    def _send(self, method: str, path: str, headers: dict | None = None, **kwargs) -> httpx.Response:
        try:
            return self._http.request(
                method, self._base_url + path, headers={**self._headers, **(headers or {})}, **kwargs
            )
        except httpx.TransportError as error:
            raise LedgerUnavailable(str(error)) from error


def _json_or_empty(response: httpx.Response) -> dict:
    try:
        body = response.json()
    except ValueError:
        return {}
    return body if isinstance(body, dict) else {}
