// libFuzzer harness for the JS8 frame decoders in js8core.
//
// Every frame that comes off the air is attacker-controlled: anyone with a transmitter can send
// any 72-bit payload that passes the LDPC and CRC checks. This harness feeds arbitrary payloads to
// the same unpack functions the Android JNI bridge calls (adapters/android/jni/js8_engine_jni.cpp),
// plus the text decoders behind them, under AddressSanitizer and UndefinedBehaviorSanitizer.
//
// Build and run: scripts/fuzz-core.sh [seconds]   (needs clang with libFuzzer)

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "js8core/protocol/jsc.hpp"
#include "js8core/protocol/varicode.hpp"

using namespace js8core::protocol;

namespace {

// Runs every decoder the receive path uses on one frame string, exactly as the JNI bridge does.
void decode_frame(std::string const& frame) {
  (void)varicode::unpack_fast_data_message(frame);
  (void)varicode::unpack_data_message(frame);

  std::uint8_t hb_type = 0, hb_bits3 = 0;
  bool hb_alt = false;
  (void)varicode::unpack_heartbeat_message(frame, &hb_type, &hb_alt, &hb_bits3);

  std::uint8_t c_type = 0, c_bits3 = 0;
  std::uint16_t c_num = 0;
  (void)varicode::unpack_compound_message(frame, &c_type, &c_num, &c_bits3);
  (void)varicode::unpack_compound_frame(frame, &c_type, &c_num, &c_bits3);

  std::uint8_t d_type = 0;
  (void)varicode::unpack_directed_message(frame, &d_type);
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
  if (size < 9) return 0;

  // 1. A well-formed 72-bit payload: 64 bits + 8 remainder bits, packed the way the decoder
  //    hands frames to the protocol layer. This is what an over-the-air attacker controls.
  std::uint64_t value = 0;
  for (int i = 0; i < 8; ++i) value = (value << 8) | data[i];
  const std::string frame = varicode::pack72bits(value, data[8]);
  decode_frame(frame);

  // 2. The same decoders on arbitrary text, in case any caller passes unvalidated strings.
  const std::string raw(reinterpret_cast<const char*>(data + 9), size - 9);
  decode_frame(raw);

  // 3. The text decoders on arbitrary bit strings.
  std::vector<bool> bits;
  bits.reserve((size - 9) * 8);
  for (std::size_t i = 9; i < size; ++i)
    for (int b = 7; b >= 0; --b) bits.push_back(((data[i] >> b) & 1) != 0);
  (void)jsc::decompress(bits);
  (void)varicode::huff_decode(varicode::default_huff_table(), bits);
  (void)varicode::unescape(raw);
  return 0;
}
