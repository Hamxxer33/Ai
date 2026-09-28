# Building from source

## Android APK

Requirements: JDK 17+, Android SDK (platform 36, build-tools 36), NDK 29.0.14206865 and CMake
3.22.1 (from the SDK manager), and git with submodules.

```sh
git clone --recurse-submodules https://github.com/Hamxxer33/Ai.git kestrel && cd kestrel
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease
scripts/verify_offline.sh app/build/outputs/apk/release/app-release.apk
```

The native part is upstream llama.cpp at the commit in `native/LLAMA_CPP_COMMIT`, with no patches.
It is built as a shared library plus one CPU backend per ARM feature level (armv8.0 up to
armv9.2 + SME), and the best one for the phone's CPU is picked at runtime. Only `arm64-v8a` is
built.

Other NDK or CMake versions: `-Pkestrel.ndkVersion=... -Pkestrel.cmakeVersion=...`.

Release signing: set `KESTREL_KEYSTORE`, `KESTREL_KEYSTORE_PASSWORD`, `KESTREL_KEY_ALIAS` and
`KESTREL_KEY_PASSWORD` (or create `keystore.properties`). Without them the release APK is signed
with your debug key.

## Desktop CLI (same engine and native bridge, for development and benchmarks)

```sh
scripts/build_native_host.sh                       # llama.cpp + JNI bridge for this machine
./gradlew -Pkestrel.jvmOnly=true :engine:test :bench:installDist
bench/build/install/bench/bin/bench ask --pack packs/enwiki --fast models/Qwen3.5-2B-Q4_K_M.gguf \
    --strong models/Qwen3.5-4B-Q4_K_M.gguf --embed models/nomic-embed-text-v1.5.f16.gguf "Why does ice float?"
```

`-Pkestrel.jvmOnly=true` skips the Android module, so the engine builds and tests with only a JDK.

CLI commands:

| Command | Purpose |
|---|---|
| `ask` | one question through the full pipeline, streaming, with sources, verification and timings |
| `retrieve` | retrieval and evidence selection only, with per-signal scores (for tuning) |
| `bench` | run `benchmark/questions.jsonl` (or a subset: `--ids`, `--category`, `--limit`), write JSONL; `--baseline memory` answers from model memory only |
| `speed` | prompt and generation speed of one model |
| `info` | llama.cpp system info (CPU features, backends) |

## Repository layout

| Path | What |
|---|---|
| `engine/` | pure Kotlin research engine (retrieval, vectors, router, planner, verifier, benchmark runner) |
| `native/` | JNI bridge (`src/kestrel_jni.cpp`) and the pinned llama.cpp submodule |
| `app/` | Android app (Compose UI, bundled SQLite, model runtime, storage) |
| `bench/` | desktop CLI |
| `tools/` | pack builder, embedding and vector index, model fetcher, benchmark scorer |
| `benchmark/` | question set and its generator |
| `scripts/` | native host build, offline audit, adb helpers |
| `docs/` | architecture, models, packs, install, offline testing, benchmark, demo, roadmap |
