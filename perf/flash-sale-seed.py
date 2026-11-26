#!/usr/bin/env python3
"""Prepares a flash sale through the public API: one organizer, one event with one ticket class, and buyers who
each have a funded wallet. Writes what the load test needs to send orders:

    python3 -I perf/flash-sale-seed.py --base-url http://localhost:8080 --run-id 1700000000-1 \
        --buyers 20000 --capacity 1000 --out build/k6-raw/buyers.json

Output: {"eventId", "ticketClassId", "capacity", "buyers": [{"token", "walletId"}, ...]}.

Every buyer is set up the way a real one would be (sign up, sign in, open a wallet, receive a deposit), so the
ledger holds only what its own API wrote. Any failed request stops the script with exit code 1, and then no output
file is written: a load test must never start with fewer buyers than it reports.

Signing up costs a BCrypt hash per buyer. Start the application with LEDGERLY_SECURITY_PASSWORD_BCRYPT_STRENGTH=4
for seeding, or 20,000 buyers take many minutes. Access tokens live for one hour, so seed right before the run.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta

PASSWORD = "flash-sale-password"
WORKERS = 32


class SeedError(Exception):
    pass


def call(base_url: str, method: str, path: str, body: object, token: str | None = None, key: str | None = None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(
        base_url + path, data=json.dumps(body).encode(), headers=headers, method=method
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        raise SeedError(f"{method} {path} -> {error.code}: {error.read().decode(errors='replace')}") from error
    except OSError as error:
        raise SeedError(f"{method} {path} -> {error}") from error


def sign_up(base_url: str, email: str, role: str) -> str:
    call(base_url, "POST", "/v1/auth/register", {"email": email, "password": PASSWORD, "role": role})
    return call(base_url, "POST", "/v1/auth/login", {"email": email, "password": PASSWORD})["accessToken"]


def open_wallet(base_url: str, token: str) -> str:
    return call(base_url, "POST", "/v1/wallets", {"currency": "VND"}, token)["id"]


def seed_buyer(args: argparse.Namespace, organizer_token: str, index: int) -> dict:
    token = sign_up(args.base_url, f"fs-{args.run_id}-{index}@perf.ledgerly.dev", "USER")
    wallet_id = open_wallet(args.base_url, token)
    call(
        args.base_url,
        "POST",
        "/v1/admin/deposits",
        {"walletId": wallet_id, "amount": str(2 * args.price), "currency": "VND"},
        organizer_token,
        key=f"fs-{args.run_id}-dep-{index}",
    )
    return {"token": token, "walletId": wallet_id}


def seed(args: argparse.Namespace) -> dict:
    organizer_token = sign_up(args.base_url, f"fs-{args.run_id}-organizer@perf.ledgerly.dev", "ORGANIZER")
    payout_wallet = open_wallet(args.base_url, organizer_token)
    event = call(
        args.base_url,
        "POST",
        "/v1/events",
        {
            "name": f"Flash sale {args.run_id}",
            "startsAt": (datetime.now(UTC) + timedelta(days=1)).isoformat(timespec="seconds"),
            "payoutWalletId": payout_wallet,
            "ticketClasses": [{"name": "FS-1", "price": str(args.price), "totalQty": args.capacity}],
        },
        organizer_token,
    )
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        buyers = list(pool.map(lambda i: seed_buyer(args, organizer_token, i), range(args.buyers)))
    return {
        "eventId": event["id"],
        "ticketClassId": event["ticketClasses"][0]["id"],
        "capacity": args.capacity,
        "buyers": buyers,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--buyers", type=int, default=20000)
    parser.add_argument("--capacity", type=int, default=1000)
    parser.add_argument("--price", type=int, default=100000)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    try:
        sale = seed(args)
    except SeedError as error:
        print(f"seeding failed: {error}", file=sys.stderr)
        return 1
    temporary = args.out + ".tmp"
    with open(temporary, "w", encoding="utf-8") as out:
        json.dump(sale, out)
    os.replace(temporary, args.out)
    print(f"{len(sale['buyers'])} buyers for ticket class {sale['ticketClassId']} -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
