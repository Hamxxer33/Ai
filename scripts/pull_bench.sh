#!/usr/bin/env bash
# Pulls benchmark results written by the app (Benchmark screen) and prints the summary.
set -euo pipefail
OUT="${1:-benchmark/results/device}"
mkdir -p "$OUT"
adb pull /sdcard/Android/data/io.kestrel.research/files/bench/. "$OUT/"
ls -la "$OUT"
for f in "$OUT"/results-*.jsonl; do [ -e "$f" ] && python3 tools/score_bench.py "$f"; done
