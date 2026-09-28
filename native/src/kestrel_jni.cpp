// Kestrel native bridge: a thin JNI layer over upstream llama.cpp.
//
// Design notes
// * All strings cross the JNI boundary as UTF-8 byte arrays. JNI's "modified UTF-8" mangles
//   4-byte sequences (emoji, some CJK), and the tokenizer must see real UTF-8.
// * One Context owns a llama_context plus the token history currently in its KV cache, so a
//   new prompt that shares a prefix with the previous one (same system prompt, same evidence)
//   only prefills the new suffix.
// * Deep-tier models can be loaded in "stream experts" mode: routed-expert tensors are pinned to
//   the plain CPU buffer type, which keeps them file-backed (mmap) so the kernel pages them in
//   from flash on demand, while every other tensor may go to the CPU repack buffer (resident,
//   fast ARM GEMM kernels).
// * No network code. llama.cpp is built with LLAMA_CURL=OFF / LLAMA_OPENSSL=OFF.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "llama.h"

#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <cerrno>

#ifdef __ANDROID__
#include <android/log.h>
#define KLOG(...) __android_log_print(ANDROID_LOG_INFO, "kestrel-native", __VA_ARGS__)
#else
#include <cstdio>
#define KLOG(...) do { fprintf(stderr, "[kestrel-native] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

using clk = std::chrono::steady_clock;

double ms_since(clk::time_point t0) {
    return std::chrono::duration<double, std::milli>(clk::now() - t0).count();
}

// ---------------------------------------------------------------- JNI string helpers

std::string from_bytes(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string s(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

void throw_java(JNIEnv * env, const std::string & msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

// Length of the longest prefix of s that ends on a complete UTF-8 sequence.
size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    if (n == 0) return 0;
    // walk back over at most 3 continuation bytes to find a lead byte
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {  // lead byte or ASCII
            size_t need = 1;
            if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            return (n - (i - 1) >= need) ? n : i - 1;
        }
        --i;
        ++back;
    }
    return n;
}

// Minimal JSON escaping for the result objects we build here.
std::string jstr(const std::string & s) {
    std::string o = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
                else o += static_cast<char>(c);
        }
    }
    return o + "\"";
}

// ---------------------------------------------------------------- tiny JSON reader
// Only what the Kotlin side sends: a flat object of numbers, booleans, strings and string arrays,
// or an array of {"role","content"} objects. Not a general JSON parser.

struct JsonCursor {
    const std::string & s; size_t i = 0;
    explicit JsonCursor(const std::string & src) : s(src) {}
    void ws() { while (i < s.size() && isspace(static_cast<unsigned char>(s[i]))) ++i; }
    bool eat(char c) { ws(); if (i < s.size() && s[i] == c) { ++i; return true; } return false; }
    std::string str() {
        ws(); std::string o; if (i >= s.size() || s[i] != '"') return o; ++i;
        while (i < s.size() && s[i] != '"') {
            char c = s[i++];
            if (c == '\\' && i < s.size()) {
                char e = s[i++];
                switch (e) {
                    case 'n': o += '\n'; break; case 't': o += '\t'; break; case 'r': o += '\r'; break;
                    case 'b': o += '\b'; break; case 'f': o += '\f'; break;
                    case 'u': {
                        unsigned cp = static_cast<unsigned>(std::stoul(s.substr(i, 4), nullptr, 16)); i += 4;
                        if (cp >= 0xD800 && cp <= 0xDBFF && i + 6 <= s.size() && s[i] == '\\' && s[i + 1] == 'u') {
                            unsigned lo = static_cast<unsigned>(std::stoul(s.substr(i + 2, 4), nullptr, 16));
                            if (lo >= 0xDC00 && lo <= 0xDFFF) { cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00); i += 6; }
                        }
                        if (cp < 0x80) o += static_cast<char>(cp);
                        else if (cp < 0x800) { o += static_cast<char>(0xC0 | (cp >> 6)); o += static_cast<char>(0x80 | (cp & 0x3F)); }
                        else if (cp < 0x10000) { o += static_cast<char>(0xE0 | (cp >> 12)); o += static_cast<char>(0x80 | ((cp >> 6) & 0x3F)); o += static_cast<char>(0x80 | (cp & 0x3F)); }
                        else { o += static_cast<char>(0xF0 | (cp >> 18)); o += static_cast<char>(0x80 | ((cp >> 12) & 0x3F)); o += static_cast<char>(0x80 | ((cp >> 6) & 0x3F)); o += static_cast<char>(0x80 | (cp & 0x3F)); }
                        break;
                    }
                    default: o += e;
                }
            } else o += c;
        }
        ++i; return o;
    }
    std::string scalar() {  // number / true / false / null as raw text
        ws(); size_t b = i;
        while (i < s.size() && s[i] != ',' && s[i] != '}' && s[i] != ']' && !isspace(static_cast<unsigned char>(s[i]))) ++i;
        return s.substr(b, i - b);
    }
    void skip_value() {
        ws(); if (i >= s.size()) return;
        if (s[i] == '"') { str(); return; }
        if (s[i] == '{' || s[i] == '[') {
            char open = s[i], close = open == '{' ? '}' : ']'; int depth = 0;
            do {
                if (s[i] == '"') { str(); continue; }
                if (s[i] == open) depth++; else if (s[i] == close) depth--;
                ++i;
            } while (i < s.size() && depth > 0);
            return;
        }
        scalar();
    }
};

