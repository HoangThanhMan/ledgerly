"""JSON in the shapes the Ledgerly API returns. Amounts are strings of VND, as on the wire."""

import base64
import json
import uuid


def ticket_class_json(name="Thường", price=300_000, remaining=100, total=100, class_id=None):
    return {
        "id": class_id or str(uuid.uuid4()),
        "name": name,
        "price": str(price),
        "currency": "VND",
        "totalQty": total,
        "remaining": remaining,
    }


def event_json(name="Ledgerly Live", starts_at="2027-03-01T12:00:00Z", classes=None, event_id=None):
    classes = classes if classes is not None else [ticket_class_json()]
    return {
        "id": event_id or str(uuid.uuid4()),
        "name": name,
        "startsAt": starts_at,
        "payoutWalletId": str(uuid.uuid4()),
        "ticketClasses": classes,
    }


def event_summary_json(event):
    return {
        "id": event["id"],
        "name": event["name"],
        "startsAt": event["startsAt"],
        "minPrice": min((c["price"] for c in event["ticketClasses"]), key=int),
    }


def event_page_json(events, page=0, size=100, total=None):
    return {
        "items": [event_summary_json(e) for e in events],
        "page": page,
        "size": size,
        "totalItems": total if total is not None else len(events),
    }


def wallet_json(balance=1_000_000, wallet_id=None):
    return {
        "id": wallet_id or str(uuid.uuid4()),
        "currency": "VND",
        "balance": str(balance),
        "createdAt": "2026-11-01T00:00:00Z",
    }


def problem_json(status, slug, title, detail=None):
    body = {"type": f"https://ledgerly.dev/problems/{slug}", "title": title, "status": status}
    if detail:
        body["detail"] = detail
    return body


def token_for(sub):
    def part(data):
        return base64.urlsafe_b64encode(json.dumps(data).encode()).rstrip(b"=").decode()

    return f"{part({'alg': 'HS256'})}.{part({'sub': sub})}.signature"
