# Architecture (Phases 2-5)

Status labels used in this document:
**[measured]** = measured by us on the stated hardware; **[reported]** = someone else's
measurement (source given); **[estimate]** = arithmetic or judgement, to be replaced by a
measurement. Nothing marked [estimate] should be quoted as a result.

## 1. Constraints that decide the design

1. **Prompt reading (prefill) dominates latency on a phone CPU.** AndroidLM measured 24-30
   tok/s prefill for Qwen3.6-35B-A3B 2-bit and 30.5 tok/s for Qwen3.5-4B Q4_K_M on a Pixel 8
   Pro CPU [reported: AndroidLM `notes/2026-09-24-speed-levers.md`]. A RAG prompt of 1,500
   tokens therefore costs ~50 s before the first word. Every token of evidence we hand the model
   has a price; the pipeline is designed around *reading less*.
2. **GrapheneOS means Pixel (Tensor G3/G4/G5).** No Snapdragon Hexagon NPU; the Tensor TPU is
   not reachable by third-party apps; llama.cpp on the Mali GPU (Vulkan) was slower than the CPU
   [reported: same note]. We design for the **CPU** (ARMv8.6+ with dotprod/i8mm) and keep GPU
   as an optional backend.
3. **12 GB RAM** leaves ~7-8 GB for the app after Android [reported: AndroidLM ran at 7.9 GB
   with ~1 GB left]. Weights must be mmap-ed (clean, evictable pages) and the working set bounded.
4. **50 GB storage** comfortably holds a full English Wikipedia with FTS5 (~15-20 GB), vectors
   for its most important passages (2-4 GB), a 12-13 GB streamed MoE and two small models.
5. **No network, ever.** No INTERNET permission, no GMS, all assets local.

## 2. Candidate architectures (Phase 2)

### A. "One big streamed MoE" (AndroidLM-style)
Qwen3.6-35B-A3B or Gemma-4-26B-A4B at ~2-3 bit, experts streamed from flash, one model for
planning, drafting and checking; BM25 retrieval.

### B. "Small dense + RAG" (Field Atlas / BOAR-style)
One 2-4B dense model fully in RAM; FTS5 (+ optional vectors) over a curated corpus.

### C. "Tiered evidence engine" (proposed)
Retrieval-first research pipeline with hybrid retrieval and sentence-level evidence
compression, driven by an **adaptive router over up to three GGUF model tiers** in one
llama.cpp runtime:

| Tier | Role | Candidates (see MODELS.md) | Residency |
|---|---|---|---|
| Fast | query analysis, decomposition, per-hop extraction, claim checks, simple answers | Qwen3.5-2B, Gemma-4-E2B | resident, Q4_0 (repacked ARM kernels) |
| Strong | explanation, comparison, synthesis from compressed evidence | Gemma-4-E4B, Qwen3.5-4B | resident or mmap, Q4_0 |
| Deep (optional) | hard multi-hop / reasoning over short notes | Qwen3.6-35B-A3B, Gemma-4-26B-A4B | mmap, experts streamed from flash; dense tensors resident |
| Embed | query embedding | EmbeddingGemma-300M, bge-small / arctic-embed-s | resident |

### D. "Resident mid-size MoE"
A 20-26B MoE (LFM2-24B-A2B, Gemma-4-26B-A4B, gpt-oss-20b) squeezed to ~2 bit so it fits in
~8 GB without streaming; one model for everything.

## 3. Comparison (Phase 3)

Scores are relative (1 = worst, 5 = best) and are **[estimate]** until the benchmark runs on a
Pixel. The latency column is the arithmetic from constraint 1 for a question needing ~1,200
tokens of evidence and a 300-token answer.

