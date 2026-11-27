// JNI bridge for com.js8call.voacap.Voacap: one call, JSON in and JSON out (see rust/src/lib.rs).
#include <jni.h>

#include <cstdint>
#include <string>

#ifdef JS8_VOACAP_STUB
#include <cstdlib>
#include <cstring>
// Development build without the Rust engine (see CMakeLists.txt): every request fails.
static char *js8voacap_run(const char *, int *ok) {
    static const char kMsg[] = "this build has no VOACAP engine (built with js8.voacapStub)";
    *ok = 0;
    char *s = static_cast<char *>(std::malloc(sizeof kMsg));
    if (s != nullptr) std::memcpy(s, kMsg, sizeof kMsg);
    return s;
}
static void js8voacap_free(char *s) { std::free(s); }
static unsigned char *js8voacap_area(const char *, size_t *, char **err) {
    int ok = 0;
    *err = js8voacap_run(nullptr, &ok);
    return nullptr;
}
static void js8voacap_free_bytes(unsigned char *, size_t) {}
#else
extern "C" {
char *js8voacap_run(const char *request, int *ok);
void js8voacap_free(char *s);
unsigned char *js8voacap_area(const char *request, size_t *len, char **err);
void js8voacap_free_bytes(unsigned char *p, size_t len);
}
#endif

namespace {

// Java strings reach native code as modified UTF-8; the request is plain ASCII JSON built by
// Voacap.kt, so that encoding is never in play, but non-ASCII is refused rather than misread.
bool isAscii(const char *s) {
    for (; *s; ++s) {
        if (static_cast<unsigned char>(*s) >= 0x80) return false;
    }
    return true;
}

void throwError(JNIEnv *env, const std::string &message) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_js8call_voacap_Voacap_nativeRun(JNIEnv *env, jclass, jstring request) {
    if (request == nullptr) {
        throwError(env, "no request");
        return nullptr;
    }
    const char *req = env->GetStringUTFChars(request, nullptr);
    if (req == nullptr) return nullptr;  // OutOfMemoryError already pending
    if (!isAscii(req)) {
        env->ReleaseStringUTFChars(request, req);
        throwError(env, "request must be ASCII JSON");
        return nullptr;
    }
    int ok = 0;
    char *answer = js8voacap_run(req, &ok);
    env->ReleaseStringUTFChars(request, req);
    if (answer == nullptr) {
        throwError(env, "the prediction engine returned nothing");
        return nullptr;
    }
    std::string text(answer);
    js8voacap_free(answer);
    if (!ok) {
        throwError(env, text);
        return nullptr;
    }
    // The answer is ASCII JSON (numbers and fixed keys), so NewStringUTF reads it correctly.
    if (!isAscii(text.c_str())) {
        throwError(env, "the prediction engine returned non-ASCII text");
        return nullptr;
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_js8call_voacap_Voacap_nativeArea(JNIEnv *env, jclass, jstring request) {
    if (request == nullptr) {
        throwError(env, "no request");
        return nullptr;
    }
    const char *req = env->GetStringUTFChars(request, nullptr);
    if (req == nullptr) return nullptr;
    if (!isAscii(req)) {
        env->ReleaseStringUTFChars(request, req);
        throwError(env, "request must be ASCII JSON");
        return nullptr;
    }
    size_t len = 0;
    char *err = nullptr;
    unsigned char *grid = js8voacap_area(req, &len, &err);
    env->ReleaseStringUTFChars(request, req);
    if (grid == nullptr) {
        std::string message = err != nullptr ? std::string(err) : "the prediction engine returned nothing";
        if (err != nullptr) js8voacap_free(err);
        throwError(env, message);
        return nullptr;
    }
    jbyteArray out = nullptr;
    if (len <= static_cast<size_t>(INT32_MAX)) {
        out = env->NewByteArray(static_cast<jsize>(len));
        if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(grid));
    }
    js8voacap_free_bytes(grid, len);
    if (out == nullptr && !env->ExceptionCheck()) throwError(env, "area grid too large");
    return out;
}
