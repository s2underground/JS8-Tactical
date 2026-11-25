// Host test for adapters/android/jni/own_frame_text.hpp: the "Sending" bar's
// per-frame text must read, frame by frame and in full, as the message other
// stations copy. Built and run by scripts/test-core.sh.
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "js8core/protocol/varicode.hpp"
#include "own_frame_text.hpp"

namespace {

int failures = 0;

void check(bool ok, std::string const& what) {
  std::cout << (ok ? "OK: " : "FAIL: ") << what << "\n";
  if (!ok) ++failures;
}

struct Case {
  const char* mycall;
  const char* selected;
  const char* text;
  bool identify;
  int submode;
  // Expected text when joined; nullptr: the text plus a 3-character checksum.
  const char* on_air;
};

}  // namespace

int main() {
  using js8core::protocol::varicode::build_message_frames;
  std::vector<Case> const cases = {
      {"KN4CRD", "", "@HB HEARTBEAT EM73", true, 0, "@HB HEARTBEAT EM73"},
      // A receiver shows a CQ beacon as an @ALLCALL CQ.
      {"KN4CRD", "", "CQ CQ CQ EM73", true, 0, "@ALLCALL CQ CQ CQ EM73"},
      {"KN4CRD", "", "HELLO FROM THE FIELD, ALL STATIONS STAND BY FOR TRAFFIC", true, 0,
       "KN4CRD: HELLO FROM THE FIELD, ALL STATIONS STAND BY FOR TRAFFIC"},
      {"KN4CRD", "", "HELLO FROM THE FIELD, ALL STATIONS STAND BY FOR TRAFFIC", true, 1,
       "KN4CRD: HELLO FROM THE FIELD, ALL STATIONS STAND BY FOR TRAFFIC"},
      {"KN4CRD", "", "HELLO FROM THE FIELD, ALL STATIONS STAND BY FOR TRAFFIC", true, 2,
       "KN4CRD: HELLO FROM THE FIELD, ALL STATIONS STAND BY FOR TRAFFIC"},
      {"KN4CRD", "W1ABC", "GOOD COPY ON YOUR LAST, SEND THE SITREP", true, 0,
       "W1ABC GOOD COPY ON YOUR LAST, SEND THE SITREP"},
      {"KN4CRD", "", "W1ABC SNR?", true, 0, "W1ABC SNR?"},
      {"KN4CRD", "", "W1ABC MSG MEET AT THE CHECKPOINT AT 1800", true, 0, nullptr},
      {"KN4CRD", "", "@RACES MSG ROADS CLOSED NORTH OF TOWN", true, 0, nullptr},
      {"KN4CRD", "", "@RACES MSG ROADS CLOSED NORTH OF TOWN", true, 1, nullptr},
      {"EA8/K1ABC", "", "W1ABC MSG MEET AT THE CHECKPOINT", true, 0, nullptr},
  };
  for (auto const& c : cases) {
    auto const frames = build_message_frames(c.mycall, "EM73", c.selected, c.text, c.identify,
                                             false, c.submode, nullptr);
    auto const texts = js8tactical::own_frame_texts(frames);
    std::string joined;
    for (auto const& t : texts) joined += t;
    std::string const label =
        std::string(c.mycall) + " " + c.text + " (submode " + std::to_string(c.submode) + ")";
    check(!frames.empty() && texts.size() == frames.size(), "one text per frame: " + label);
    if (c.on_air) {
      check(joined == c.on_air, "reads as sent: " + label + " -> '" + joined + "'");
    } else {
      std::string const text = c.text;
      check(joined.size() == text.size() + 4 && joined.rfind(text + " ", 0) == 0,
            "reads as sent with its checksum: " + label + " -> '" + joined + "'");
    }
    for (std::size_t i = 0; i < texts.size(); ++i) {
      if (texts[i].size() == 12 && texts[i] == frames[i].first) {
        check(false, "frame " + std::to_string(i) + " fell back to raw: " + label);
      }
    }
  }
  check(js8tactical::own_frame_texts({}).empty(), "no frames, no texts");

  if (failures) {
    std::cerr << failures << " failure(s)\n";
    return EXIT_FAILURE;
  }
  return EXIT_SUCCESS;
}