| | A: big streamed MoE | B: small dense | C: tiered evidence engine | D: resident mid MoE |
|---|---|---|---|---|
| Expected quality, easy questions | 5 | 3 | 4-5 (retrieval-first, strong tier) | 4 |
| Expected quality, hard synthesis | 4-5 | 2 | 4 (deep tier on compressed notes) | 3-4 (2-bit damage) |
| Peak RAM | ~8 GB | 2-4 GB | 4-8 GB depending on tier in use | 8-9 GB (tight) |
| Storage | ~13 GB model + corpus | ~2-3 GB + corpus | ~17 GB models + corpus | ~9 GB + corpus |
| Speed, easy question | 1.5-3 min [reported] | 10-30 s | **10-30 s** (fast/strong tier, short prompts) | 45-90 s |
| Speed, hard question | 2-5 min [reported] | 30-60 s (but weak) | 1-2.5 min | 1-2 min |
| Implementation difficulty | high (engine fork) | low | **high** (pipeline) / medium (engine: upstream llama.cpp) | medium |
| Offline reliability | high | high | high | high (LMK risk at 9 GB) |
| Research capability (multi-hop, comparison, abstention) | medium (model-named articles) | low | **high** (decomposition, evidence graph, verification) | medium |

## 4. Decision (Phase 4): architecture C

**Why C beats A** on the judge's two complaints. A spends its whole budget on one model that
reads slowly; C spends model time only where it buys quality. An easy question never touches
the MoE and reads ~600 compressed evidence tokens with a resident 2-4B model. A hard question
uses the fast model for the many small steps (decompose, extract per hop, check claims) and
hands the deep model a **short** notes prompt (≤ ~800 tokens) instead of raw sources.

**Why C beats B.** The same small models do better with better evidence. Hybrid retrieval,
reranking, decomposition and verification are where a 4B model catches up with a larger one
on research tasks. The deep tier stays available for questions where reasoning, not
knowledge, is the bottleneck.

**Why not D.** 2-bit quantisation of a 20-26B model at 8-9 GB resident leaves no headroom on
a 12 GB Pixel; the low-memory killer would take the app down under normal Android load. D
survives as a *configuration* of C: the deep tier accepts any GGUF, so a resident mid-size
MoE can be measured with the same benchmark.

**MoE and expert streaming in C.** The deep tier loads with mmap and with weight repacking turned
off, so every tensor stays file-backed. The kernel page cache streams routed experts from flash on
demand and evicts them under pressure. This is the cheapest correct form of expert streaming and
needs no engine fork. Measured: anonymous memory falls from 3.45 GB to 0.22 GB on a 7B-A1B MoE
(docs/MODELS.md). Keeping the dense tensors (attention, DeltaNet, shared experts) in the fast ARM
repack format while experts stream needs a small llama.cpp patch (ROADMAP). A dedicated expert cache with router-driven prefetch
(BigMoeOnEdge-style) is the next step **only if** the on-device benchmark shows page-cache
streaming is the bottleneck. See ROADMAP.md.

## 5. The research pipeline

```
question
  │
  ├─► 0. Normalise, follow-up rewrite (uses previous turn), language check
  │
  ├─► 1. Analyse (rule features + fast-model JSON, grammar-constrained, ~120 output tokens)
  │       type ∈ {lookup, explanation, comparison, multihop, synthesis, numeric, ambiguous}
  │       entities, sub-questions (with dependencies), search queries, needs_calc
  │
  ├─► 2. Route (ModelRouter): pick tiers and budgets from type, #hops, evidence strength,
  │       available models, user mode (Quick / Auto / Deep) and a latency budget
  │
  ├─► 3. For each hop (dependency order; hop i may use the answer of hop j<i):
  │       a. Hybrid retrieval  — BM25 (FTS5) on chunks
  │                             — title/alias exact + prefix match (entity linking)
  │                             — dense ANN (binary IVF → int8 rescoring)
  │                             — reciprocal-rank fusion
  │       b. Rerank            — features: fused rank, term coverage, proximity, entity
  │                              match, lead-section prior, doc prior; optional cross-encoder
  │       c. Evidence select   — split top passages into sentences; score by query coverage,
  │                              embedding similarity, entity/number overlap; keep the best
  │                              ~N sentences + their neighbours under a token budget
  │       d. Hop answer        — fast model extracts a short answer with [n] citations,
  │                              or NOT_FOUND
  │       └─► evidence graph: nodes = sources/sentences, hop answers; edges = hop deps
  │
  ├─► 4. Sufficiency gate — if no hop found support and top scores are weak:
  │       answer "not in the offline corpus" (+ what was searched), never invent
  │
  ├─► 5. Synthesis — strong or deep tier writes the answer from numbered evidence + hop notes
  │       (system prompt KV-cached; evidence ≤ budget; citations [n] mandatory)
  │
  ├─► 6. Verify — split into claims; each claim → support score against its cited sentences
  │       (content-word overlap, numbers/dates/names must match); weak claims batched into one
  │       fast-model SUPPORTED/UNSUPPORTED check; unsupported claims flagged in the UI and,
  │       if central, trigger one repair pass
  │
  └─► 7. Answer + citations (clickable → source passage) + research trace + metrics
```

