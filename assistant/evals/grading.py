"""Scores one eval case with plain code: no model judges another model here."""


def grade(expect: dict, proposals: list[dict], tools_called: list[str], reply: str) -> list[str]:
    """Returns what went wrong; an empty list means the case passed."""
    failures = []
    if wanted := expect.get("proposal"):
        target = f"{wanted['quantity']} × {wanted['class_name']}"
        if len(proposals) > 1:
            failures.append(f"expected one proposal, got {len(proposals)}")
        elif not proposals:
            failures.append(f"expected {target}, got no proposal")
        elif (proposals[0]["quantity"], proposals[0]["className"]) != (wanted["quantity"], wanted["class_name"]):
            failures.append(f"expected {target}, got {proposals[0]['quantity']} × {proposals[0]['className']}")
    if (expect.get("no_proposal") or expect.get("asks_clarification")) and proposals:
        failures.append(f"expected no proposal, got {len(proposals)}")
    if expect.get("asks_clarification") and "?" not in reply:
        failures.append("expected a question")
    for tool in expect.get("tools_include", []):
        if tool not in tools_called:
            failures.append(f"expected a call to {tool}")
    if expect.get("no_tools") and tools_called:
        failures.append(f"expected no tool calls, got {','.join(tools_called)}")
    return failures
