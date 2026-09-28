# Competitive analysis (Phase 1)

Snapshot taken 2026-09-28 from each project's `main` branch (shallow clones), their READMEs,
notes and evaluation files, plus the judge's public feedback. Numbers quoted below are the
projects' own measurements, not ours.

## The bounty, read closely

"Build the Best Offline AI Research App for Android" (poidh bounty 31): a research assistant
that runs completely offline on real (GrapheneOS-compatible, i.e. Pixel) hardware, within
12 GB RAM and 50 GB of storage, and that is "more than 50% as useful as internet search +
frontier AI". The judge's published feedback after trying the first entries:

- the apps are much better than his own attempt two months earlier, **but still much slower
  and weaker at difficult questions than models that run on a laptop**;
- they were weakest at specialised travel queries; his test was
  *"Tell me the best vegan restaurants in [city I am currently in]"*, which none handled well.

So the scoring axes that matter are: (1) quality on hard questions, (2) wall-clock speed,
(3) honesty (no invented facts), (4) breadth of the offline knowledge (incl. practical/travel).

## AndroidLM (Phineas1500/AndroidLM)

| | |
|---|---|
| Stack | Kotlin/Compose app forked from BigMoeOnEdge's demo; BigMoeOnEdge engine (llama.cpp fork) with their own patches (persistent thread pool, repacked dense weights, ik_llama.cpp ARM kernels for IQ2/IQ3 experts) |
| Model | Qwen3.6-35B-A3B, Unsloth UD-Q2_K_XL (12.3 GB; experts IQ2_XS/IQ3_XXS), routed experts streamed from flash into a 5 GB in-RAM expert cache |
| Corpus | FineWiki (Aug 2025) English Wikipedia in one 21 GB SQLite: full text for the 2M most-read articles, leads for the rest, FTS5/BM25, redirects, pageviews. Wikivoyage (0.3 GB). Places DB (2.9 GB, 21M places from Overture + OSM + GeoNames) |
| Retrieval | Lexical only (BM25). The model names the Wikipedia articles it wants; titles resolved through redirects. No dense vectors, no reranker |
| Pipeline | Router: little-read subjects go retrieval-first, everything else answer-first (draft from model memory) followed by a "source check" that reads ~1,100 tokens of sources and cites/corrects |
| Offline | No INTERNET permission, no GMS. Location permission (for "near me") |
| Measured (Pixel 8 Pro) | 4-6 tok/s decode; **24-30 tok/s prompt reading**; model load ~28 s; answer-first: first words ~18 s, full answer + check ~3 min; retrieval-first: ~1.6 min median; RAM ~7.9 GB |
| Eval | 72 questions graded 0-10 by Claude (grader knew which system was which): 7.0 vs 2.6 for Qwen3-1.7B from memory |

**Strengths.** The most serious entry. Big model with real knowledge; carefully measured on a
real Pixel; honest notes; the widest corpus (Wikipedia + Wikivoyage + places); already fixed
the judge's vegan-restaurant test; engine work on thread pinning and priority.

**Weaknesses / openings.**
1. **Latency.** 1.5-5 minutes per question. Their own notes attribute it to prompt reading:
   24-30 tok/s means every 1,000 tokens of sources costs 35-40 s, and the source check alone is
   70-85 s of a 100-140 s answer. The MoE-at-2-bit engine cannot read long evidence quickly on
   a phone CPU (prefill touches all experts; IQ-grid kernels are the slowest on ARM).
2. **Retrieval is lexical and model-driven.** The model has to *name* the right articles. This
   works for entity questions, fails for conceptual/paraphrased questions, and for multi-hop
   questions whose bridge entity the model does not know. No dense retrieval, no reranking.
3. **Answer-first drafting** invents details that the check must then catch; the check itself
   is sometimes wrong ("corrected" correct answers in their red-team notes).
4. **One model for everything.** No cheap path for easy questions: a trivial lookup pays the
   same model-load and prefill costs as a hard synthesis question.
5. **Engine is a fork of a fork.** Tied to BigMoeOnEdge + patches; hard to swap models.

