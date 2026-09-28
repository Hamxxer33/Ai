# Roadmap

Ordered by expected effect on the judge's two complaints (speed, hard questions) and the known
test (travel). Status: ✅ done, 🔶 in progress, ⬜ planned.

## Done in v0.1

* ✅ Native JNI bridge over upstream llama.cpp: streaming, cancel, chat templates, embeddings,
  lazy grammar sampling, **state snapshots of system prompts** (prefix caching that also works on
  hybrid DeltaNet and SWA models), expert-streaming load mode for MoE files
* ✅ Hybrid retrieval: BM25 (AND of rare terms, OR, entity phrase ∧ rare terms), title/alias entity
  linking, IVF binary + int8 dense index, RRF, feature reranking (coverage, bigram proximity,
  entity, lead, prior)
* ✅ Sentence-level evidence compression with local IDF and an optional embedding reranker
* ✅ Router (Quick / Research / Deep), planner with forced multi-hop grammar, per-hop extraction,
  article-suggestion ("scout") step, sufficiency gate with refusal, claim verification (lexical +
  numeric + model check)
* ✅ Android app: Compose UI, citations, research trace, grounding, Library, Benchmark, Settings,
  Offline badge, no INTERNET permission
* ✅ Benchmark: 165 questions in 15 categories, desktop and on-device runners, scorer, grading sheet
* ✅ CI: tests, APK build, offline audit, release on tag

## Next

1. 🔶 **Measure on the phone** (the user's device): runtime benchmark per tier, then the full
   benchmark. Pick the default strong model (Gemma-4-E4B vs Qwen3.5-4B) and the quantisation
   (Q4_0 with ARM repack vs Q4_K_M) from the numbers.
2. ⬜ **Full English Wikipedia pack** (2023-11 parquet), with vectors for the lead passages of
   the top 2M articles; publish the build logs and checksums.
3. ⬜ **Travel/places pack**: Wikivoyage (CC BY-SA) as a jsonl pack, plus a places table built from
   OpenStreetMap (ODbL) with diet tags (vegan/vegetarian), cuisine and coordinates, and a "near X"
   query path. The judge's test is "best vegan restaurants in [city]".
4. ⬜ **Deep tier on device**: Qwen3.6-35B-A3B at ~2.5 bit with experts streamed (`streamExperts`,
   verified to keep weights file-backed). Measure page-cache hit rate and decode speed. Then:
   (a) a llama.cpp patch that repacks only non-expert tensors, keeping ARM GEMM kernels for the
   dense part; (b) if page-cache streaming is too slow, an explicit expert LRU cache with
   router-driven prefetch.
5. ⬜ **Speculative decoding** with the MTP heads shipped for Qwen3.5/3.6 and Gemma 4, or with the
   fast tier as draft model for the strong tier (same tokenizer family).
6. 🔶 **Thread placement**: implemented (ggml threadpool restricted to the big cores, inference
   threads at nice -10, both Settings toggles). AndroidLM measured 10-60% from similar changes on
   Tensor G3. Needs an A/B measurement on the phone.
7. ⬜ **Foreground service** so long answers and benchmark runs survive the app going to the
   background.
8. ⬜ **Calculator tool** for numerical questions: the model writes an expression and the engine
   evaluates it exactly.
9. ⬜ **Cross-encoder reranker** (small BERT-class GGUF) for the top 30 passages, if the phone
   budget allows.
10. ⬜ **Follow-up conversations** in the UI (the engine already rewrites follow-ups) and export of an
    answer with its sources.
