// JNI bridge to llama.cpp for JS8-Tactical's optional on-device summary.
// One model + context per handle; generation is synchronous on the caller's thread.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "js8llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    double load_ms = 0;
    // stats of the last generate(): prompt tokens, prompt ms, generated tokens, generation ms
    jlong stats[4] = {0, 0, 0, 0};
    std::string error;
};

double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

std::string to_string(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    if (!c) return {};  // out of memory: an exception is pending for the caller
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Model output can split a UTF-8 character across tokens; NewStringUTF needs whole characters
// (and modified UTF-8), so build Java strings from UTF-16 ourselves.
jstring to_jstring(JNIEnv * env, const std::string & s) {
    std::u16string u;
    u.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        unsigned char c = s[i];
        uint32_t cp; size_t n;
        if (c < 0x80) { cp = c; n = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; n = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; n = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; n = 4; }
        else { i++; continue; }
        if (i + n > s.size()) break;
        bool ok = true;
        for (size_t k = 1; k < n; k++) {
            unsigned char cc = s[i + k];
            if ((cc >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { i++; continue; }
        i += n;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u.push_back(char16_t(0xD800 + (cp >> 10)));
            u.push_back(char16_t(0xDC00 + (cp & 0x3FF)));
        } else {
            u.push_back(char16_t(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), (jsize) u.size());
}

// Bytes at the end of s that start an unfinished UTF-8 character.
size_t incomplete_tail(const std::string & s) {
    for (size_t back = 1; back <= 3 && back <= s.size(); back++) {
        unsigned char c = s[s.size() - back];
        if ((c >> 6) == 0x2) continue; // continuation byte
        size_t need = (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        return need > back ? back : 0;
    }
    return 0;
}

bool g_backend = false;

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_js8call_llm_LlamaBridge_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    if (!g_backend) {
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            // CONT continues the previous line (the loader's progress dots follow an INFO line):
            // judge it by that line's level, so the dots no longer fill the log as warnings.
            static thread_local ggml_log_level last = GGML_LOG_LEVEL_INFO;
            if (level == GGML_LOG_LEVEL_CONT) level = last; else last = level;
            if (level >= GGML_LOG_LEVEL_WARN && level != GGML_LOG_LEVEL_CONT)
                __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text ? text : "");
        }, nullptr);
        llama_backend_init();
        g_backend = true;
    }
    auto * s = new Session();
    const std::string path = to_string(env, jpath);
    const double t0 = now_ms();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    s->model = llama_model_load_from_file(path.c_str(), mp);
    if (!s->model) {
        LOGE("model load failed: %s", path.c_str());
        delete s;
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx) {
        LOGE("context init failed");
        llama_model_free(s->model);
        delete s;
        return 0;
    }
    s->load_ms = now_ms() - t0;
    LOGI("loaded %s in %.0f ms (ctx %d, threads %d)", path.c_str(), s->load_ms, n_ctx, n_threads);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_js8call_llm_LlamaBridge_nativeLoadMs(JNIEnv *, jclass, jlong h) {
    return h ? reinterpret_cast<Session *>(h)->load_ms : 0;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_js8call_llm_LlamaBridge_nativeStats(JNIEnv * env, jclass, jlong h) {
    jlongArray out = env->NewLongArray(4);
    if (h) env->SetLongArrayRegion(out, 0, 4, reinterpret_cast<Session *>(h)->stats);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_js8call_llm_LlamaBridge_nativeError(JNIEnv * env, jclass, jlong h) {
    return to_jstring(env, h ? reinterpret_cast<Session *>(h)->error : std::string("not loaded"));
}

// Formats [system, user] with the model's own chat template, then generates up to max_tokens.
// listener.onToken(String) is called with each new piece of text; returning false stops early.
extern "C" JNIEXPORT jstring JNICALL
Java_com_js8call_llm_LlamaBridge_nativeGenerate(JNIEnv * env, jclass, jlong h, jstring jsystem, jstring juser,
                                               jint max_tokens, jfloat temperature, jobject listener) {
    auto * s = reinterpret_cast<Session *>(h);
    if (!s) return to_jstring(env, "");
    s->error.clear();
    for (auto & v : s->stats) v = 0;

    const std::string system = to_string(env, jsystem);
    // The user text quotes what was heard on the air. The prompt is tokenized with special
    // tokens enabled (the chat template needs them), so break up anything in it that reads as a
    // control token ("<|im_start|>"…): a station can't steer the summary that way.
    std::string user = to_string(env, juser);
    for (std::size_t at = user.find("<|"); at != std::string::npos; at = user.find("<|", at + 3)) {
        user.insert(at + 1, " ");
    }
    llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(system.size() + user.size() + 1024);
    int n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    }
    std::string prompt = n > 0 ? std::string(buf.data(), n) : (system + "\n\n" + user + "\n\n");

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        s->error = "tokenize failed";
        return to_jstring(env, "");
    }
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    if (n_prompt + max_tokens > n_ctx) {
        s->error = "prompt too long: " + std::to_string(n_prompt) + " tokens";
        return to_jstring(env, "");
    }

    jmethodID on_token = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_batch_ext * batch = llama_batch_ext_init(s->ctx);
    const int n_batch = (int) llama_n_batch(s->ctx);
    std::string out, pending;

    // Prompt, in batch-sized chunks; logits only for the very last token.
    const double tp = now_ms();
    for (int i = 0; i < n_prompt; i += n_batch) {
        const int m = std::min(n_batch, n_prompt - i);
        llama_batch_ext_clear(batch);
        for (int k = 0; k < m; k++) {
            const int idx = llama_batch_ext_add_token(batch, 0, tokens[i + k]);
            const llama_pos pos = i + k;
            llama_batch_ext_set_pos(batch, idx, &pos);
        }
        if (i + m == n_prompt) llama_batch_ext_set_output_logits(batch, m - 1, true);
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            s->error = "prompt decode failed";
            llama_batch_ext_free(batch);
            llama_sampler_free(smpl);
            return to_jstring(env, "");
        }
    }
    s->stats[0] = n_prompt;
    s->stats[1] = (jlong) (now_ms() - tp);

    const double tg = now_ms();
    int generated = 0;
    llama_pos pos = n_prompt;
    while (generated < max_tokens) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        char piece[256];
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) {
            pending.append(piece, len);
            const size_t tail = incomplete_tail(pending);
            const std::string ready = pending.substr(0, pending.size() - tail);
            pending = pending.substr(pending.size() - tail);
            out += ready;
            if (on_token && !ready.empty()) {
                jstring js = to_jstring(env, ready);
                const bool go = env->CallBooleanMethod(listener, on_token, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
                if (!go) { generated++; break; }
            }
        }
        generated++;
        llama_batch_ext_clear(batch);
        const int idx = llama_batch_ext_add_token(batch, 0, tok);
        llama_batch_ext_set_pos(batch, idx, &pos);
        llama_batch_ext_set_output_logits(batch, idx, true);
        pos++;
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            s->error = "decode failed";
            break;
        }
    }
    s->stats[2] = generated;
    s->stats[3] = (jlong) (now_ms() - tg);
    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);
    return to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_js8call_llm_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong h) {
    auto * s = reinterpret_cast<Session *>(h);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}
