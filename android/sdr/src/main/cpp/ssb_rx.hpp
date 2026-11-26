// SSB (upper sideband) receiver for an SDR, 12 kHz audio out. Two inputs:
//  - RTL-SDR: 8-bit IQ at 960 kS/s, tuned TUNE_OFFSET_HZ (60 kHz) below the dial;
//  - Airspy HF+: float IQ at 192 kS/s, tuned AIRSPY_OFFSET_HZ (24 kHz) below the dial.
// Plain C++ with no Android or driver dependency, so the host tests (src/test/cpp) cover it.
//
// The offset keeps the wanted passband clear of the device's DC spike; mixing brings the dial
// to 0 Hz. FIR decimators (/5 for the RTL-SDR only, then /4, /4) take it to 12 kHz complex, and
// a complex filter keeps 0..3300 Hz (the upper sideband) before the real part becomes the audio.
// A slow AGC sets the level for the decoder.
//
// Direct sampling (an RTL-SDR Blog V3 on HF) above half the 28.8 MHz ADC clock sees the band as
// an alias, mirrored: `inverted` conjugates the IQ so upper sideband stays upper sideband.
#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace js8sdr {

constexpr int INPUT_RATE = 960000;
constexpr int OUTPUT_RATE = 12000;
constexpr int TUNE_OFFSET_HZ = 60000;
constexpr uint32_t DEFAULT_XTAL_HZ = 28800000;
constexpr int AIRSPY_RATE = 192000;
constexpr int AIRSPY_OFFSET_HZ = 24000;

// Where to tune the dongle for a dial frequency, and how to read what comes back.
struct TuningPlan {
  uint32_t center_hz = 0;  // frequency to set (for direct sampling: the alias the ADC sees)
  bool inverted = false;   // spectrum mirrored (direct-sampling alias above Nyquist)
  int direct_sampling = 0; // 0 off, 1 I branch, 2 Q branch (librtlsdr's values)
  bool ok = false;
};

// ds_pref: 0 = automatic (direct sampling below 28.8 MHz unless the dongle has an
// upconverter, as the RTL-SDR Blog V4 does), 1 = off, 2 = Q branch, 3 = I branch.
inline TuningPlan plan_tuning(uint64_t dial_hz, int ds_pref, bool has_upconverter,
                              uint32_t xtal_hz = DEFAULT_XTAL_HZ) {
  TuningPlan p;
  int ds = 0;
  if (ds_pref == 2) ds = 2;
  else if (ds_pref == 3) ds = 1;
  else if (ds_pref == 0 && !has_upconverter && dial_hz < 28800000ULL) ds = 2;
  p.direct_sampling = ds;
  if (ds != 0) {
    const uint64_t nyquist = xtal_hz / 2;
    if (dial_hz < static_cast<uint64_t>(TUNE_OFFSET_HZ) || dial_hz >= xtal_hz) return p;
    if (dial_hz + 3000 < nyquist) {
      p.center_hz = static_cast<uint32_t>(dial_hz - TUNE_OFFSET_HZ);
    } else {
      // Alias at xtal - f, mirrored. Tuning above the alias puts the passband at
      // +offset..+offset+3 kHz once the IQ is conjugated.
      p.center_hz = static_cast<uint32_t>(xtal_hz - dial_hz + TUNE_OFFSET_HZ);
      p.inverted = true;
    }
  } else {
    if (dial_hz < static_cast<uint64_t>(TUNE_OFFSET_HZ) || dial_hz > 0xFFFFFFFFULL) return p;
    p.center_hz = static_cast<uint32_t>(dial_hz - TUNE_OFFSET_HZ);
  }
  p.ok = true;
  return p;
}

