// JNI bridge between LlamaNative.kt and llama.cpp.
//
// One Handle = one loaded model + context. Generation is stateless from Kotlin's point of view:
// every call sends the whole conversation (OpenAI-style JSON). The model's own Jinja chat template
// renders it, and the tokens already in the KV cache are reused, so in practice only the new turn
// is processed.
//
// llama.cpp's common_chat_session does the model-specific work: rendering tools into the prompt,
// constraining tool-call output with a grammar, and parsing tool calls / reasoning out of the text.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <random>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "ggml.h"
#include "llama.h"
#include "sampling.h"

#define TAG "chota_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int BATCH_SIZE = 512;

enum DeltaKind { DELTA_CONTENT = 0, DELTA_REASONING = 1 };

struct Handle {
    llama_model *             model = nullptr;
    llama_context *           ctx   = nullptr;
    common_chat_templates_ptr templates;
    std::vector<llama_token>  cached;  // tokens currently in the KV cache (sequence 0)
    std::atomic<bool>         cancel{false};
    std::mutex                mutex;  // one generation at a time; free waits for it

    ~Handle() {
        templates.reset();
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

struct SamplerDeleter {
    void operator()(common_sampler * s) const { common_sampler_free(s); }
};

void log_callback(ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

std::string to_string(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// JNI's NewStringUTF wants "modified UTF-8" and aborts on 4-byte sequences (emoji),
// so text crosses the boundary as UTF-8 bytes and Kotlin decodes it.
jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

void throw_java(JNIEnv * env, const std::string & message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

std::string json_escape(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 2);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += (char) c;
                }
        }
    }
    return out;
}

// Length of the prefix of `s` that ends on a complete UTF-8 code point.
size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        const auto c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep looking for the lead byte
        size_t len = 1;
        if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        return back >= len ? n : n - back;
    }
    return n;
}

std::string random_call_id() {
    static std::mt19937 rng{std::random_device{}()};
    static const char alphabet[] = "abcdefghijklmnopqrstuvwxyz0123456789";
    std::string id = "call_";
    for (int i = 0; i < 12; i++) id += alphabet[rng() % (sizeof(alphabet) - 1)];
    return id;
}

// Streams text deltas to the Kotlin callback, holding back incomplete UTF-8 sequences.
class DeltaEmitter {
  public:
    DeltaEmitter(JNIEnv * env, jobject callback) : env(env), callback(callback) {
        jclass cls = env->GetObjectClass(callback);
        method     = env->GetMethodID(cls, "onDelta", "(I[B)Z");
    }

    // Returns false if Kotlin asked to stop.
    bool emit(DeltaKind kind, const std::string & delta) {
        std::string & pending = kind == DELTA_CONTENT ? pending_content : pending_reasoning;
        pending += delta;
        const size_t n = utf8_complete_prefix(pending);
        if (n == 0) return true;
        const std::string ready = pending.substr(0, n);
        pending.erase(0, n);

        jbyteArray arr  = to_bytes(env, ready);
        jboolean   keep = env->CallBooleanMethod(callback, method, (jint) kind, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return false;
        }
        return keep == JNI_TRUE;
    }

    bool emit_diff(const common_chat_msg & prev, const common_chat_msg & next) {
        if (prev == next) return true;
        std::vector<common_chat_msg_diff> diffs;
        try {
            diffs = common_chat_msg_diff::compute_diffs(prev, next);
        } catch (const std::exception &) {
            return true;  // the parser re-interpreted earlier text; nothing safe to stream
        }
        for (const auto & d : diffs) {
            if (!d.reasoning_content_delta.empty() && !emit(DELTA_REASONING, d.reasoning_content_delta)) return false;
            if (!d.content_delta.empty() && !emit(DELTA_CONTENT, d.content_delta)) return false;
        }
        return true;
    }

  private:
    JNIEnv *    env;
    jobject     callback;
    jmethodID   method;
    std::string pending_content;
    std::string pending_reasoning;
};