struct GenParams {
    int max_tokens = 512;
    float temperature = 0.0f;
    float top_p = 0.95f;
    int top_k = 40;
    float min_p = 0.05f;
    float repeat_penalty = 1.0f;
    int repeat_last_n = 64;
    uint32_t seed = 42;
    std::string grammar;
    std::vector<std::string> stop;
    bool reuse_prefix = true;
    // Formatted prompt prefix (usually the system turn) whose model state is snapshotted so later
    // prompts starting with it skip its prefill, even on recurrent/hybrid or SWA models whose
    // cache cannot drop a suffix.
    std::string snapshot_prefix;
};

GenParams parse_gen_params(const std::string & json) {
    GenParams p;
    JsonCursor c(json);
    if (!c.eat('{')) return p;
    while (!c.eat('}')) {
        std::string key = c.str();
        c.eat(':');
        c.ws();
        if (key == "grammar") p.grammar = c.str();
        else if (key == "stop") {
            c.eat('[');
            while (!c.eat(']')) { p.stop.push_back(c.str()); c.eat(','); }
        } else if (key == "max_tokens") p.max_tokens = std::stoi(c.scalar());
        else if (key == "temperature") p.temperature = std::stof(c.scalar());
        else if (key == "top_p") p.top_p = std::stof(c.scalar());
        else if (key == "top_k") p.top_k = std::stoi(c.scalar());
        else if (key == "min_p") p.min_p = std::stof(c.scalar());
        else if (key == "repeat_penalty") p.repeat_penalty = std::stof(c.scalar());
        else if (key == "repeat_last_n") p.repeat_last_n = std::stoi(c.scalar());
        else if (key == "seed") p.seed = static_cast<uint32_t>(std::stoul(c.scalar()));
        else if (key == "reuse_prefix") p.reuse_prefix = c.scalar() == "true";
        else if (key == "snapshot_prefix") p.snapshot_prefix = c.str();
        else c.skip_value();
        c.eat(',');
    }
    return p;
}

std::vector<common_chat_msg> parse_messages(const std::string & json) {
    std::vector<common_chat_msg> msgs;
    JsonCursor c(json);
    if (!c.eat('[')) return msgs;
    while (!c.eat(']')) {
        if (!c.eat('{')) break;
        common_chat_msg m;
        while (!c.eat('}')) {
            std::string key = c.str();
            c.eat(':');
            if (key == "role") m.role = c.str();
            else if (key == "content") m.content = c.str();
            else c.skip_value();
            c.eat(',');
        }
        msgs.push_back(std::move(m));
        c.eat(',');
    }
    return msgs;
}

// ---------------------------------------------------------------- handles

struct Model {
    llama_model * model = nullptr;
    common_chat_templates_ptr templates;
    std::string path;
    double load_ms = 0;
};

struct Snapshot {
    std::vector<llama_token> tokens;
    std::vector<uint8_t> state;
    uint64_t last_used = 0;
};

struct Context {
    ggml_threadpool * tp = nullptr;
    void (*tp_free)(ggml_threadpool *) = nullptr;
    std::vector<Snapshot> snaps;  // LRU, at most kMaxSnaps
    uint64_t tick = 0;
    Model * owner = nullptr;
    llama_context * ctx = nullptr;
    std::vector<llama_token> cached;  // tokens currently in the KV cache for seq 0
    std::atomic<bool> cancel{false};
    std::mutex busy;
    int n_batch = 512;
    bool embeddings = false;
    int n_seq_max = 1;
};

