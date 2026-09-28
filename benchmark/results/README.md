# Benchmark results

Each file is one run: JSONL, one `BenchResult` per question (see docs/BENCHMARK.md).
Summaries come from `python tools/score_bench.py <file>`.

| File | Where | Pack | Models | Mode | Notes |
|---|---|---|---|---|---|
| `desktop-dev-sample1.jsonl` | 4-core x86 VM (development container) | enwiki-dev (3 GB 2020 subset) | Qwen3.5-2B Q4_K_M (fast), Qwen3.5-4B Q4_K_M (strong) | auto | 1 question per category; timings contended by a concurrent embedding job, so use them only for relative step costs |

Phone results will be added as `phone-<device>-<date>-<mode>.jsonl` together with the runtime
benchmark file from the app.