// Windowed-sinc low-pass (Blackman), unity DC gain.
inline std::vector<float> lowpass_taps(int n, double cutoff_hz, double rate_hz) {
  std::vector<float> h(n);
  const double fc = cutoff_hz / rate_hz;
  const double m = n - 1;
  double sum = 0;
  for (int i = 0; i < n; ++i) {
    const double x = i - m / 2.0;
    const double sinc = x == 0 ? 2 * fc : std::sin(2 * M_PI * fc * x) / (M_PI * x);
    const double w = 0.42 - 0.5 * std::cos(2 * M_PI * i / m) + 0.08 * std::cos(4 * M_PI * i / m);
    h[i] = static_cast<float>(sinc * w);
    sum += h[i];
  }
  for (auto& v : h) v = static_cast<float>(v / sum);
  return h;
}

// Complex FIR decimator: keeps a history ring, outputs one sample every `factor` inputs.
class Decimator {
 public:
  Decimator(int factor, std::vector<float> taps)
      : factor_(factor), taps_(std::move(taps)), hist_(taps_.size() * 2) {}

  // Push one sample; returns true and sets `out` when an output sample is due.
  bool push(std::complex<float> x, std::complex<float>& out) {
    const size_t n = taps_.size();
    hist_[pos_] = x;
    hist_[pos_ + n] = x;  // mirrored so the convolution never wraps
    pos_ = (pos_ + 1) % n;
    if (++phase_ < factor_) return false;
    phase_ = 0;
    float re = 0, im = 0;
    const std::complex<float>* h = &hist_[pos_];  // oldest .. newest
    for (size_t i = 0; i < n; ++i) {
      re += taps_[n - 1 - i] * h[i].real();
      im += taps_[n - 1 - i] * h[i].imag();
    }
    out = {re, im};
    return true;
  }

  void reset() {
    std::fill(hist_.begin(), hist_.end(), std::complex<float>{});
    pos_ = 0;
    phase_ = 0;
  }

 private:
  int factor_;
  std::vector<float> taps_;
  std::vector<std::complex<float>> hist_;
  size_t pos_ = 0;
  int phase_ = 0;
};

class SsbReceiver {
 public:
  static constexpr double SSB_CENTER_HZ = 1650.0;  // middle of the 0..3300 Hz passband

  SsbReceiver()
      : d1_(5, lowpass_taps(25, 20000, INPUT_RATE)),
        d2_(4, lowpass_taps(41, 8000, INPUT_RATE / 5)),
        d3_(4, lowpass_taps(31, 4500, INPUT_RATE / 20)),
        ssb_(1, lowpass_taps(191, SSB_CENTER_HZ, OUTPUT_RATE)) {
    static_assert(INPUT_RATE / 5 == AIRSPY_RATE, "the Airspy input joins after the first stage");
    for (int i = 0; i < MIX_LEN; ++i) {
      const double a = -2 * M_PI * TUNE_OFFSET_HZ * i / INPUT_RATE;
      mix_[i] = {static_cast<float>(std::cos(a)), static_cast<float>(std::sin(a))};
    }
    for (int i = 0; i < AIRSPY_MIX_LEN; ++i) {
      const double a = -2 * M_PI * AIRSPY_OFFSET_HZ * i / AIRSPY_RATE;
      mix_a_[i] = {static_cast<float>(std::cos(a)), static_cast<float>(std::sin(a))};
    }
  }

  // Interleaved float I/Q at AIRSPY_RATE (the Airspy HF+), tuned AIRSPY_OFFSET_HZ below the
  // dial. Joins the RTL-SDR chain after its first decimator. Appends 16-bit audio at OUTPUT_RATE.
  void process_float(const float* iq, size_t complex_count, std::vector<int16_t>& out) {
    for (size_t k = 0; k < complex_count; ++k) {
      const std::complex<float> x = std::complex<float>(iq[2 * k], iq[2 * k + 1]) * mix_a_[mix_a_pos_];
      mix_a_pos_ = (mix_a_pos_ + 1) % AIRSPY_MIX_LEN;
      std::complex<float> b, c;
      if (!d2_.push(x, b) || !d3_.push(b, c)) continue;
      out.push_back(audio_sample(c));
    }
  }

