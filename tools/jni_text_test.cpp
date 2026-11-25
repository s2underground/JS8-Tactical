// Host test for adapters/android/jni/jni_text.hpp: whatever bytes native code produces, the
// text handed to Java is well-formed UTF-16 (NewStringUTF would abort the app on some of them).
// Built and run by scripts/test-core.sh.
#include <cstdlib>
#include <iostream>
#include <random>
#include <string>

#include "js8core/protocol/varicode.hpp"
#include "jni_text.hpp"

namespace {

int failures = 0;

void check(bool ok, std::string const& what) {
  std::cout << (ok ? "OK: " : "FAIL: ") << what << "\n";
  if (!ok) ++failures;
}

bool well_formed(std::u16string const& s) {
  for (std::size_t i = 0; i < s.size(); ++i) {
    char16_t const c = s[i];
    if (c >= 0xD800 && c <= 0xDBFF) {
      if (i + 1 >= s.size() || s[i + 1] < 0xDC00 || s[i + 1] > 0xDFFF) return false;
      ++i;
    } else if (c >= 0xDC00 && c <= 0xDFFF) {
      return false;
    }
  }
  return true;
}

}  // namespace

int main() {
  using js8tactical::lenient_utf16;
  check(lenient_utf16("HELLO 123") == u"HELLO 123", "ASCII passes through");
  check(lenient_utf16("\xc3\xa9t\xc3\xa9") == u"été", "valid UTF-8 decodes");
  check(lenient_utf16("\xe2\x99\xa2") == u"♢", "the end-of-message diamond decodes");
  check(lenient_utf16("\xf0\x9f\x93\xbb") == u"\U0001F4FB", "4-byte UTF-8 becomes a surrogate pair");
  check(lenient_utf16("A\xc0" "B") == u"AÀB", "a lone dictionary byte reads as Latin-1");
  check(lenient_utf16("\xa1\xbf") == u"¡¿", "stray continuation bytes read as Latin-1");
  check(lenient_utf16("\xc0\x80") == u"À\u0080", "overlong NUL is not decoded");
  check(lenient_utf16("\xed\xa0\x80") == u"í \u0080", "an encoded surrogate is not decoded");
  check(lenient_utf16("\xf4\x90\x80\x80") == u"ô\u0090\u0080\u0080", "past U+10FFFF is not decoded");
  check(lenient_utf16("\xe2\x99") == u"â\u0099", "a cut-off sequence reads as Latin-1");
  check(lenient_utf16(std::string("A\0B", 3)) == u"AB", "NUL is dropped");
  check(lenient_utf16("").empty(), "empty stays empty");

  // Every single-byte dictionary entry, as the decoder produces it.
  {
    bool ok = true;
    for (int c = 0x80; c <= 0xFF; ++c) {
      auto const s = lenient_utf16(std::string(1, static_cast<char>(c)));
      if (s.size() != 1 || s[0] != static_cast<char16_t>(c)) ok = false;
    }
    check(ok, "every high byte alone maps to its Latin-1 character");
  }

  // The real path: a data frame whose dictionary word is a single high byte (JSC entries
  // 10704-10735), packed and decoded by the core as any station's frame would be.
  {
    using namespace js8core::protocol::varicode;
    std::string const sent = "HI \xc0 THERE";
    int n = 0;
    auto const frame = pack_data_message(sent, &n);
    auto const heard = unpack_data_message(frame);
    bool const has_high = heard.find('\xc0') != std::string::npos;
    auto const text = lenient_utf16(heard);
    check(!frame.empty() && has_high, "the decoder can hand native code a lone 0xC0 byte: '" + heard + "'");
    check(well_formed(text) && text.find(u'\u00c0') != std::u16string::npos, "and it reaches Java as \"\u00c0\"");
  }

  // Random bytes: always well-formed, never longer than the input.
  {
    std::mt19937 rng(8);
    bool ok = true;
    for (int round = 0; round < 200000 && ok; ++round) {
      std::string bytes(rng() % 24, '\0');
      for (auto& ch : bytes) ch = static_cast<char>(rng() & 0xFF);
      auto const s = lenient_utf16(bytes);
      if (!well_formed(s) || s.size() > bytes.size()) ok = false;
    }
    check(ok, "200000 random byte strings give well-formed UTF-16");
  }

  if (failures) {
    std::cerr << failures << " failure(s)\n";
    return EXIT_FAILURE;
  }
  return EXIT_SUCCESS;
}
