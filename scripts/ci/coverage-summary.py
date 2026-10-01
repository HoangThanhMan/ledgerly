#!/usr/bin/env python3
"""In bảng coverage (JaCoCo, gộp unit test và integration test) dạng Markdown.

CI ghi kết quả vào $GITHUB_STEP_SUMMARY. Chạy local sau ./gradlew check:  scripts/ci/coverage-summary.py
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
        print("Không có báo cáo JaCoCo: build dừng trước khi tạo báo cáo.")
        return
    print("| Module | Dòng | Nhánh |")
    print("|---|---:|---:|")
    for path in reports:
        counters = {c.get("type"): c for c in ET.parse(path).getroot().findall("counter")}
        module = path.split("/", 1)[0]
        print(f"| `{module}` | {percent(counters.get('LINE'))} | {percent(counters.get('BRANCH'))} |")
    print("\nTuần 2 chỉ báo cáo. Ngưỡng ≥ 80% dòng cho `internal.domain` bắt đầu từ tuần 4.")


if __name__ == "__main__":
    main()