constexpr size_t kMaxSnaps = 4;

std::vector<llama_token> tokenize_prompt(const llama_vocab * vocab, const std::string & text) {
    std::vector<llama_token> t = common_tokenize(vocab, text, true, true);
    const llama_token bos = llama_vocab_bos(vocab);
    if (t.size() >= 2 && t[0] == bos && t[1] == bos) t.erase(t.begin());
    return t;
}

bool is_prefix(const std::vector<llama_token> & p, const std::vector<llama_token> & of) {
    return p.size() <= of.size() && std::equal(p.begin(), p.end(), of.begin());
}

bool abort_cb(void * data) {
    return static_cast<Context *>(data)->cancel.load();
}

std::atomic<bool> g_backend_ready{false};
std::mutex g_init_mutex;

}  // namespace

// ================================================================ JNI exports
// Kotlin: io.kestrel.engine.llm.LlamaNative

#define JNI_FN(name) Java_io_kestrel_engine_llm_LlamaNative_##name

extern "C" {

JNIEXPORT void JNICALL JNI_FN(backendInit)(JNIEnv * env, jclass, jbyteArray jLibDir, jboolean verbose) {
    std::lock_guard<std::mutex> lk(g_init_mutex);
    if (g_backend_ready.load()) return;
    if (!verbose) {
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) KLOG("%s", text);
        }, nullptr);
    }
    const std::string dir = from_bytes(env, jLibDir);
#ifdef GGML_BACKEND_DL
    if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str());
    else ggml_backend_load_all();
#else
    (void) dir;
#endif
    llama_backend_init();
    g_backend_ready.store(true);
}

JNIEXPORT jbyteArray JNICALL JNI_FN(systemInfo)(JNIEnv * env, jclass) {
    std::string info = llama_print_system_info();
    std::ostringstream devs;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        devs << (i ? ", " : "") << ggml_backend_dev_name(d) << " (" << ggml_backend_dev_description(d) << ")";
    }
    return to_bytes(env, info + " | devices: " + devs.str());
}

