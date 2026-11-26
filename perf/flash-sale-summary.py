#!/usr/bin/env python3
"""Summarizes the orders of one flash-sale run from its compacted requests (see perf/compact-raw.py):

    python3 -I perf/flash-sale-summary.py perf/results/<name>/run-1/requests.csv.gz

Prints one line: how many orders were won (201), lost because the class was sold out (409), shed (503) or answered
otherwise; when the last order was won, in seconds since the first request; and latency percentiles of winners
and losers, nearest rank, in milliseconds.
"""

import csv
import gzip
import math
import sys


def percentile(values: list[float], q: float) -> str:
    if not values:
        return "null"
    ordered = sorted(values)
    return f"{ordered[max(math.ceil(q * len(ordered)), 1) - 1]:.1f}"


def summarize(path: str) -> str:
    won, lost, shed, other, durations = [], [], 0, 0, []
    last_win = None
    with gzip.open(path, "rt", encoding="utf-8", newline="") as file:
        for row in csv.DictReader(file):
            if row["name"] != "order":
                continue
            duration = float(row["duration_ms"])
            durations.append(duration)
            if row["status"] == "201":
                won.append(duration)
                last_win = row["t_seconds"]
            elif row["status"] == "409":
                lost.append(duration)
            elif row["status"] == "503":
                shed += 1
            else:
                other += 1
    return (
        f"requests={len(durations)} won={len(won)} sold_out={len(lost)} shed={shed} other={other}"
        f" last_win_s={last_win or 'null'}"
        f" won_p50={percentile(won, 0.50)} won_p99={percentile(won, 0.99)}"
        f" lost_p50={percentile(lost, 0.50)} lost_p99={percentile(lost, 0.99)}"
        f" max={max(durations):.1f}" if durations else "requests=0"
    )


if __name__ == "__main__":
    print(summarize(sys.argv[1]))