std::vector<llama_token> tokenize_prompt(const llama_vocab * vocab, const common_chat_prompt & prompt) {
    std::vector<llama_token> tokens;
    bool                     first = true;
    for (const auto & part : prompt.parts) {
        // Special tokens are parsed in template text but never in user-provided text,
        // so a user typing "<|im_end|>" can't break out of their message.
        auto t = common_tokenize(vocab, part.text, /*add_special=*/first, /*parse_special=*/!part.is_input);
        tokens.insert(tokens.end(), t.begin(), t.end());
        first = false;
    }
    return tokens;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_chotaai_llm_llamacpp_LlamaNative_backendInit(JNIEnv * env, jobject, jstring native_lib_dir) {
    static std::once_flag once;
    const std::string     dir = to_string(env, native_lib_dir);
    std::call_once(once, [&] {
        llama_log_set(log_callback, nullptr);
        // GGML_BACKEND_DL: the CPU backend ships as several .so variants; load the best one for this CPU.
        ggml_backend_load_all_from_path(dir.c_str());
        llama_backend_init();
        LOGI("llama.cpp backends loaded from %s", dir.c_str());
    });
}

JNIEXPORT jlong JNICALL
Java_com_chotaai_llm_llamacpp_LlamaNative_loadModel(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = to_string(env, jpath);
    auto              h    = std::make_unique<Handle>();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers       = 0;
    h->model                   = llama_model_load_from_file(path.c_str(), mparams);
    if (!h->model) {
        throw_java(env, "Failed to load model: " + path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx                = std::min<uint32_t>(n_ctx, llama_model_n_ctx_train(h->model));
    cparams.n_batch              = BATCH_SIZE;
    cparams.n_ubatch             = BATCH_SIZE;
    cparams.n_threads            = n_threads;
    cparams.n_threads_batch      = n_threads;
    h->ctx                       = llama_init_from_model(h->model, cparams);
    if (!h->ctx) {
        throw_java(env, "Failed to create context (out of memory?)");
        return 0;
    }

    try {
        h->templates = common_chat_templates_init(h->model, "");
    } catch (const std::exception & e) {
        LOGE("Model chat template failed to parse (%s); falling back to chatml", e.what());
        h->templates = common_chat_templates_init(h->model, "chatml");
    }

    LOGI("Loaded %s (n_ctx=%u, threads=%d)", path.c_str(), llama_n_ctx(h->ctx), n_threads);
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT void JNICALL
Java_com_chotaai_llm_llamacpp_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<Handle *>(handle);
    if (!h) return;
    h->cancel = true;
    { std::lock_guard<std::mutex> wait_for_generation(h->mutex); }
    delete h;
}

JNIEXPORT void JNICALL
Java_com_chotaai_llm_llamacpp_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<Handle *>(handle);
    if (h) h->cancel = true;
}

JNIEXPORT jstring JNICALL
Java_com_chotaai_llm_llamacpp_LlamaNative_modelInfo(JNIEnv * env, jobject, jlong handle) {
    auto * h = reinterpret_cast<Handle *>(handle);
    char   desc[256];
    llama_model_desc(h->model, desc, sizeof(desc));

    std::string caps;
    for (const auto & [name, value] : common_chat_templates_get_caps(h->templates.get())) {
        if (!caps.empty()) caps += ",";
        caps += "\"" + json_escape(name) + "\":" + (value ? "true" : "false");
    }
    const std::string json = "{\"description\":\"" + json_escape(desc) + "\"" +
                             ",\"params\":" + std::to_string(llama_model_n_params(h->model)) +
                             ",\"size_bytes\":" + std::to_string(llama_model_size(h->model)) +
                             ",\"n_ctx\":" + std::to_string(llama_n_ctx(h->ctx)) +
                             ",\"n_ctx_train\":" + std::to_string(llama_model_n_ctx_train(h->model)) +
                             ",\"template_caps\":{" + caps + "}}";
    return env->NewStringUTF(json.c_str());
}

// Request:  {"messages":[...], "tools":[...], "temperature", "top_k", "top_p", "seed", "max_tokens", "enable_thinking"}
// Response: {"message":{role, content, reasoning_content, tool_calls}, "stop", "prompt_tokens", "reused_tokens",
//            "generated_tokens", "prefill_ms", "decode_ms"}
JNIEXPORT jbyteArray JNICALL
Java_com_chotaai_llm_llamacpp_LlamaNative_generate(JNIEnv * env, jobject, jlong handle, jstring jrequest, jobject callback) {
    auto *                      h = reinterpret_cast<Handle *>(handle);
    std::lock_guard<std::mutex> lock(h->mutex);
    h->cancel = false;

    try {
        const auto           req   = common_json::parse(to_string(env, jrequest));
        const llama_vocab *  vocab = llama_model_get_vocab(h->model);
        llama_memory_t       mem   = llama_get_memory(h->ctx);
        const int            n_ctx = (int) llama_n_ctx(h->ctx);

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(req.at("messages"));
        if (req.contains("tools") && !req.at("tools").empty()) {
            inputs.tools = common_chat_tools_parse_oaicompat(req.at("tools"));
        }
        inputs.tool_choice           = COMMON_CHAT_TOOL_CHOICE_AUTO;
        inputs.parallel_tool_calls   = true;
        inputs.enable_thinking       = req.value("enable_thinking", false);
        inputs.reasoning_format      = COMMON_REASONING_FORMAT_DEEPSEEK;
        inputs.add_generation_prompt = true;
        inputs.use_jinja             = true;

        common_chat_session session(h->templates.get(), vocab, inputs);
        const auto          prompt = tokenize_prompt(vocab, session.prompt());
        if ((int) prompt.size() >= n_ctx - 8) {
            throw_java(env, "context_overflow: prompt has " + std::to_string(prompt.size()) +
                                " tokens, context is " + std::to_string(n_ctx));
            return nullptr;
        }

        // Reuse the longest prefix already in the KV cache. At least one token is re-evaluated
        // so there are logits to sample from.
        size_t n_reused = 0;
        while (n_reused < h->cached.size() && n_reused < prompt.size() && h->cached[n_reused] == prompt[n_reused]) {
            n_reused++;
        }
        if (n_reused == prompt.size()) n_reused--;
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) n_reused, -1)) {
            // Recurrent / hybrid models (e.g. LFM2) can't drop a partial range; start over.
            llama_memory_clear(mem, false);
            n_reused = 0;
        }
        h->cached.resize(n_reused);

        common_batch batch(h->ctx);
        const auto   t_prefill = ggml_time_us();
        for (size_t i = n_reused; i < prompt.size(); i += BATCH_SIZE) {
            const size_t n = std::min<size_t>(BATCH_SIZE, prompt.size() - i);
            batch.clear();
            for (size_t j = 0; j < n; j++) {
                batch.add(prompt[i + j], (llama_pos) (i + j), 0, i + j == prompt.size() - 1);
            }
            if (llama_process(h->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                llama_memory_clear(mem, false);
                h->cached.clear();
                throw_java(env, "Prompt processing failed");
                return nullptr;
            }
            h->cached.insert(h->cached.end(), prompt.begin() + (long) i, prompt.begin() + (long) (i + n));
            if (h->cancel) break;
        }
        const auto prefill_us = ggml_time_us() - t_prefill;

        common_params_sampling sparams;
        sparams.temp  = (float) req.value("temperature", 0.2);
        sparams.top_k = req.value("top_k", 40);
        sparams.top_p = (float) req.value("top_p", 0.95);
        const int seed = req.value("seed", 0);
        sparams.seed   = seed == 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed;
        session.apply_sampling(sparams);  // tool-call grammar, triggers, preserved tokens
        std::unique_ptr<common_sampler, SamplerDeleter> sampler(common_sampler_init(h->model, sparams));
        if (!sampler) {
            throw_java(env, "Failed to initialize sampler (invalid tool grammar?)");
            return nullptr;
        }

        DeltaEmitter    emitter(env, callback);
        common_chat_msg prev       = session.msg();
        std::string     generated;
        std::string     stop       = "length";
        const int       max_tokens = req.value("max_tokens", 512);
        int             n_gen      = 0;
        const auto      t_decode   = ggml_time_us();

        while (n_gen < max_tokens) {
            if (h->cancel) { stop = "cancelled"; break; }
            if ((int) h->cached.size() >= n_ctx) { stop = "context_full"; break; }

            const llama_token tok = common_sampler_sample(sampler.get(), h->ctx, -1);
            common_sampler_accept(sampler.get(), tok, true);
            if (llama_vocab_is_eog(vocab, tok)) { stop = "stop"; break; }
            n_gen++;

            // Tool-call markers like <tool_call> are special tokens for some models; render them.
            const bool        special = sparams.preserved_tokens.count(tok) > 0;
            const std::string piece   = common_token_to_piece(h->ctx, tok, special);
            generated += piece;

            bool hit_stop = false;
            for (const auto & s : session.additional_stops()) {
                if (!s.empty() && generated.find(s) != std::string::npos) hit_stop = true;
            }
            if (hit_stop) { stop = "stop"; break; }

            common_chat_input chunk;
            chunk.append(piece, tok);
            const common_chat_msg & cur = session.feed(chunk);
            if (!emitter.emit_diff(prev, cur)) { stop = "cancelled"; break; }
            prev = cur;

            batch.clear();
            batch.add(tok, (llama_pos) h->cached.size(), 0, true);
            if (llama_process(h->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { stop = "error"; break; }
            h->cached.push_back(tok);
        }
        const auto decode_us = ggml_time_us() - t_decode;

        common_chat_msg final_msg = prev;
        try {
            final_msg = session.finish();
            emitter.emit_diff(prev, final_msg);
        } catch (const std::exception & e) {
            LOGE("Final parse failed (%s); keeping the partial message", e.what());
        }
        for (auto & call : final_msg.tool_calls) {
            if (call.id.empty()) call.id = random_call_id();
        }

        const std::string result =
            "{\"message\":" + final_msg.to_json_oaicompat().dump() +
            ",\"stop\":\"" + stop + "\"" +
            ",\"prompt_tokens\":" + std::to_string(prompt.size()) +
            ",\"reused_tokens\":" + std::to_string(n_reused) +
            ",\"generated_tokens\":" + std::to_string(n_gen) +
            ",\"prefill_ms\":" + std::to_string(prefill_us / 1000.0) +
            ",\"decode_ms\":" + std::to_string(decode_us / 1000.0) + "}";
        return to_bytes(env, result);
    } catch (const std::exception & e) {
        throw_java(env, std::string("Generation failed: ") + e.what());
        return nullptr;
    }
}

}  // extern "C"
