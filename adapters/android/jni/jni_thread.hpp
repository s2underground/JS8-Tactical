// A JNIEnv for the calling native thread, attaching it to the JVM if needed, and detaching it
// again when the thread ends. Native threads (the engine's decoder, audio and serial readers)
// call into Java; one attached and never detached keeps a Java Thread object alive after the
// native thread is gone, and ART logs an error for it ("thread exiting, not yet detached").
// The engine restarts on every USB reconnect, so those added up (audit L7, Nov 25).
#pragma once

#include <jni.h>

namespace js8tactical {

inline JNIEnv* attach_current_thread(JavaVM* jvm) {
  if (!jvm) return nullptr;
  JNIEnv* env = nullptr;
  int const status = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (status == JNI_OK) return env;
  if (status != JNI_EDETACHED) return nullptr;
  if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
  // Runs when this thread exits: only threads this function attached are detached here.
  struct Detacher {
    JavaVM* vm = nullptr;
    ~Detacher() {
      if (vm) vm->DetachCurrentThread();
    }
  };
  thread_local Detacher detacher;
  detacher.vm = jvm;
  return env;
}

}  // namespace js8tactical
