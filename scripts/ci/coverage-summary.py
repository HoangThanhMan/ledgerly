#!/usr/bin/env python3
"""Print a Markdown coverage table (JaCoCo, unit and integration tests combined).

CI appends the output to $GITHUB_STEP_SUMMARY. Run locally after ./gradlew check:  scripts/ci/coverage-summary.py
"""

import glob
import xml.etree.ElementTree as ET


def percent(counter):
    if counter is None:
        return "—"
    covered, missed = int(counter.get("covered")), int(counter.get("missed"))
    total = covered + missed
    return f"{100 * covered / total:.1f}% ({covered}/{total})" if total else "—"


def main():
    reports = sorted(glob.glob("*/build/reports/jacoco/test/jacocoTestReport.xml"))
    print("## Coverage (JaCoCo)\n")
    if not reports:
        print("No JaCoCo report: the build stopped before generating one.")
        return
    print("| Module | Line | Branch |")
    print("|---|---:|---:|")
    for path in reports:
        counters = {c.get("type"): c for c in ET.parse(path).getroot().findall("counter")}
        module = path.split("/", 1)[0]
        print(f"| `{module}` | {percent(counters.get('LINE'))} | {percent(counters.get('BRANCH'))} |")


if __name__ == "__main__":
    main()
