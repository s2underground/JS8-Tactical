// JNI for com.js8call.sdr.AirspyHf: an Airspy HF+ opened from an Android USB file descriptor
// (libairspyhf's own airspyhf_open_fd), 192 kS/s float IQ through the shared SSB receiver,
// 12 kHz audio to a Kotlin listener. libairspyhf calls back on its own thread; the audio is
// queued there and handed to Java on the thread inside nativeRun, so every JNI call stays on a
// thread Java started. Part of JS8-Tactical (GPL-3.0-or-later).
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "airspyhf.h"
#include "ssb_rx.hpp"

namespace {

constexpr const char* TAG = "js8sdr";
constexpr size_t CHUNK = js8sdr::OUTPUT_RATE / 10;            // 100 ms per call into Java
constexpr size_t MAX_QUEUED = js8sdr::OUTPUT_RATE * 2;         // drop the oldest past 2 s

struct Airspy {
  airspyhf_device_t* dev = nullptr;
  js8sdr::SsbReceiver rx;
  std::mutex mu;
  std::condition_variable cv;
  std::deque<int16_t> queue;      // guarded by mu
  std::vector<int16_t> scratch;   // callback thread only
  std::atomic<bool> stop{false};
  std::atomic<bool> streaming{false};
  std::atomic<uint64_t> dropped{0};
};

Airspy* from(jlong h) { return reinterpret_cast<Airspy*>(h); }

int on_samples(airspyhf_transfer_t* t) {
  auto* a = static_cast<Airspy*>(t->ctx);
  if (a->stop.load()) return 0;
  a->scratch.clear();
  a->rx.process_float(reinterpret_cast<const float*>(t->samples), static_cast<size_t>(t->sample_count), a->scratch);
  if (t->dropped_samples) a->dropped.fetch_add(t->dropped_samples);
  {
    std::lock_guard<std::mutex> lock(a->mu);
    a->queue.insert(a->queue.end(), a->scratch.begin(), a->scratch.end());
    if (a->queue.size() > MAX_QUEUED) a->queue.erase(a->queue.begin(), a->queue.begin() + static_cast<long>(a->queue.size() - MAX_QUEUED));
  }
  a->cv.notify_one();
  return 0;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_js8call_sdr_AirspyHf_nativeOpen(JNIEnv*, jclass, jint fd) {
  auto a = std::make_unique<Airspy>();
  const int r = airspyhf_open_fd(&a->dev, fd);
  if (r != AIRSPYHF_SUCCESS || !a->dev) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "airspyhf_open_fd failed: %d", r);
    return 0;
  }
  if (airspyhf_set_samplerate(a->dev, js8sdr::AIRSPY_RATE) != AIRSPYHF_SUCCESS) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "Airspy HF+: %d S/s not offered by this firmware", js8sdr::AIRSPY_RATE);
    airspyhf_close(a->dev);
    return 0;
  }
  // The library's IQ correction and IF shift, and the device's HF AGC.
  airspyhf_set_lib_dsp(a->dev, 1);
  airspyhf_set_hf_agc(a->dev, 1);
  return reinterpret_cast<jlong>(a.release());
}

JNIEXPORT jstring JNICALL Java_com_js8call_sdr_AirspyHf_nativeDescribe(JNIEnv* env, jclass, jlong h) {
  Airspy* a = from(h);
  if (!a) return env->NewStringUTF("");
  char version[MAX_VERSION_STRING_SIZE + 1] = {};
  airspyhf_version_string_read(a->dev, version, MAX_VERSION_STRING_SIZE);
  for (char* c = version; *c; ++c) if (*c < 0x20 || *c > 0x7e) *c = '?';
  std::string d = std::string("Airspy HF+, firmware ") + (version[0] ? version : "?");
  return env->NewStringUTF(d.c_str());
}