  void set_inverted(bool inverted) { inverted_.store(inverted, std::memory_order_relaxed); }
  bool inverted() const { return inverted_.load(std::memory_order_relaxed); }

  // Interleaved unsigned 8-bit I/Q from the dongle. Appends 16-bit audio at OUTPUT_RATE.
  void process(const uint8_t* iq, size_t bytes, std::vector<int16_t>& out) {
    const bool inv = inverted();
    for (size_t k = 0; k + 1 < bytes; k += 2) {
      float i = (iq[k] - 127.4f) * (1.0f / 128.0f);
      float q = (iq[k + 1] - 127.4f) * (1.0f / 128.0f);
      if (inv) q = -q;
      const std::complex<float> x = std::complex<float>(i, q) * mix_[mix_pos_];
      mix_pos_ = (mix_pos_ + 1) % MIX_LEN;
      std::complex<float> a, b, c;
      if (!d1_.push(x, a) || !d2_.push(a, b) || !d3_.push(b, c)) continue;
      out.push_back(audio_sample(c));
    }
  }

  void reset() {
    d1_.reset(); d2_.reset(); d3_.reset(); ssb_.reset();
    mix_pos_ = 0; mix_a_pos_ = 0; ssb_phase_ = 0; level_ = 0; gain_ = INITIAL_GAIN;
  }

  float gain() const { return gain_; }

 private:
  static constexpr int MIX_LEN = INPUT_RATE / TUNE_OFFSET_HZ;  // 16: the offset tone's period
  static constexpr int AIRSPY_MIX_LEN = AIRSPY_RATE / AIRSPY_OFFSET_HZ;  // 8
  static_assert(AIRSPY_RATE % AIRSPY_OFFSET_HZ == 0, "offset must divide the sample rate");
  static constexpr int SSB_ROT_LEN = 80;  // 1650 Hz at 12 kHz repeats exactly every 80 samples
  static_assert(INPUT_RATE % TUNE_OFFSET_HZ == 0, "offset must divide the sample rate");
  static constexpr float INITIAL_GAIN = 20.0f;
  static constexpr float TARGET_RMS = 0.12f;
  static constexpr float MIN_GAIN = 0.5f, MAX_GAIN = 20000.0f;

  int16_t audio_sample(std::complex<float> c) {
    // Upper sideband: shift the 0..3300 Hz band down to centre on 0, low-pass, shift back, real part.
    const double w = 2 * M_PI * SSB_CENTER_HZ / OUTPUT_RATE;
    const std::complex<float> rot(static_cast<float>(std::cos(w * ssb_phase_)),
                                  static_cast<float>(std::sin(w * ssb_phase_)));
    ssb_phase_ = (ssb_phase_ + 1) % SSB_ROT_LEN;
    std::complex<float> y;
    ssb_.push(c * std::conj(rot), y);
    float s = (y * rot).real();
    // AGC: level follows the signal (fast up, slow down); gain moves slowly toward the target.
    const float a = std::fabs(s);
    level_ = a > level_ ? level_ + 0.01f * (a - level_) : level_ + 0.0002f * (a - level_);
    if (level_ > 1e-9f) {
      const float want = std::clamp(TARGET_RMS * 1.4f / level_, MIN_GAIN, MAX_GAIN);
      gain_ += (want - gain_) * (want < gain_ ? 0.002f : 0.0002f);
    }
    const float v = std::clamp(s * gain_, -1.0f, 1.0f);
    return static_cast<int16_t>(std::lrint(v * 32767.0f));
  }

  Decimator d1_, d2_, d3_, ssb_;
  std::array<std::complex<float>, MIX_LEN> mix_{};
  std::array<std::complex<float>, AIRSPY_MIX_LEN> mix_a_{};
  int mix_pos_ = 0;
  int mix_a_pos_ = 0;
  int ssb_phase_ = 0;
  float level_ = 0;
  float gain_ = INITIAL_GAIN;
  std::atomic<bool> inverted_{false};
};

}  // namespace js8sdr
