#!/usr/bin/env bash
# Build the Kestrel JNI bridge (and llama.cpp) for the host machine, for the desktop CLI
# (`bench/`) and JVM tests. Output: build/native-host/libkestrel_jni.{so,dylib} (kestrel_jni.dll on Windows).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/native-host"
LLAMA_DIR="${LLAMA_CPP_DIR:-$ROOT/native/llama.cpp}"
if [ ! -f "$LLAMA_DIR/CMakeLists.txt" ]; then
  git -C "$ROOT" submodule update --init --depth 1 native/llama.cpp
fi
GEN=()
command -v ninja >/dev/null && GEN=(-G Ninja)
cmake -S "$ROOT/native" -B "$BUILD" "${GEN[@]}" -DCMAKE_BUILD_TYPE=Release -DLLAMA_CPP_DIR="$LLAMA_DIR" "$@"
cmake --build "$BUILD" --target kestrel_jni -j "${JOBS:-$(nproc 2>/dev/null || echo 4)}"
ls -la "$BUILD"/libkestrel_jni.* "$BUILD"/kestrel_jni.* 2>/dev/null || true
