# Models

All inference runs through upstream **llama.cpp** (GGUF), pinned in `native/LLAMA_CPP_COMMIT`.
One runtime serves every tier, so any model that upstream llama.cpp supports can be used.

## Candidates (2026-09 landscape)

Facts are from model cards and release posts; "phone" columns are **[estimate]** until the
in-app benchmark (Settings → Benchmark → Runtime) has been run on a Pixel. The docs record the
measured values in `benchmark/results/` once available.

| Model | Total / active params | Arch notes | Context | Licence | GGUF on-disk (typical quant) | Role fit |
|---|---|---|---|---|---|---|
| Qwen3.6-35B-A3B | 35B / 3B | MoE (256 experts, 8 routed + shared), hybrid Gated DeltaNet + gated attention (3:1) | 262K | Apache-2.0 | ~12.3 GB UD-Q2_K_XL; ~13.5 GB Q2_K | Deep (streamed) |
| Qwen3.5-35B-A3B | 35B / 3B | same family, Feb 2026 | 262K | Apache-2.0 | similar | Deep (alt.) |
| Gemma-4-26B-A4B | 25.2B / 3.8B | MoE, standard attention (sliding + global) | 256K | Apache-2.0 | ~17 GB Q4_K_M, ~9-11 GB at 2-3 bit | Deep (streamed) |
| gpt-oss-20b | 21B / 3.6B | MoE, MXFP4 native | 128K | Apache-2.0 | ~12.1 GB | Deep (reasoning-heavy, knowledge-light) |
| LFM2-24B-A2B | 24B / 2.3B | MoE on LFM2 hybrid conv/attention | 32K | LFM Open License v1.0 (revenue-capped) | ~14 GB Q4_K_M | Deep (alt.) |
| LFM2.5-8B-A1B | 8.3B / ~1.5B | MoE, hybrid conv | 32K | LFM Open License v1.0 | ~5 GB Q4_K_M | Fast/strong (MoE that fits in RAM) |
| Granite-4.0-H-Tiny | 7B / 1B | MoE, hybrid Mamba-2 | 128K | Apache-2.0 | ~4.2 GB Q4_K_M | Fast (weaker) |
| Gemma-4-E4B | ~8B raw / ~4B effective | per-layer embeddings (PLE, mmap-friendly), sliding+global attention | 128K | Apache-2.0 | ~4-5 GB Q4_K_M | Strong |
| Gemma-4-E2B | ~5B raw / ~2B effective | as above | 128K | Apache-2.0 | ~2.5-3 GB Q4_K_M | Fast |
| Qwen3.5-4B | 4B dense | hybrid Gated DeltaNet | 262K | Apache-2.0 | ~2.7 GB Q4_K_M | Strong |
| Qwen3.5-2B | 2B dense | hybrid Gated DeltaNet | 262K | Apache-2.0 | ~1.3 GB Q4_K_M | Fast |
| Qwen3.5-9B | 9B dense | hybrid Gated DeltaNet | 262K | Apache-2.0 | ~5.7 GB Q4_K_M | too slow for decode on Tensor CPUs [estimate] |
| Phi-4-mini (3.8B) | 3.8B dense | GQA attention | 128K | MIT | ~2.5 GB | Strong (alt.) |
| SmolLM3-3B | 3B dense | GQA attention | 64K | Apache-2.0 | ~1.9 GB | Fast (alt.) |

Embedding and reranking:

| Model | Params | Dim | Licence | Notes |
|---|---|---|---|---|
| nomic-embed-text-v1.5 | 137M | 768 (Matryoshka → 256) | Apache-2.0 | needs `search_query:` / `search_document:` prefixes; good speed/quality for bulk corpus embedding |
| EmbeddingGemma-300M | 308M | 768 (MRL 512/256/128) | Gemma Terms of Use | strongest small multilingual option; slower to embed a whole corpus |
| Qwen3-Embedding-0.6B | 0.6B | 1024 (MRL) | Apache-2.0 | high quality, too slow for a full-Wikipedia build on a laptop CPU |
| bge-small-en-v1.5 / arctic-embed-s | 33M | 384 | MIT / Apache-2.0 | 5-10x faster corpus embedding; lower quality |
| Qwen3-Reranker-0.6B | 0.6B | – | Apache-2.0 | too slow per query on a phone CPU for 50 candidates [estimate]; optional |

## How the decision is made

We do not pick a model from desktop leaderboards. The selection is measured, in this order:

1. **Runtime fitness on the phone** (in-app Runtime benchmark): prefill tok/s at 512 tokens,
   decode tok/s, load time, resident memory, for each candidate quant.
2. **Pipeline quality** (benchmark/, 150 questions): same retrieval, same prompts, each tier
   candidate swapped in; key-fact recall, citation precision, abstention correctness.
3. **Licence**: weights must be redistributable (Apache-2.0/MIT preferred) because the install
   script and release notes point to exact files and SHA-256s.

## Current default configuration

Chosen for the first on-device measurements. Each is replaceable from Settings → Models.

| Role | Default | Why |
|---|---|---|
| Fast | Qwen3.5-2B (Q4_0 or Q4_K_M) | Apache-2.0; strong instruction following and JSON output at 2B; DeltaNet keeps long prompts cheap in memory |
| Strong | Gemma-4-E4B (Q4_0) | Apache-2.0; standard attention (expected to read prompts faster on CPU than DeltaNet models, to be measured); strong writing and knowledge for size |
| Deep | Qwen3.6-35B-A3B (~2.5-bit, K-quant experts preferred over IQ-grid experts for ARM speed) | best knowledge/reasoning per active parameter that fits the storage budget; Apache-2.0 |
| Embed | nomic-embed-text-v1.5 (Q8_0), 256-d Matryoshka | fast enough to embed a large corpus on a laptop; Apache-2.0 |

Exact filenames, sources and SHA-256 are recorded in `models/models.lock.json` when the files
are fetched by `tools/fetch_models.py`.

## Expert streaming: what the load mode does (measured)

`LoadOptions.streamExperts` (the deep tier) loads the GGUF memory-mapped and turns off llama.cpp's
weight repacking for that model. Every weight then stays file-backed. The kernel pages routed
experts in from flash on demand and can drop them again under memory pressure, instead of the
low-memory killer ending the app.

Why repacking has to be off: upstream llama.cpp copies every tensor it can repack into anonymous
memory, even when a tensor override points the experts at the plain CPU buffer type (the loader
then considers the repack buffer types again). Measured with the desktop CLI
(`bench speed --stream-experts`) on Granite-4.0-H-Tiny Q4_K_M (7B total / 1B active, 4.2 GB file),
4-core x86 VM:

| Mode | Anonymous RSS | File-backed RSS | Prompt speed (795 tokens) |
|---|---|---|---|
| default (repack on) | 3,455 MB | 752 MB | 37 tok/s |
| stream experts (repack off) | 221 MB | 4,063 MB (evictable) | 55-63 tok/s |

On a 12 GB phone a 12-13 GB MoE file cannot stay fully resident. Only the default mode would
exceed the memory limit; with streaming the file-backed working set competes with the page cache
instead. The cost: dense tensors lose the ARM repack kernels. A small llama.cpp patch that
repacks only non-expert tensors is on the roadmap. Decode and prompt speed of streamed MoE on a
Pixel are **not measured yet**.
