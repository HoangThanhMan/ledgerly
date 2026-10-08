#!/usr/bin/env python3
"""Reduces the raw output of `k6 run --out json=raw.json.gz` to one line per request.

k6 writes a JSON line for every metric of every request, several hundred megabytes for a five minute run. What a
reader needs to recompute the percentiles is one duration per request, so that is what gets committed:

    python3 perf/compact-raw.py raw.json.gz perf/results/<run>/requests.csv.gz

Let k6 compress its output (a file name ending in .gz) and keep it off a memory-backed /tmp: uncompressed, a run
takes memory or disk away from the system under test while it is being measured.

Columns: seconds since the first request, phase, name, HTTP status, duration in milliseconds.
"""

import csv
import gzip
import json
import sys
from datetime import datetime


def main(raw_path: str, out_path: str) -> None:
    rows = []
    opener = gzip.open if raw_path.endswith(".gz") else open
    with opener(raw_path, "rt", encoding="utf-8") as raw:
        for line in raw:
            point = json.loads(line)
            if point.get("type") != "Point" or point.get("metric") != "http_req_duration":
                continue
            tags = point["data"]["tags"]
            if tags.get("name") == "setup":
                continue
            rows.append(
                (
                    datetime.fromisoformat(point["data"]["time"]).timestamp(),
                    tags.get("phase", ""),
                    tags.get("name", ""),
                    tags.get("status", ""),
                    point["data"]["value"],
                )
            )
    rows.sort()
    start = rows[0][0] if rows else 0.0
    with gzip.open(out_path, "wt", encoding="utf-8", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(["t_seconds", "phase", "name", "status", "duration_ms"])
        for time, phase, name, status, duration in rows:
            writer.writerow([f"{time - start:.3f}", phase, name, status, f"{duration:.3f}"])
    print(f"{len(rows)} requests -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
