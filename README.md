# Kestrel — an offline research assistant for Android

Kestrel answers research questions entirely on the phone: no network permission, no Google Play
Services, no remote inference. It is an entry for the bounty *"Build the Best Offline AI Research
App for Android"* (12 GB RAM, 50 GB storage, GrapheneOS-compatible hardware, Airplane Mode).

**The idea:** a large model does not need to answer every question. Kestrel routes each question
to the cheapest pipeline that can answer it, and it makes every model read as little as possible:

```
question ─► classify ─► route ─┬─ lookup ────► hybrid search ─► best sentences ─► fast model ─────────► cite + check
                               ├─ explain ───► hybrid search ─► best sentences ─► strong model ───────► cite + check
                               └─ multi-hop / compare / synthesis
                                     plan (sub-questions) ─► per-hop search + extraction (fast model)
                                     ─► evidence graph ─► strong or deep (streamed MoE) model ─► claim verification ─► cite
```

* **Hybrid retrieval** over an offline Wikipedia pack: SQLite FTS5 BM25, entity linking through
  titles and aliases, dense vectors (IVF with 1-bit codes and int8 rescoring, memory-mapped),
  reciprocal-rank fusion and feature reranking.
* **Evidence compression.** Only the sentences that matter reach the model. Prompt reading
  dominates latency on a phone CPU.
* **Prefix caching by state snapshots.** System prompts are read once per model, including on
  hybrid DeltaNet (Qwen3.5/3.6) and sliding-window (Gemma) models, whose caches cannot simply
  be truncated.
* **Decomposition** of multi-hop questions into dependent sub-questions, each answered from its own
  sources before the next is formed.
* **Claim verification.** Every sentence of the answer is checked against the passages it cites
  (content overlap and exact numbers), with a model check for the weak ones. Unsupported claims are
  flagged in the UI.
* **Refusal.** When the library does not support an answer, Kestrel says so. An answer from model
  memory is available only on request and is labelled unverified.
* **Any GGUF model** through upstream llama.cpp. Roles: *fast* (2B class), *strong* (4B class),
  *deep* (35B-A3B MoE, experts streamed from flash) and *embed*.

Design and decisions: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) ·
competitors: [docs/COMPETITIVE_ANALYSIS.md](docs/COMPETITIVE_ANALYSIS.md) ·
models: [docs/MODELS.md](docs/MODELS.md).

## Status (2026-09-28)

| Part | State |
|---|---|
| Engine (retrieval, evidence, router, planner, hops, verifier) | working; unit tests in CI; tested end to end on the desktop with Qwen3.5-2B/4B on a 3.1M-passage Wikipedia subset |
| Native bridge (llama.cpp, pinned) | working on x86-64 (desktop CLI); arm64 build in CI |
| Android app | builds in CI (see Actions); **not yet run on a phone** |
| Knowledge pack builder | working (Wikipedia parquet, jsonl, plain text); dev pack built (418k articles) |
| Vectors | builder and index working; dev pack partially embedded |
| Benchmark | 165 questions, desktop and on-device runners, scorer; phone results pending |
| Deep tier (streamed MoE) | load mode implemented; **not yet measured on a phone** |

**Performance on a phone: not measured yet.** The table below is from the development VM (4-core
x86 VM, AVX-512, no GPU) and exists only to show where time goes. The phone will be slower in
prefill and similar in decode [estimate]. Numbers for the demo will come from the phone
([docs/BENCHMARK.md](docs/BENCHMARK.md)).

| Desktop VM, dev pack | Quick (2B) | Research, lookup with scout step (2B) | Research, comparison (plan + 2 aspects, 4B answer) |
|---|---|---|---|
| Total | 9-14 s | 23 s | 131 s (455-token answer; answer budgets since reduced) |
| Retrieval | 0.15-0.6 s | 0.2-0.4 s | 1.7 s |
| Time to first answer token | 3-3.8 s | 5.6-6.8 s | 14.5 s |

## Install and test

1. APK: GitHub Releases or the CI artifact ([docs/INSTALL.md](docs/INSTALL.md)).
2. Models: `python tools/fetch_models.py --out models` (SHA-256 verified).
3. Knowledge pack: `tools/build_pack.py` from the Wikipedia dump ([docs/KNOWLEDGE_PACKS.md](docs/KNOWLEDGE_PACKS.md)).
4. Copy to the phone: `scripts/push_to_phone.sh models packs/enwiki`.
5. Airplane Mode test: [docs/OFFLINE_TESTING.md](docs/OFFLINE_TESTING.md).
6. Benchmark: [docs/BENCHMARK.md](docs/BENCHMARK.md). Demo script: [docs/DEMO_PLAN.md](docs/DEMO_PLAN.md).

Building from source: [docs/BUILDING.md](docs/BUILDING.md). What comes next: [docs/ROADMAP.md](docs/ROADMAP.md).

## Offline by construction

* The APK requests no network permission. `scripts/verify_offline.sh` fails the CI build if any
  permission such as INTERNET appears, or if GMS/Firebase components or networking libraries
  appear.
* There is no downloader in the app. Models and packs are copied over USB.
* The **Offline** badge is computed from the package's permissions, not from connectivity.

## Repository

| Path | What |
|---|---|
| `engine/` | Kotlin research engine, platform-independent, unit-tested on the JVM |
| `native/` | JNI bridge (`src/kestrel_jni.cpp`) over upstream llama.cpp (git submodule, pinned) |
| `app/` | Android app (Kotlin, Jetpack Compose) |
| `bench/` | desktop CLI running the same engine and native code |
| `tools/` | pack builder, embeddings and vector index, model fetcher, benchmark scorer |
| `benchmark/` | question set (165 questions, 15 categories) |
| `scripts/` | native host build, offline audit, adb helpers |

## Licences

Code: Apache-2.0 ([LICENSE](LICENSE)). llama.cpp: MIT. Models and knowledge packs keep their own
licences: Qwen3.5/3.6 and Gemma 4 are Apache-2.0, nomic-embed is Apache-2.0, and Wikipedia text
is CC BY-SA 4.0 ([NOTICE.md](NOTICE.md)).