## BOAR (rferrari/boar-app)

| | |
|---|---|
| Stack | React Native / Expo, `llama.rn` (llama.cpp binding), TypeScript pipeline |
| Models | Default Qwen2.5-1.5B; tiers include LFM2.5-8B-A1B (MoE, 1.5B active), Phi-3.5-mini, Qwen2.5-7B; downloaded on first run |
| Corpus | Small bundled JSON corpora; optional Wikipedia Vital Articles pack; user document import |
| Retrieval | SQLite FTS5 + on-device embeddings (hybrid) |
| Routing | "Adaptive routing": classify query, pick model profile, selective verification |
| Offline | **Requests INTERNET** (first-run download and Hugging Face model search); offline afterwards |
| Measured | Dimensity 8300: LFM2.5-8B-A1B 14.8 tok/s median, Qwen2.5-1.5B 11.4 tok/s, Qwen2.5-7B 2.7 tok/s |

**Strengths.** The routing idea; the device evaluation runner; a real MoE measurement
(LFM2.5-8B-A1B is as fast as a dense 1.5B while carrying 8B of parameters); personal
document import.

**Weaknesses.** Small models and a small corpus: little knowledge, weak on hard questions. The
network permission weakens the offline claim. React Native adds overhead and a JS runtime.
README says compile + install on real hardware was "not yet verified" for parts of it.

## Field Atlas (0x94t3z/fieldatlas)

| | |
|---|---|
| Stack | Native Kotlin/Compose, pinned llama.cpp submodule, SQLite bundled (FTS5), CI release audit |
| Model | Qwen3 1.7B GGUF default (Qwen3.5 / MiniCPM5 packs buildable) |
| Corpus | Small curated "Reference" pack bundled in the APK; optional Wikipedia-mini (vital articles) pack |
| Retrieval | LLM query expansion, FTS5 + int8 vectors (BGE-small query encoder), keyword fallback |
| Offline | No INTERNET permission; offline acceptance tests; verify_offline.sh audits the APK |
| Measured | Physical Infinix X6840 (demo video); no systematic latency/quality table |

**Strengths.** Clean native engineering: pack format with manifests, SHA-256 and storage
budgets, compliance tests, reproducible pack builds, CI audit.

**Weaknesses.** Small model and small corpus, so weak on hard/obscure questions. Falls back to
an uncited model answer when retrieval finds nothing (hallucination risk). No routing, no
multi-hop, no verification step.

## Others seen

`oluwasemilorebello7-design/offline-research`: Qwen3-4B / Qwen3-30B-A3B tiers, FTS5 over
Wikipedia 2023-11, LLM query planning; no measurements published yet.

## Where a new entry can win

| Gap in the field | Our answer |
|---|---|
| Minutes per answer (prompt reading at 25-30 tok/s) | Minimise tokens the model must read: sentence-level evidence compression (~600-900 tokens instead of 1,100-4,000), cached system-prompt KV, short structured intermediate steps, and a fast model that is fully resident with repacked Q4_0 kernels |
| Same cost for easy and hard questions | Adaptive routing: a fast 2B-class model for lookups and planning; a 4B-class model for explanation/comparison; the streamed MoE only for hard synthesis over compressed notes |
| Lexical-only retrieval that needs the model to know article names | Hybrid retrieval: BM25 + title/alias index + dense vectors (binary-quantised IVF, int8 rescoring) + reciprocal-rank fusion + feature reranking |
| Multi-hop questions whose bridge entity the model does not know | Explicit decomposition into dependent sub-questions; each hop is retrieved and answered from evidence before the next hop is formed |
| Drafts from memory that must be corrected afterwards | Retrieval-first always; the model writes only from numbered evidence; claim-level verification (lexical + numeric checks, then a cheap LLM check only for weak claims) |
| Silent fallbacks to model memory | "Insufficient offline evidence" is a first-class outcome; a memory-only answer is available only on request and is labelled unverified |
| Hard to swap models | Any GGUF that upstream llama.cpp supports; roles (fast/strong/deep/embed) are assigned per file; the in-app benchmark measures pp/tg on the actual phone |
