# Bounty requirements: status

Legend: ✅ met and verifiable in this repository · 🔶 implemented, needs the on-phone measurement ·
⬜ open.

| Requirement | Status | Evidence |
|---|---|---|
| Android application (real APK, not a web app) | ✅ | native Kotlin/Compose app `app/`; CI builds `kestrel-*-arm64.apk` (Actions artifact; GitHub Release on tags) |
| Works on real Android hardware | 🔶 | APK builds and passes the offline audit in CI; first on-phone run pending (docs/OFFLINE_TESTING.md) |
| GrapheneOS-compatible hardware | 🔶 | no Google Play Services or Firebase dependency (audited); targets Pixel CPUs (arm64, per-CPU ggml backends up to armv9.2); to be run on a Pixel |
| Max 12 GB RAM | 🔶 | memory-budgeted model loader (deep and strong tiers never resident together; budget = 62% of RAM, max 8 GB); peak RSS recorded per benchmark question |
| Max 50 GB total storage | 🔶 | Library screen shows APK + models + packs against 50 GB; planned configuration ~40 GB (docs/INSTALL.md table) |
| Completely offline after setup | ✅ | no INTERNET permission (`scripts/verify_offline.sh`, CI); no downloader; models and packs copied over USB |
| No API calls / remote inference / web search | ✅ | all inference through bundled llama.cpp; retrieval over local SQLite and a memory-mapped vector file |
| No network requests during normal operation | ✅ | impossible without the INTERNET permission; no networking libraries packaged (audited) |
| No Google Play Services requirement | ✅ | audited in CI |
| Useful research beyond factual recall: explanation, comparison, synthesis, multi-hop, reasoning | 🔶 | router + planner + per-hop extraction + evidence compression + verification; desktop runs show multi-hop and comparison answers with citations; phone benchmark pending |
| Usable response speed on a phone | 🔶 | quick path uses a 2B model on ~500 evidence tokens; prefix snapshots avoid re-reading system prompts; thread pinning to big cores; phone numbers pending |
| Open source on GitHub, all code, reproducible instructions | ✅ | Apache-2.0; docs/BUILDING.md, docs/INSTALL.md; llama.cpp pinned as a submodule |
| Models, datasets, indexes documented | ✅ | docs/MODELS.md (sources, licences), `tools/fetch_models.py` (SHA-256 pinned), docs/KNOWLEDGE_PACKS.md (dataset, schema, build commands) |
| Demonstrate on real hardware | ⬜ | docs/DEMO_PLAN.md and docs/OFFLINE_TESTING.md give the recording protocol |
| Difficult questions a basic 1B model struggles with | 🔶 | benchmark `--baseline memory` runs the small model alone on the same questions for a side-by-side comparison |
| Benchmark 100-200 questions with per-question metrics | ✅ | 154 questions in 14 categories; each result records sources, answer, citation grounding, answer match, key-fact recall, latency, TTFT, tok/s, RSS, models, route, verification, thermal and battery |