Every step emits a trace event (what ran, on which model, tokens in/out, ms) that the UI shows
under "Research steps" and the benchmark writes to JSONL.

### Why this reads less
* Sentence-level evidence: a 180-word passage usually holds one or two relevant sentences.
  Keeping those plus one neighbour cuts evidence tokens by ~3-5x [estimate].
* Per-hop extraction uses the fast model on tiny prompts; the expensive model sees the hop
  answers plus the best sentences, not every retrieved passage.
* The system prompt and instructions are prefilled once per model and kept in the KV cache
  (prefix reuse), so only the new part of each prompt is read.

## 6. Knowledge pack

```
<pack dir>/
  manifest.json      id, version, licence, counts, embedding model id + dim, checksums
  corpus.sqlite      docs, chunks (text), chunks_fts (FTS5, porter+unicode61), titles/aliases,
                     doc priors (length, inlinks or pageviews when available)
  vectors.kvec       IVF centroids (float32), per-list binary codes (1 bit/dim), int8 vectors
                     for rescoring, chunk-id map. Memory-mapped; optional.
```
Built on a PC by `tools/` (reproducible, deterministic ordering). Multiple packs can be
installed; retrieval fans out and fuses across them.

## 7. Memory and storage budget [estimate, to be measured]

| Component | RAM (peak) | Disk |
|---|---|---|
| App + UI + SQLite page cache | 0.3-0.4 GB | APK ~60-90 MB |
| Fast tier (2B, Q4_0) + 4k KV | ~1.5 GB | ~1.3 GB |
| Strong tier (4B, Q4_0) + 8k KV | ~3.0 GB (evicted while Deep runs) | ~2.5 GB |
| Deep tier (35B-A3B ~2.5-bit) | dense ~1.5-2 GB resident + expert page cache ≤ 4 GB | ~12-13 GB |
| Embedding model | 0.1-0.35 GB | 0.1-0.35 GB |
| Vector index (mmap, touched lists) | 0.2-0.6 GB | 2-4 GB |
| Wikipedia text + FTS5 | page cache only | 15-20 GB |
| **Total** | **≤ ~7.5 GB target** | **~35-42 GB** |

## 8. Offline guarantees

* `AndroidManifest.xml` declares **no INTERNET permission**; `scripts/verify_offline.sh`
  fails the build if any merged manifest (including libraries) adds it.
* No Google Play Services dependency; no Firebase; no analytics.
* The app reads models and packs from local storage only; there is no downloader.
* The UI shows an "Offline" badge derived from facts: the INTERNET permission is not granted
  to the package, and the Airplane-mode flag.

## 9. Repository structure (Phase 5)

```
engine/        Pure Kotlin/JVM research engine (no Android deps): retrieval, vector index,
               router, planner, pipeline, verifier, prompts, benchmark runner. Unit-tested on JVM.
native/        C++ JNI bridge over upstream llama.cpp (pinned), built for Android arm64 (NDK)
               and for the host (desktop CLI and tests).
app/           Android app (Kotlin, Jetpack Compose), Android storage/SQLite adapters.
bench/         Desktop CLI: runs the identical engine + native bridge on a PC for development
               and reproducible benchmarking.
tools/         Python: knowledge-pack builder (Wikipedia → SQLite/FTS5 → embeddings →
               vectors.kvec), benchmark scorer.
benchmark/     Question set (JSONL) and methodology.
scripts/       Build helpers, offline audit, adb helpers for on-device benchmark runs.
docs/          This document, models, packs, install, offline testing, benchmark, roadmap.
```