// flags: bit0 = use mmap, bit1 = mlock, bit2 = stream experts (keep *_exps file-backed), bit3 = disable repack
JNIEXPORT jlong JNICALL JNI_FN(modelLoad)(JNIEnv * env, jclass, jbyteArray jPath, jint flags, jint nGpuLayers) {
    const std::string path = from_bytes(env, jPath);
    const bool use_mmap = flags & 1, use_mlock = flags & 2, stream_experts = flags & 4, no_repack = flags & 8;

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    mp.load_mode = use_mmap ? (use_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP)
                            : (use_mlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE);
    mp.use_extra_bufts = !no_repack;

    // Must stay alive for the duration of the load call.
    llama_model_tensor_buft_override overrides[2] = {};
    if (stream_experts) {
        overrides[0].pattern = "\\.ffn_(up|down|gate|gate_up)_exps";
        overrides[0].buft = ggml_backend_cpu_buffer_type();
        overrides[1].pattern = nullptr;
        overrides[1].buft = nullptr;
        mp.tensor_buft_overrides = overrides;
    }

    const auto t0 = clk::now();
    llama_model * m = llama_model_load_from_file(path.c_str(), mp);
    if (!m) {
        throw_java(env, "failed to load model: " + path);
        return 0;
    }
    auto * h = new Model();
    h->model = m;
    h->path = path;
    h->load_ms = ms_since(t0);
    try {
        h->templates = common_chat_templates_init(m, "");
    } catch (const std::exception & e) {
        KLOG("chat template init failed: %s", e.what());
    }
    KLOG("loaded %s in %.0f ms", path.c_str(), h->load_ms);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL JNI_FN(modelFree)(JNIEnv *, jclass, jlong handle) {
    auto * h = reinterpret_cast<Model *>(handle);
    if (!h) return;
    h->templates.reset();
    llama_model_free(h->model);
    delete h;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(modelInfo)(JNIEnv * env, jclass, jlong handle) {
    auto * h = reinterpret_cast<Model *>(handle);
    char desc[256] = {0};
    llama_model_desc(h->model, desc, sizeof desc);
    char arch[64] = {0};
    llama_model_meta_val_str(h->model, "general.architecture", arch, sizeof arch);
    char name[256] = {0};
    llama_model_meta_val_str(h->model, "general.name", name, sizeof name);
    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    std::ostringstream o;
    o << "{\"desc\":" << jstr(desc)
      << ",\"arch\":" << jstr(arch)
      << ",\"name\":" << jstr(name)
      << ",\"n_params\":" << llama_model_n_params(h->model)
      << ",\"size_bytes\":" << llama_model_size(h->model)
      << ",\"n_ctx_train\":" << llama_model_n_ctx_train(h->model)
      << ",\"n_embd\":" << llama_model_n_embd(h->model)
      << ",\"n_layer\":" << llama_model_n_layer(h->model)
      << ",\"n_vocab\":" << llama_vocab_n_tokens(vocab)
      << ",\"has_encoder\":" << (llama_model_has_encoder(h->model) ? "true" : "false")
      << ",\"is_recurrent\":" << (llama_model_is_recurrent(h->model) || llama_model_is_hybrid(h->model) ? "true" : "false")
      << ",\"has_chat_template\":" << (h->templates ? "true" : "false")
      << ",\"supports_thinking\":" << (h->templates && common_chat_templates_support_enable_thinking(h->templates.get()) ? "true" : "false")
      << ",\"load_ms\":" << h->load_ms
      << "}";
    return to_bytes(env, o.str());
}

JNIEXPORT jbyteArray JNICALL JNI_FN(applyChatTemplate)(JNIEnv * env, jclass, jlong handle, jbyteArray jMessages,
                                                       jboolean addGenerationPrompt, jboolean enableThinking) {
    auto * h = reinterpret_cast<Model *>(handle);
    if (!h->templates) {
        throw_java(env, "model has no chat template");
        return nullptr;
    }
    common_chat_templates_inputs in;
    in.messages = parse_messages(from_bytes(env, jMessages));
    in.add_generation_prompt = addGenerationPrompt;
    in.use_jinja = true;
    in.enable_thinking = enableThinking;
    try {
        auto params = common_chat_templates_apply(h->templates.get(), in);
        return to_bytes(env, params.prompt);
    } catch (const std::exception & e) {
        throw_java(env, std::string("chat template failed: ") + e.what());
        return nullptr;
    }
}

JNIEXPORT jintArray JNICALL JNI_FN(tokenize)(JNIEnv * env, jclass, jlong handle, jbyteArray jText, jboolean addSpecial,
                                             jboolean parseSpecial) {
    auto * h = reinterpret_cast<Model *>(handle);
    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    auto toks = common_tokenize(vocab, from_bytes(env, jText), addSpecial, parseSpecial);
    jintArray out = env->NewIntArray(static_cast<jsize>(toks.size()));
    if (!toks.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(toks.size()), toks.data());
    return out;
}

// pooling: -1 = model default, 0 none, 1 mean, 2 cls, 3 last, 4 rank. flashAttn: -1 auto, 0 off, 1 on.
JNIEXPORT jlong JNICALL JNI_FN(contextCreate)(JNIEnv * env, jclass, jlong modelHandle, jint nCtx, jint nBatch, jint nUbatch,
                                              jint nThreads, jint nThreadsBatch, jboolean embeddings, jint pooling,
                                              jint flashAttn, jint nSeqMax) {
    auto * m = reinterpret_cast<Model *>(modelHandle);
    auto * c = new Context();
    c->owner = m;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = static_cast<uint32_t>(nBatch);
    cp.n_ubatch = static_cast<uint32_t>(nUbatch);
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreadsBatch;
    cp.n_seq_max = static_cast<uint32_t>(std::max(1, static_cast<int>(nSeqMax)));
    cp.embeddings = embeddings;
    cp.no_perf = false;
    if (pooling >= 0) cp.pooling_type = static_cast<enum llama_pooling_type>(pooling);
    cp.flash_attn_type = static_cast<llama_flash_attn_type>(flashAttn);
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = c;
    c->ctx = llama_init_from_model(m->model, cp);
    if (!c->ctx) {
        delete c;
        throw_java(env, "failed to create context");
        return 0;
    }
    c->n_batch = nBatch;
    c->embeddings = embeddings;
    c->n_seq_max = cp.n_seq_max;
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL JNI_FN(contextFree)(JNIEnv *, jclass, jlong handle) {
    auto * c = reinterpret_cast<Context *>(handle);
    if (!c) return;
    c->cancel.store(true);
    std::lock_guard<std::mutex> lk(c->busy);
    llama_free(c->ctx);
    if (c->tp && c->tp_free) c->tp_free(c->tp);
    delete c;
}

// Sets the nice value of the calling thread. Threads it creates afterwards (ggml's compute
// threads) inherit it. Returns 0 or errno.
JNIEXPORT jint JNICALL JNI_FN(setThreadNice)(JNIEnv *, jclass, jint nice) {
    const pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
    return setpriority(PRIO_PROCESS, static_cast<id_t>(tid), nice) == 0 ? 0 : errno;
}

// Replaces the context's compute threads with a persistent pool whose threads may only run on the
// given CPUs (the phone's big cores). strict = one thread per CPU. Must be called from the thread
// that will run generation (the pool threads inherit its nice value).
JNIEXPORT jboolean JNICALL JNI_FN(contextSetThreadpool)(JNIEnv * env, jclass, jlong handle, jintArray jCpus, jboolean strict) {
    auto * c = reinterpret_cast<Context *>(handle);
    std::lock_guard<std::mutex> lk(c->busy);
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (!dev) return JNI_FALSE;
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    auto * new_fn = reinterpret_cast<decltype(ggml_threadpool_new) *>(ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new"));
    auto * free_fn = reinterpret_cast<decltype(ggml_threadpool_free) *>(ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free"));
    if (!new_fn || !free_fn) return JNI_FALSE;
    ggml_threadpool_params p;
    ggml_threadpool_params_init(&p, llama_n_threads(c->ctx));
    const jsize n = jCpus ? env->GetArrayLength(jCpus) : 0;
    std::vector<jint> cpus(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(jCpus, 0, n, cpus.data());
    for (jint cpu : cpus) if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) p.cpumask[cpu] = true;
    p.strict_cpu = strict;
    p.prio = GGML_SCHED_PRIO_NORMAL;  // keep the inherited nice value; SCHED_FIFO is not allowed for apps
    ggml_threadpool * tp = new_fn(&p);
    if (!tp) return JNI_FALSE;
    llama_detach_threadpool(c->ctx);
    if (c->tp && c->tp_free) c->tp_free(c->tp);
    llama_attach_threadpool(c->ctx, tp, nullptr);
    c->tp = tp;
    c->tp_free = free_fn;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_FN(cancel)(JNIEnv *, jclass, jlong handle) {
    auto * c = reinterpret_cast<Context *>(handle);
    if (c) c->cancel.store(true);
}

JNIEXPORT void JNICALL JNI_FN(kvClear)(JNIEnv *, jclass, jlong handle) {
    auto * c = reinterpret_cast<Context *>(handle);
    std::lock_guard<std::mutex> lk(c->busy);
    llama_memory_clear(llama_get_memory(c->ctx), true);
    c->cached.clear();
}

// Generate a completion for a fully formatted prompt (chat template already applied).
// callback: object with `boolean onBytes(byte[])`; returning false stops generation.
// Returns a JSON object with the text and timings.
JNIEXPORT jbyteArray JNICALL JNI_FN(generate)(JNIEnv * env, jclass, jlong handle, jbyteArray jPrompt,
                                              jbyteArray jParams, jobject callback) {
    auto * c = reinterpret_cast<Context *>(handle);
    std::lock_guard<std::mutex> lk(c->busy);
    c->cancel.store(false);

    const std::string prompt = from_bytes(env, jPrompt);
    const GenParams gp = parse_gen_params(from_bytes(env, jParams));
    llama_model * model = c->owner->model;
    const llama_vocab * vocab = llama_model_get_vocab(model);
    llama_memory_t mem = llama_get_memory(c->ctx);

    jmethodID onBytes = nullptr;
    if (callback) {
        jclass cbCls = env->GetObjectClass(callback);
        onBytes = env->GetMethodID(cbCls, "onBytes", "([B)Z");
    }

    const auto t_start = clk::now();
    std::vector<llama_token> toks = tokenize_prompt(vocab, prompt);
    const int n_ctx = static_cast<int>(llama_n_ctx(c->ctx));
    if (static_cast<int>(toks.size()) + gp.max_tokens > n_ctx) {
        // Keep the head (system prompt) and the tail (question); drop from the middle.
        const int budget = std::max(64, n_ctx - gp.max_tokens - 8);
        if (static_cast<int>(toks.size()) > budget) {
            const int head = budget / 3;
            std::vector<llama_token> t2(toks.begin(), toks.begin() + head);
            t2.insert(t2.end(), toks.end() - (budget - head), toks.end());
            toks.swap(t2);
        }
    }

    // ---- prefix reuse
    size_t n_keep = 0;
    if (gp.reuse_prefix) {
        while (n_keep < c->cached.size() && n_keep < toks.size() && c->cached[n_keep] == toks[n_keep]) ++n_keep;
        if (n_keep == toks.size() && n_keep > 0) --n_keep;  // must decode at least one token for logits
        if (n_keep > 0 && !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(n_keep), -1)) {
            n_keep = 0;  // recurrent / hybrid memory cannot drop a suffix
        }
    }
    if (n_keep == 0) llama_memory_clear(mem, true);
    c->cached.resize(n_keep);

    // ---- snapshot restore: a saved state whose tokens prefix this prompt beats a shorter reuse
    size_t n_restored = 0;
    {
        Snapshot * best = nullptr;
        for (auto & sn : c->snaps) {
            if (sn.tokens.size() > n_keep && sn.tokens.size() < toks.size() && is_prefix(sn.tokens, toks) &&
                (!best || sn.tokens.size() > best->tokens.size())) best = &sn;
        }
        if (best) {
            llama_memory_clear(mem, true);
            if (llama_state_seq_set_data(c->ctx, best->state.data(), best->state.size(), 0) > 0) {
                c->cached = best->tokens;
                n_keep = best->tokens.size();
                n_restored = n_keep;
                best->last_used = ++c->tick;
            } else {
                llama_memory_clear(mem, true);
                c->cached.clear();
                n_keep = 0;
            }
        }
    }

    // ---- where to take a new snapshot (end of the given prefix), if it is not cached yet
    size_t n_snap = 0;
    if (!gp.snapshot_prefix.empty()) {
        std::vector<llama_token> pt = tokenize_prompt(vocab, gp.snapshot_prefix);
        const bool have = std::any_of(c->snaps.begin(), c->snaps.end(), [&](const Snapshot & sn) { return sn.tokens == pt; });
        if (!have && pt.size() > n_keep && pt.size() < toks.size() && is_prefix(pt, toks)) n_snap = pt.size();
    }

    // ---- prefill
    const auto t_prefill = clk::now();
    llama_batch batch = llama_batch_init(c->n_batch, 0, 1);
    bool failed = false;
    auto prefill = [&](size_t from, size_t to) {
        for (size_t i = from; i < to && !failed; i += static_cast<size_t>(c->n_batch)) {
            const size_t end = std::min(to, i + static_cast<size_t>(c->n_batch));
            common_batch_clear(batch);
            for (size_t j = i; j < end; ++j) {
                common_batch_add(batch, toks[j], static_cast<llama_pos>(j), {0}, j == toks.size() - 1);
            }
            if (llama_decode(c->ctx, batch) != 0) failed = true;
            else c->cached.insert(c->cached.end(), toks.begin() + static_cast<long>(i), toks.begin() + static_cast<long>(end));
            if (c->cancel.load()) { failed = true; break; }
        }
    };
    if (n_snap > 0) {
        prefill(n_keep, n_snap);
        if (!failed) {
            Snapshot sn;
            sn.tokens.assign(toks.begin(), toks.begin() + static_cast<long>(n_snap));
            sn.state.resize(llama_state_seq_get_size(c->ctx, 0));
            if (!sn.state.empty() && llama_state_seq_get_data(c->ctx, sn.state.data(), sn.state.size(), 0) > 0) {
                sn.last_used = ++c->tick;
                if (c->snaps.size() >= kMaxSnaps) {
                    auto lru = std::min_element(c->snaps.begin(), c->snaps.end(),
                                                [](const Snapshot & a, const Snapshot & b) { return a.last_used < b.last_used; });
                    c->snaps.erase(lru);
                }
                c->snaps.push_back(std::move(sn));
            }
        }
        prefill(n_snap, toks.size());
    } else {
        prefill(n_keep, toks.size());
    }
    const double prefill_ms = ms_since(t_prefill);
    const int n_prefilled = static_cast<int>(toks.size() - n_keep);

    // ---- samplers
    // The grammar is kept out of the chain: each token is first sampled normally and only checked
    // against the grammar; the full-vocabulary grammar pass runs only when that token is rejected
    // (the same strategy as llama.cpp's common sampler). With 150k-250k token vocabularies this is
    // several times faster than masking the whole vocabulary at every step.
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler * grammar = nullptr;
    if (!gp.grammar.empty()) {
        grammar = llama_sampler_init_grammar(vocab, gp.grammar.c_str(), "root");
        if (!grammar) KLOG("grammar failed to parse; generating unconstrained");
    }
    if (gp.repeat_penalty > 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), gp.repeat_last_n, gp.repeat_penalty, 0.0f, 0.0f));
    }
    if (gp.temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(gp.top_k));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(gp.top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(gp.min_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(gp.temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(gp.seed));
    }
    const int n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> cand(static_cast<size_t>(n_vocab));
    auto fill = [&](llama_token_data_array & arr) {
        const float * logits = llama_get_logits_ith(c->ctx, -1);
        for (int t = 0; t < n_vocab; ++t) cand[static_cast<size_t>(t)] = llama_token_data{t, logits[t], 0.0f};
        arr = llama_token_data_array{cand.data(), cand.size(), -1, false};
    };
    auto sample = [&]() -> llama_token {
        llama_token_data_array arr;
        fill(arr);
        llama_sampler_apply(chain, &arr);
        llama_token tok = arr.data[arr.selected].id;
        if (grammar) {
            llama_token_data single{tok, 1.0f, 0.0f};
            llama_token_data_array one{&single, 1, -1, false};
            llama_sampler_apply(grammar, &one);
            if (std::isinf(one.data[0].logit) && one.data[0].logit < 0) {
                fill(arr);
                llama_sampler_apply(grammar, &arr);
                llama_sampler_apply(chain, &arr);
                tok = arr.data[arr.selected].id;
            }
            llama_sampler_accept(grammar, tok);
        }
        llama_sampler_accept(chain, tok);
        return tok;
    };

    // ---- decode loop
    std::string text, pending;  // pending = bytes not yet sent (incomplete UTF-8 or possible stop prefix)
    int n_gen = 0;
    double ttft_ms = -1;
    const auto t_decode = clk::now();
    std::string stop_reason = failed ? "error" : "length";
    bool user_stopped = false;
    llama_pos pos = static_cast<llama_pos>(toks.size());

    auto flush = [&](bool final) {
        if (!callback || !onBytes || pending.empty()) return;
        size_t n = final ? pending.size() : utf8_complete_prefix(pending);
        if (!final) {
            // hold back anything that could be the start of a stop string
            size_t hold = 0;
            for (const auto & s : gp.stop) {
                for (size_t k = std::min(s.size() - 1, n); k > 0; --k) {
                    if (pending.compare(n - k, k, s, 0, k) == 0) { hold = std::max(hold, k); break; }
                }
            }
            n -= std::min(n, hold);
            n = utf8_complete_prefix(pending.substr(0, n));
        }
        if (n == 0) return;
        jbyteArray arr = to_bytes(env, pending.substr(0, n));
        const jboolean keep = env->CallBooleanMethod(callback, onBytes, arr);
        env->DeleteLocalRef(arr);
        pending.erase(0, n);
        if (!keep) user_stopped = true;
    };

    while (!failed && n_gen < gp.max_tokens) {
        if (c->cancel.load() || user_stopped) { stop_reason = "cancelled"; break; }
        const llama_token tok = sample();
        if (llama_vocab_is_eog(vocab, tok)) { stop_reason = "eos"; break; }
        if (ttft_ms < 0) ttft_ms = ms_since(t_start);
        const std::string piece = common_token_to_piece(c->ctx, tok, false);
        text += piece;
        pending += piece;
        ++n_gen;

        bool hit_stop = false;
        for (const auto & s : gp.stop) {
            if (s.empty()) continue;
            const size_t found = text.find(s, text.size() > piece.size() + s.size() ? text.size() - piece.size() - s.size() : 0);
            if (found != std::string::npos) {
                const size_t cut = text.size() - found;
                text.erase(found);
                pending.erase(pending.size() >= cut ? pending.size() - cut : 0);
                hit_stop = true;
                break;
            }
        }
        if (hit_stop) { stop_reason = "stop"; break; }
        flush(false);

        common_batch_clear(batch);
        common_batch_add(batch, tok, pos++, {0}, true);
        if (llama_decode(c->ctx, batch) != 0) { stop_reason = c->cancel.load() ? "cancelled" : "error"; break; }
        c->cached.push_back(tok);
    }
    flush(true);
    const double decode_ms = ms_since(t_decode);
    llama_sampler_free(chain);
    if (grammar) llama_sampler_free(grammar);
    llama_batch_free(batch);

    std::ostringstream o;
    o << "{\"text\":" << jstr(text)
      << ",\"prompt_tokens\":" << toks.size()
      << ",\"prefilled_tokens\":" << n_prefilled
      << ",\"reused_tokens\":" << n_keep
      << ",\"restored_tokens\":" << n_restored
      << ",\"generated_tokens\":" << n_gen
      << ",\"prefill_ms\":" << prefill_ms
      << ",\"decode_ms\":" << decode_ms
      << ",\"ttft_ms\":" << (ttft_ms < 0 ? ms_since(t_start) : ttft_ms)
      << ",\"total_ms\":" << ms_since(t_start)
      << ",\"stop_reason\":" << jstr(stop_reason)
      << "}";
    return to_bytes(env, o.str());
}

// Embed a batch of texts. Returns float[texts.length][n_embd] (L2-normalised if normalize).
JNIEXPORT jobjectArray JNICALL JNI_FN(embed)(JNIEnv * env, jclass, jlong handle, jobjectArray jTexts, jboolean normalize) {
    auto * c = reinterpret_cast<Context *>(handle);
    std::lock_guard<std::mutex> lk(c->busy);
    c->cancel.store(false);
    llama_model * model = c->owner->model;
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int n_embd = llama_model_n_embd(model);
    const jsize n = env->GetArrayLength(jTexts);
    const bool enc_only = llama_model_has_encoder(model) && !llama_model_has_decoder(model);
    const int n_ctx = static_cast<int>(llama_n_ctx(c->ctx));
    const int cap = std::min(c->n_batch, n_ctx);

    std::vector<std::vector<llama_token>> all(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto * b = static_cast<jbyteArray>(env->GetObjectArrayElement(jTexts, i));
        auto t = common_tokenize(vocab, from_bytes(env, b), true, true);
        env->DeleteLocalRef(b);
        if (static_cast<int>(t.size()) > cap) t.resize(static_cast<size_t>(cap));
        all[static_cast<size_t>(i)] = std::move(t);
    }

    jclass floatArrCls = env->FindClass("[F");
    jobjectArray out = env->NewObjectArray(n, floatArrCls, nullptr);
    llama_batch batch = llama_batch_init(cap, 0, c->n_seq_max);
    std::vector<float> buf(static_cast<size_t>(n_embd));

    size_t i = 0;
    while (i < all.size()) {
        common_batch_clear(batch);
        llama_memory_clear(llama_get_memory(c->ctx), true);
        std::vector<size_t> members;
        int used = 0;
        while (i < all.size() && static_cast<int>(members.size()) < c->n_seq_max &&
               (members.empty() || used + static_cast<int>(all[i].size()) <= cap)) {
            const auto seq = static_cast<llama_seq_id>(members.size());
            for (size_t p = 0; p < all[i].size(); ++p) {
                common_batch_add(batch, all[i][p], static_cast<llama_pos>(p), {seq}, true);
            }
            used += static_cast<int>(all[i].size());
            members.push_back(i++);
        }
        const int rc = enc_only ? llama_encode(c->ctx, batch) : llama_decode(c->ctx, batch);
        if (rc != 0) {
            llama_batch_free(batch);
            throw_java(env, "embedding decode failed");
            return nullptr;
        }
        for (size_t k = 0; k < members.size(); ++k) {
            const float * e = llama_get_embeddings_seq(c->ctx, static_cast<llama_seq_id>(k));
            if (!e) e = llama_get_embeddings_ith(c->ctx, -1);
            if (!e) { std::fill(buf.begin(), buf.end(), 0.0f); }
            else std::copy(e, e + n_embd, buf.begin());
            if (normalize) {
                double ss = 0;
                for (float v : buf) ss += static_cast<double>(v) * v;
                const float inv = ss > 0 ? static_cast<float>(1.0 / std::sqrt(ss)) : 0.0f;
                for (float & v : buf) v *= inv;
            }
            jfloatArray fa = env->NewFloatArray(n_embd);
            env->SetFloatArrayRegion(fa, 0, n_embd, buf.data());
            env->SetObjectArrayElement(out, static_cast<jsize>(members[k]), fa);
            env->DeleteLocalRef(fa);
        }
        if (c->cancel.load()) break;
    }
    llama_batch_free(batch);
    return out;
}

}  // extern "C"
