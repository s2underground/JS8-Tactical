// JNI for com.js8call.sdr.RtlSdr: open an RTL-SDR from an Android USB file descriptor, tune it
// for a dial frequency, and stream 12 kHz upper-sideband audio to a Kotlin listener.
// Part of JS8-Tactical (GPL-3.0-or-later).
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "rtl-sdr.h"
#include "ssb_rx.hpp"

extern "C" int rtlsdr_open_fd(rtlsdr_dev_t** out_dev, int fd);
extern "C" int rtlsdr_js8_has_upconverter(rtlsdr_dev_t* dev);

namespace {

constexpr const char* TAG = "js8sdr";
constexpr size_t CHUNK = js8sdr::OUTPUT_RATE / 10;  // hand audio over every 100 ms

struct Session {
  rtlsdr_dev_t* dev = nullptr;
  js8sdr::SsbReceiver rx;
  std::mutex ctl;  // tuning calls
  std::atomic<bool> streaming{false};
  // Only touched on the thread inside nativeRun.
  JNIEnv* env = nullptr;
  jobject listener = nullptr;
  jmethodID on_audio = nullptr;
  jshortArray buf = nullptr;
  std::vector<int16_t> pending;
  bool failed = false;
};

Session* from(jlong h) { return reinterpret_cast<Session*>(h); }

void on_iq(unsigned char* data, uint32_t len, void* ctx) {
  auto* s = static_cast<Session*>(ctx);
  if (s->failed) return;
  s->rx.process(data, len, s->pending);
  JNIEnv* env = s->env;
  size_t off = 0;
  while (s->pending.size() - off >= CHUNK) {
    env->SetShortArrayRegion(s->buf, 0, static_cast<jsize>(CHUNK), s->pending.data() + off);
    env->CallVoidMethod(s->listener, s->on_audio, s->buf, static_cast<jint>(CHUNK));
    off += CHUNK;
    if (env->ExceptionCheck()) {
      // A listener that throws must not leave a Java exception pending in librtlsdr's loop.
      env->ExceptionClear();
      s->failed = true;
      rtlsdr_cancel_async(s->dev);
      break;
    }
  }
  s->pending.erase(s->pending.begin(), s->pending.begin() + static_cast<long>(off));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_js8call_sdr_RtlSdr_nativeOpen(JNIEnv*, jclass, jint fd) {
  auto s = std::make_unique<Session>();
  const int r = rtlsdr_open_fd(&s->dev, fd);
  if (r < 0 || !s->dev) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "rtlsdr_open_fd failed: %d", r);
    return 0;
  }
  if (rtlsdr_set_sample_rate(s->dev, js8sdr::INPUT_RATE) < 0 ||
      rtlsdr_set_agc_mode(s->dev, 0) < 0) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "rtlsdr setup failed");
    rtlsdr_close(s->dev);
    return 0;
  }
  return reinterpret_cast<jlong>(s.release());
}

JNIEXPORT jstring JNICALL Java_com_js8call_sdr_RtlSdr_nativeDescribe(JNIEnv* env, jclass, jlong h) {
  Session* s = from(h);
  if (!s) return env->NewStringUTF("");
  static const char* tuners[] = {"unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D"};
  const int t = rtlsdr_get_tuner_type(s->dev);
  char m[256] = {}, p[256] = {}, serial[256] = {};
  rtlsdr_get_usb_strings(s->dev, m, p, serial);
  // Device strings come from the dongle's EEPROM: keep only printable ASCII.
  auto clean = [](char* x) { for (char* c = x; *c; ++c) if (*c < 0x20 || *c > 0x7e) *c = '?'; };
  clean(m); clean(p);
  std::string d = std::string(m) + " " + p + ", tuner " + (t >= 0 && t <= 6 ? tuners[t] : "?");
  return env->NewStringUTF(d.c_str());
}

