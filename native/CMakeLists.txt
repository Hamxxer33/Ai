cmake_minimum_required(VERSION 3.22)
project(kestrel_native LANGUAGES C CXX)

# Kestrel JNI bridge over upstream llama.cpp.
#   Android: built by the app module's externalNativeBuild (NDK, arm64-v8a).
#   Host:    scripts/build_native_host.sh (for the desktop CLI and JVM tests).
#
# llama.cpp source: the pinned git submodule at native/llama.cpp (see native/LLAMA_CPP_COMMIT),
# or -DLLAMA_CPP_DIR=/path/to/llama.cpp.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT LLAMA_CPP_DIR)
    set(LLAMA_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
endif()
if(NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_CPP_DIR}. Run: git submodule update --init native/llama.cpp")
endif()

# ---- llama.cpp options: CPU only, no network, no tools/tests/server.
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # One CPU backend library per ARM feature level (dotprod, i8mm, SVE, SME ...), chosen at
    # runtime for the phone's CPU. KleidiAI micro-kernels for Q4_0/Q8_0 matmuls on Arm.
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    if(NOT DEFINED GGML_NATIVE)
        set(GGML_NATIVE ON CACHE BOOL "" FORCE)
    endif()
endif()

if(ANDROID)
    # Not EXCLUDE_FROM_ALL: the per-CPU ggml backend variants are loaded at runtime (dlopen), so
    # nothing links against them and they must still be built and packaged.
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)
else()
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp EXCLUDE_FROM_ALL)
endif()

add_library(kestrel_jni SHARED src/kestrel_jni.cpp)

if(ANDROID)
    target_link_libraries(kestrel_jni PRIVATE llama llama-common android log)
    target_compile_definitions(kestrel_jni PRIVATE GGML_BACKEND_DL)
    # 16 KB page-size compatibility (Android 15+ devices).
    target_link_options(kestrel_jni PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(kestrel_jni PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(kestrel_jni PRIVATE llama llama-common)
endif()

target_include_directories(kestrel_jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include)
