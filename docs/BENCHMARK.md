# Benchmark methodology

## Question set

`benchmark/questions.jsonl` has 154 questions, 11 in each of 14 categories, generated from
`benchmark/make_questions.py` (edit there, then regenerate):

| Category | What it tests |
|---|---|
| factual | single-fact lookup |
| obscure | long-tail facts a small model does not remember |
| multi_hop | a chain of two facts ("the capital of the country where Angkor Wat is") |
| comparison | two or more things compared point by point |
| explanation | mechanisms and causes ("why does ice float") |
| historical_analysis | causes and consequences of historical events |
| scientific | scientific concepts |
| technical | computing and engineering |
| numerical | answers that need arithmetic on retrieved numbers |
| synthesis | overviews that combine several sources |
| contradictory | contested or commonly misstated facts; the answer should present the nuance |
| long_context | many details spread through long articles |
| hallucination_probe | false premises and non-existent entities; the correct behaviour is to correct or refuse |
| multi_source | facts from several articles combined |

Each question has a short gold answer (optional), accepted alternatives, key facts, an
`expect_abstain` flag for questions about non-existent things, and notes.

## What is recorded per question

`BenchResult` (engine/src/main/kotlin/io/kestrel/engine/bench/Benchmark.kt), one JSON line:

* question, standalone rewrite, detected type, route, mode, models used, answer model
* answer text, retrieved sources (pack, article, section, chunk id, evidence text), linked
  articles, per-hop sub-questions and answers
* automatic scores: `answer_match`, `key_fact_recall`, `abstain_correct`
* grounding: checkable claims, supported claims, unsupported claims, invalid citations, and whether
  a model check ran (`verification_ran_llm`)
* timing: total latency, retrieval time, time to first answer token, prefill and decode speed of
  the answer call, and for every model call its prompt, prefilled, reused and generated tokens,
  prefill time, decode time and TTFT
* memory: process RSS and peak RSS (VmHWM)
* the full research-step trace

## Running it

Desktop (identical engine and native code, for reproducibility and development):

```sh
bench/build/install/bench/bin/bench bench --pack packs/enwiki \
    --fast models/Qwen3.5-2B-Q4_K_M.gguf --strong models/Qwen3.5-4B-Q4_K_M.gguf --embed models/nomic-embed-text-v1.5.f16.gguf \
    --questions benchmark/questions.jsonl --out benchmark/results/desktop-auto.jsonl
# baseline: the fast model alone, from memory (the "1B-class model" comparison)
bench/build/install/bench/bin/bench bench --pack packs/enwiki --fast models/Qwen3.5-2B-Q4_K_M.gguf \
    --baseline memory --questions benchmark/questions.jsonl --out benchmark/results/desktop-memory-2b.jsonl
python tools/score_bench.py benchmark/results/desktop-auto.jsonl benchmark/results/desktop-memory-2b.jsonl
```

Phone: **Benchmark → Run all** (or *2 per category* for a quick run). The screen stays on while it
runs. Then `scripts/pull_bench.sh` pulls the JSONL and prints the summary. The runtime benchmark
(**Run runtime benchmark**) records load time, prefill and decode speed and memory per model.

## Scoring

1. **Automatic** (`tools/score_bench.py`): per-category answer match, key-fact recall, abstention
   accuracy, grounded-claim rate, median latency, median TTFT, decode speed, peak RSS. Matching is
   lenient: all content words and numbers of a target must appear. It under-counts paraphrases and
   over-counts right words in wrong sentences, so it is a screening measure.
2. **Human grading**: `tools/score_bench.py results.jsonl --grading-sheet grading.csv` writes one row
   per answer with the gold answer, key facts and sources. The grader fills in a 0-10 grade,
   factual correctness, citation correctness and hallucination (y/n). For comparisons between
   systems, shuffle and hide the system names before grading.
3. **Citation correctness**: every claim's cited passages are stored with the answer, so each can
   be checked by hand. The built-in verifier's grounded-claim rate is reported separately and is
   not a substitute.

## Reporting rules

* Every number in a README or submission names the device (model, SoC, RAM, Android version), the
  models and quantisations, the pack, the mode and the result file it came from.
* Desktop numbers are labelled desktop. Phone numbers come only from the phone.
* Estimates are labelled as estimates.
* Result files go in `benchmark/results/` and are committed with the change that produced them.

## Current results

See `benchmark/results/README.md`. The phone runs are pending (they need the phone). The desktop
development runs use the 3 GB dev subset of Wikipedia (docs/KNOWLEDGE_PACKS.md), which lacks some
articles the questions need. They measure the pipeline, not full-corpus coverage.