// 0 on success, -1 can't tune there, -3 the device refused the frequency.
JNIEXPORT jint JNICALL Java_com_js8call_sdr_AirspyHf_nativeTune(JNIEnv*, jclass, jlong h, jlong dial_hz) {
  Airspy* a = from(h);
  if (!a || dial_hz <= js8sdr::AIRSPY_OFFSET_HZ || dial_hz > 0xFFFFFFFFLL) return -1;
  const auto centre = static_cast<uint32_t>(dial_hz - js8sdr::AIRSPY_OFFSET_HZ);
  if (airspyhf_set_freq(a->dev, centre) != AIRSPYHF_SUCCESS) return -3;
  __android_log_print(ANDROID_LOG_INFO, TAG, "Airspy HF+ tuned dial %lld Hz: centre %u Hz",
                      static_cast<long long>(dial_hz), centre);
  return 0;
}

// Streams until nativeCancel (returns 0) or the device stops (returns -3). Blocks.
JNIEXPORT jint JNICALL Java_com_js8call_sdr_AirspyHf_nativeRun(JNIEnv* env, jclass, jlong h, jobject listener) {
  Airspy* a = from(h);
  if (!a || !listener) return -1;
  bool expected = false;
  if (!a->streaming.compare_exchange_strong(expected, true)) return -2;
  jclass cls = env->GetObjectClass(listener);
  jmethodID on_audio = env->GetMethodID(cls, "onAudio", "([SI)V");
  env->DeleteLocalRef(cls);
  jshortArray buf = env->NewShortArray(static_cast<jsize>(CHUNK));
  if (!on_audio || !buf) { env->ExceptionClear(); a->streaming = false; return -1; }
  a->stop = false;
  { std::lock_guard<std::mutex> lock(a->mu); a->queue.clear(); }
  a->rx.reset();
  if (airspyhf_start(a->dev, on_samples, a) != AIRSPYHF_SUCCESS) {
    env->DeleteLocalRef(buf);
    a->streaming = false;
    return -3;
  }
  int result = 0;
  std::vector<int16_t> chunk(CHUNK);
  while (!a->stop.load()) {
    {
      std::unique_lock<std::mutex> lock(a->mu);
      a->cv.wait_for(lock, std::chrono::milliseconds(500), [a] { return a->stop.load() || a->queue.size() >= CHUNK; });
      if (a->stop.load()) break;
      if (a->queue.size() < CHUNK) {
        // Nothing for half a second: is the device still there?
        lock.unlock();
        if (!airspyhf_is_streaming(a->dev)) { result = -3; break; }
        continue;
      }
      std::copy(a->queue.begin(), a->queue.begin() + static_cast<long>(CHUNK), chunk.begin());
      a->queue.erase(a->queue.begin(), a->queue.begin() + static_cast<long>(CHUNK));
    }
    env->SetShortArrayRegion(buf, 0, static_cast<jsize>(CHUNK), chunk.data());
    env->CallVoidMethod(listener, on_audio, buf, static_cast<jint>(CHUNK));
    if (env->ExceptionCheck()) { env->ExceptionClear(); result = -1; break; }
  }
  a->stop = true;
  airspyhf_stop(a->dev);
  env->DeleteLocalRef(buf);
  const uint64_t dropped = a->dropped.exchange(0);
  if (dropped) __android_log_print(ANDROID_LOG_INFO, TAG, "Airspy HF+: %llu samples dropped while streaming", static_cast<unsigned long long>(dropped));
  a->streaming = false;
  return result;
}

JNIEXPORT void JNICALL Java_com_js8call_sdr_AirspyHf_nativeCancel(JNIEnv*, jclass, jlong h) {
  Airspy* a = from(h);
  if (!a) return;
  a->stop = true;
  a->cv.notify_all();
}

// Only after nativeRun has returned (SdrSession waits for it).
JNIEXPORT void JNICALL Java_com_js8call_sdr_AirspyHf_nativeClose(JNIEnv*, jclass, jlong h) {
  Airspy* a = from(h);
  if (!a) return;
  airspyhf_close(a->dev);
  delete a;
}

}  // extern "C"
