// A Java string from any native bytes, without the aborts NewStringUTF gives on invalid input
// (see jni_text.hpp). Use this for every native string handed to Java.
#pragma once

#include <jni.h>

#include <string_view>

#include "jni_text.hpp"

namespace js8tactical {

inline jstring to_jstring(JNIEnv* env, std::string_view bytes) {
  auto const text = lenient_utf16(bytes);
  return env->NewString(reinterpret_cast<jchar const*>(text.data()), static_cast<jsize>(text.size()));
}

}  // namespace js8tactical