JNIEXPORT jboolean JNICALL Java_com_js8call_sdr_RtlSdr_nativeHasUpconverter(JNIEnv*, jclass, jlong h) {
  Session* s = from(h);
  return s && rtlsdr_js8_has_upconverter(s->dev) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL Java_com_js8call_sdr_RtlSdr_nativeGains(JNIEnv* env, jclass, jlong h) {
  Session* s = from(h);
  if (!s) return env->NewIntArray(0);
  const int n = rtlsdr_get_tuner_gains(s->dev, nullptr);
  if (n <= 0) return env->NewIntArray(0);
  std::vector<int> g(n);
  rtlsdr_get_tuner_gains(s->dev, g.data());
  jintArray out = env->NewIntArray(n);
  if (out) env->SetIntArrayRegion(out, 0, n, g.data());
  return out;
}

// Returns 0 on success; negative codes say which step failed (-1 plan, -2 sampling mode,
// -3 frequency). ppm and gain are applied best-effort (not every tuner supports them).
JNIEXPORT jint JNICALL Java_com_js8call_sdr_RtlSdr_nativeTune(JNIEnv*, jclass, jlong h, jlong dial_hz,
                                                             jint ds_pref, jint ppm, jint gain_tenths_db) {
  Session* s = from(h);
  if (!s || dial_hz <= 0) return -1;
  std::lock_guard<std::mutex> lock(s->ctl);
  const bool up = rtlsdr_js8_has_upconverter(s->dev) != 0;
  const js8sdr::TuningPlan p = js8sdr::plan_tuning(static_cast<uint64_t>(dial_hz), ds_pref, up);
  if (!p.ok) return -1;
  if (rtlsdr_get_direct_sampling(s->dev) != p.direct_sampling &&
      rtlsdr_set_direct_sampling(s->dev, p.direct_sampling) < 0) return -2;
  if (ppm != rtlsdr_get_freq_correction(s->dev)) rtlsdr_set_freq_correction(s->dev, ppm);
  if (gain_tenths_db < 0) {
    rtlsdr_set_tuner_gain_mode(s->dev, 0);
  } else {
    rtlsdr_set_tuner_gain_mode(s->dev, 1);
    rtlsdr_set_tuner_gain(s->dev, gain_tenths_db);
  }
  s->rx.set_inverted(p.inverted);
  if (rtlsdr_set_center_freq(s->dev, p.center_hz) < 0) return -3;
  __android_log_print(ANDROID_LOG_INFO, TAG, "tuned dial %lld Hz: centre %u Hz, direct sampling %d, inverted %d",
                      static_cast<long long>(dial_hz), p.center_hz, p.direct_sampling, p.inverted ? 1 : 0);
  return 0;
}

// Blocks, streaming audio to listener.onAudio(short[], int), until nativeCancel or the dongle is
// lost. Returns librtlsdr's result (0 after a cancel).
JNIEXPORT jint JNICALL Java_com_js8call_sdr_RtlSdr_nativeRun(JNIEnv* env, jclass, jlong h, jobject listener) {
  Session* s = from(h);
  if (!s || !listener) return -1;
  bool expected = false;
  if (!s->streaming.compare_exchange_strong(expected, true)) return -2;
  jclass cls = env->GetObjectClass(listener);
  s->on_audio = env->GetMethodID(cls, "onAudio", "([SI)V");
  env->DeleteLocalRef(cls);
  if (!s->on_audio) { env->ExceptionClear(); s->streaming = false; return -1; }
  jshortArray local = env->NewShortArray(static_cast<jsize>(CHUNK));
  if (!local) { s->streaming = false; return -1; }
  s->buf = static_cast<jshortArray>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  s->listener = env->NewGlobalRef(listener);
  s->env = env;
  s->failed = false;
  s->pending.clear();
  s->rx.reset();
  rtlsdr_reset_buffer(s->dev);
  const int r = rtlsdr_read_async(s->dev, on_iq, s, 0, 0);
  env->DeleteGlobalRef(s->listener);
  env->DeleteGlobalRef(s->buf);
  s->listener = nullptr;
  s->buf = nullptr;
  s->env = nullptr;
  s->streaming = false;
  return r;
}

JNIEXPORT void JNICALL Java_com_js8call_sdr_RtlSdr_nativeCancel(JNIEnv*, jclass, jlong h) {
  Session* s = from(h);
  if (s && s->streaming) rtlsdr_cancel_async(s->dev);
}

// Only after nativeRun has returned (RtlSdr.close() waits for it).
JNIEXPORT void JNICALL Java_com_js8call_sdr_RtlSdr_nativeClose(JNIEnv*, jclass, jlong h) {
  Session* s = from(h);
  if (!s) return;
  rtlsdr_close(s->dev);
  delete s;
}

}  // extern "C"
