// The text each frame of our own send carries on air, for the Traffic tab's
// "Sending" bar. Each frame is read back the way a receiving station unpacks
// it, so the bar shows what other stations copy, frame by frame, rather than
// what was typed. Header-only and free of Android types so the host test
// (tools/own_frame_text_test.cpp) runs it on the same frames the engine sends.
#pragma once

#include <cstddef>
#include <string>
#include <utility>
#include <vector>

#include "js8core/protocol/varicode.hpp"

namespace js8tactical {

namespace own_frame_detail {

inline std::string trim_front(std::string s) {
  std::size_t i = 0;
  while (i < s.size() && s[i] == ' ') ++i;
  return s.substr(i);
}

// Text for one frame and whether it is a data frame (data frames carry the
// message text exactly; the others carry headers and commands).
inline std::pair<std::string, bool> render(std::string const& frame, int type) {
  using namespace js8core::protocol::varicode;
  if (frame.size() < 12 || frame.find(' ') != std::string::npos) return {frame, false};

  // Same order a receiver tries them in: data, heartbeat, compound, directed.
  if (type & 0b100) {
    auto data = unpack_fast_data_message(frame);
    return {data, !data.empty()};
  }
  if (auto data = unpack_data_message(frame); !data.empty()) return {data, true};

  std::uint8_t hb_type = 0;
  bool hb_alt = false;
  std::uint8_t hb_bits3 = 0;
  auto hb = unpack_heartbeat_message(frame, &hb_type, &hb_alt, &hb_bits3);
  if (!hb.empty()) {
    static const char* const kCq[] = {"CQ CQ CQ", "CQ DX", "CQ QRP", "CQ CONTEST",
                                      "CQ FIELD", "CQ FD", "CQ CQ", "CQ"};
    std::string out = hb_alt ? std::string("@ALLCALL ") + (hb_bits3 < 8 ? kCq[hb_bits3] : "CQ")
                             : std::string("@HB HEARTBEAT");
    if (hb.size() > 2 && !hb[2].empty()) out += " " + hb[2];
    return {out, false};
  }

  {
    std::uint8_t ctype = 0;
    std::uint16_t num = 0;
    std::uint8_t bits3 = 0;
    auto parts = unpack_compound_message(frame, &ctype, &num, &bits3);
    if (!parts.empty()) {
      // The identity header of a compound callsign: it says who is sending,
      // which the bar already knows. It carries none of the message.
      if (ctype == 1) return {std::string(), false};
      std::string out;
      for (auto const& raw : parts) {
        auto part = trim_front(raw);
        if (part.empty()) continue;
        if (!out.empty()) out += " ";
        out += part;
      }
      if (!out.empty()) return {out, false};
    }
  }

  {
    std::uint8_t dtype = 0;
    auto parts = unpack_directed_message(frame, &dtype);
    if (!parts.empty()) {
      // parts[0] is the sender (us); the rest is the addressee and command.
      std::string out;
      for (std::size_t i = 1; i < parts.size(); ++i) {
        auto part = trim_front(parts[i]);
        if (part.empty()) continue;
        if (!out.empty()) out += " ";
        out += part;
      }
      if (!out.empty()) return {out, false};
    }
  }
  return {frame, false};
}

}  // namespace own_frame_detail

// One text per frame, in order. Joined they read as the message on air: a
// header or command frame is followed by a space when more frames follow it.
inline std::vector<std::string> own_frame_texts(
    std::vector<std::pair<std::string, int>> const& frames) {
  std::vector<std::string> texts;
  texts.reserve(frames.size());
  for (std::size_t i = 0; i < frames.size(); ++i) {
    auto [text, data] = own_frame_detail::render(frames[i].first, frames[i].second);
    if (!data && !text.empty() && i + 1 < frames.size()) text += " ";
    texts.push_back(std::move(text));
  }
  return texts;
}

}  // namespace js8tactical
