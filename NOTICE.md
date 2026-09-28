# Third-party notices

| Component | Use | Licence |
|---|---|---|
| [llama.cpp / ggml](https://github.com/ggml-org/llama.cpp) (git submodule, `native/llama.cpp`) | on-device inference | MIT |
| AndroidX (Compose, Activity, Lifecycle, SQLite bundled) | app | Apache-2.0 |
| SQLite (bundled by AndroidX) | knowledge pack storage, FTS5 | public domain |
| kotlinx.coroutines, kotlinx.serialization | engine | Apache-2.0 |
| sqlite-jdbc (desktop CLI and tests only) | desktop SQLite driver | Apache-2.0 |

Not distributed with the APK (the user fetches them, with checksums, via `tools/fetch_models.py`):

| Asset | Licence |
|---|---|
| Qwen3.5-2B / Qwen3.5-4B / Qwen3.6-35B-A3B (GGUF) | Apache-2.0 |
| Gemma-4-E4B (GGUF) | Apache-2.0 |
| nomic-embed-text-v1.5 (GGUF) | Apache-2.0 |
| EmbeddingGemma-300M (optional) | Gemma Terms of Use |
| Wikipedia text in knowledge packs | CC BY-SA 4.0; attribution through article titles shown with every source |
