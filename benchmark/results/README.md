# Benchmark results

Each file is one run: JSONL, one `BenchResult` per question (see docs/BENCHMARK.md).
Summaries come from `python tools/score_bench.py <file> [<file2>]`.

**No phone results yet.** Everything below comes from the development container (4-core x86 VM,
AVX-512, no GPU) with the development pack: the first 3 GB of a February 2020 English Wikipedia
text dump, 418k articles (docs/KNOWLEDGE_PACKS.md). It lacks some articles the questions need.
These runs test the pipeline, not phone speed or full-corpus quality.

| File | Pipeline | Models | Notes |
|---|---|---|---|
| `desktop-dev-sample1.jsonl` | v1 (commit ef30508-era engine), auto mode | Qwen3.5-2B Q4_K_M (fast) + Qwen3.5-4B Q4_K_M (strong) | 1 question per category (14); CPU shared with a concurrent embedding job, so latencies are inflated about 2-3x |
| `desktop-dev-sample1-memory-2b.jsonl` | none: model memory only (`--baseline memory`) | Qwen3.5-2B Q4_K_M | same 14 questions; the "small model alone" baseline; partly contended |
| `desktop-dev-sample1-v2.jsonl` | v2 (routing, evidence breadth, scout for explanations, arithmetic check), auto mode | same as v1 | same 14 questions, CPU not shared |

## Sample 1: 14 questions, v1 pipeline vs. the small model alone

| Metric (automatic, lenient) | Kestrel v1 | Qwen3.5-2B from memory |
|---|---|---|
| Answer match | 83% | 83% |
| Key-fact recall | 0.59 | 0.88 |
| Abstention correct | 93% | 93% |
| Claims grounded in cited sources | 77% | – (no sources) |

What these numbers hide (read the answers in the files):

* The memory-only answers contain confident errors that the lenient scorer does not catch. It
  says **Phobos** is the smaller moon of Mars (it is Deimos; the scorer matched "Deimos" because
  the answer mentions it). It says **Ulysses S. Grant** was president when the Eiffel Tower was
  completed (it was Benjamin Harrison). It says Canberra was chosen "in 1908 through a
  referendum" (there was no referendum).
* Kestrel's misses come from missing evidence: the dev subset has no "Benjamin Harrison"
  article, and the v1 evidence selection was too narrow for synthesis questions. It declined or
  stayed vague instead of inventing.

Conclusions acted on:
1. Keyword scoring cannot measure correctness. The blind grading sheet
   (`tools/score_bench.py --grading-sheet`) is the measure for the submission.
2. These 14 questions are mostly things a 2B model has memorised. A `long_tail` category was added
   (165 questions total), and the demo questions (docs/DEMO_PLAN.md) favour multi-hop, false
   premises and non-existent entities.
3. Evidence breadth and routing were fixed for synthesis and "describe/outline" questions (v2).

## Sample 1 again, v2 pipeline, CPU not shared (`desktop-dev-sample1-v2.jsonl`)

| Metric | v1 (contended) | v2 |
|---|---|---|
| Answer match | 83% | 83% |
| Key-fact recall | 0.59 | 0.57 |
| Abstention correct | 93% | 93% |
| Claims grounded in cited sources | 77% | 84% |
| Median latency (4-core x86 VM) | 73.9 s | 37.2 s |
| Median time to first answer token | 17.2 s | 8.2 s |
| Lookups (factual, obscure) end to end | 18-23 s | 6-8 s |

Qualitatively, v2 answers the ice question from the density sentences (0.917 g/cm³, 9% expansion)
instead of ice-shelf trivia. The Roman Empire synthesis draws on 8 sources instead of 5. The
multi-hop miss (Benjamin Harrison) is a gap in the dev corpus. Phone numbers will differ: prefill
on a Tensor CPU is expected to be slower than on this AVX-512 VM, and decode similar [estimate].
