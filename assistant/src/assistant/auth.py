"""Reads who is calling. The token is not verified here: ledger-app verifies it on every call it receives."""

import base64
import binascii
import json


def subject_of(token: str) -> str:
    """Returns the `sub` claim of a JWT, without checking its signature."""
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("not a JWT")
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        claims = json.loads(base64.urlsafe_b64decode(payload))
    except (binascii.Error, ValueError) as error:
        raise ValueError("not a JWT") from error
    sub = claims.get("sub") if isinstance(claims, dict) else None
    if not isinstance(sub, str) or not sub:
        raise ValueError("JWT has no subject")
    return sub
