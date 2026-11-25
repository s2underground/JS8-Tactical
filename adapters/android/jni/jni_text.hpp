// Text from native code to Java, safely. JNI's NewStringUTF needs valid *modified* UTF-8: given
// anything else, a debuggable build (CheckJNI) aborts the whole app and a release build's
// behaviour depends on the Android version. Text decoded off the air can hold any byte (the JS8
// dictionary itself has single Latin-1 bytes such as 0xC0), so every native string bound for
// Java goes through lenient_utf16() and NewString instead. Header-only and free of JNI types so
// the host test (tools/jni_text_test.cpp) runs it.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace js8tactical {

// Decodes [bytes] as UTF-8 where it is valid, and reads every byte that isn't part of a valid
// sequence as Latin-1 (so the dictionary's 0xC0 shows as "À"). NUL bytes are dropped. Never
// fails: any input gives well-formed UTF-16.
inline std::u16string lenient_utf16(std::string_view bytes) {
  std::u16string out;
  out.reserve(bytes.size());
  auto const* b = reinterpret_cast<unsigned char const*>(bytes.data());
  std::size_t const n = bytes.size();
  std::size_t i = 0;
  auto cont = [&](std::size_t k) { return i + k < n && (b[i + k] & 0xC0) == 0x80; };
  while (i < n) {
    unsigned char const c = b[i];
    if (c == 0) { ++i; continue; }
    if (c < 0x80) { out.push_back(static_cast<char16_t>(c)); ++i; continue; }

    std::uint32_t cp = 0;
    std::size_t len = 0;
    if (c >= 0xC2 && c <= 0xDF && cont(1)) {
      cp = ((c & 0x1Fu) << 6) | (b[i + 1] & 0x3Fu);
      len = 2;
    } else if (c >= 0xE0 && c <= 0xEF && cont(1) && cont(2)) {
      cp = ((c & 0x0Fu) << 12) | ((b[i + 1] & 0x3Fu) << 6) | (b[i + 2] & 0x3Fu);
      // Overlong forms and UTF-16 surrogates are not valid UTF-8.
      if (cp >= 0x800 && (cp < 0xD800 || cp > 0xDFFF)) len = 3;
    } else if (c >= 0xF0 && c <= 0xF4 && cont(1) && cont(2) && cont(3)) {
      cp = ((c & 0x07u) << 18) | ((b[i + 1] & 0x3Fu) << 12) | ((b[i + 2] & 0x3Fu) << 6) | (b[i + 3] & 0x3Fu);
      if (cp >= 0x10000 && cp <= 0x10FFFF) len = 4;
    }

    if (len == 0) {
      out.push_back(static_cast<char16_t>(c));  // Latin-1
      ++i;
    } else if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
      out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
      i += len;
    } else {
      out.push_back(static_cast<char16_t>(cp));
      i += len;
    }
  }
  return out;
}

}  // namespace js8tactical
