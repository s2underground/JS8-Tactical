// JNI bridge for com.js8call.voacap.Voacap: one call, JSON in and JSON out (see rust/src/lib.rs).
#include <jni.h>

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
#else
extern "C" {
char *js8voacap_run(const char *request, int *ok);
void js8voacap_free(char *s);
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
