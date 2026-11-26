// Host tests for the RTL-SDR SSB receiver: g++ -std=c++17 -O2 -I../../main/cpp ssb_rx_test.cpp
#include "ssb_rx.hpp"

#include <cstdio>
#include <chrono>
#include <cstdlib>

using namespace js8sdr;

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { std::printf("FAIL %s:%d: ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); ++failures; } } while (0)

// IQ (as the dongle sends it) for a set of complex tones at baseband frequencies (Hz, relative to
// the tuned centre), each with an amplitude out of 1.
static std::vector<uint8_t> iq_tones(const std::vector<std::pair<double, double>>& tones, double seconds, unsigned seed = 1) {
  const size_t n = static_cast<size_t>(seconds * INPUT_RATE);
  std::vector<uint8_t> out(n * 2);
  srand(seed);
  for (size_t k = 0; k < n; ++k) {
    double i = 0, q = 0;
    for (auto [f, a] : tones) {
      const double ph = 2 * M_PI * f * k / INPUT_RATE;
      i += a * std::cos(ph);
      q += a * std::sin(ph);
    }
    // Dither so the 8-bit quantisation behaves like a real dongle's noise.
    const double di = (rand() / (double)RAND_MAX - 0.5), dq = (rand() / (double)RAND_MAX - 0.5);
    out[2 * k] = static_cast<uint8_t>(std::clamp(std::lround(127.4 + 127 * i + di), 0L, 255L));
    out[2 * k + 1] = static_cast<uint8_t>(std::clamp(std::lround(127.4 + 127 * q + dq), 0L, 255L));
  }
  return out;
}

// Power of a tone in the audio (Goertzel), skipping the first `skip` samples (filters and AGC settling).
static double tone_power(const std::vector<int16_t>& a, double hz, size_t skip) {
  const double w = 2 * M_PI * hz / OUTPUT_RATE;
  double s1 = 0, s2 = 0;
  const double c = 2 * std::cos(w);
  size_t n = 0;
  for (size_t k = skip; k < a.size(); ++k, ++n) {
    const double s0 = a[k] / 32768.0 + c * s1 - s2;
    s2 = s1; s1 = s0;
  }
  return (s1 * s1 + s2 * s2 - c * s1 * s2) / (n * n);
}

static double db(double p) { return 10 * std::log10(p + 1e-30); }

int main() {
  // Plans.
  {
    auto p = plan_tuning(7078000, 0, false);  // V3, 40 m: direct sampling Q, below Nyquist
    CHECK(p.ok && p.direct_sampling == 2 && !p.inverted && p.center_hz == 7078000 - 60000, "40m V3 %u", p.center_hz);
    p = plan_tuning(21078000, 0, false);       // V3, 15 m: alias at 28.8 - 21.078 MHz, mirrored
    CHECK(p.ok && p.inverted && p.center_hz == 28800000 - 21078000 + 60000, "15m V3 %u", p.center_hz);
    p = plan_tuning(7078000, 0, true);         // V4 (upconverter): no direct sampling
    CHECK(p.ok && p.direct_sampling == 0 && !p.inverted && p.center_hz == 7018000, "40m V4");
    p = plan_tuning(50318000, 0, false);       // 6 m: tuner
    CHECK(p.ok && p.direct_sampling == 0 && p.center_hz == 50258000, "6m");
    p = plan_tuning(7078000, 1, false);        // direct sampling forced off
    CHECK(p.ok && p.direct_sampling == 0, "ds off");
    p = plan_tuning(30000, 0, false);
    CHECK(!p.ok, "below the offset");
  }

  const size_t settle = OUTPUT_RATE * 2;  // 2 s

  // USB tone 1000 Hz above the dial comes out at 1000 Hz; an LSB tone 1000 Hz below the dial is rejected.
  {
    SsbReceiver rx;
    std::vector<int16_t> audio;
    auto iq = iq_tones({{TUNE_OFFSET_HZ + 1000, 0.02}}, 4.0);
    rx.process(iq.data(), iq.size(), audio);
    CHECK(audio.size() == 4 * OUTPUT_RATE, "rate: %zu samples for 4 s", audio.size());
    const double p1000 = tone_power(audio, 1000, settle), p1500 = tone_power(audio, 1500, settle);
    CHECK(db(p1000) - db(p1500) > 40, "USB tone %.1f dB vs off-tone %.1f dB", db(p1000), db(p1500));

    SsbReceiver rx2;
    std::vector<int16_t> a2;
    auto both = iq_tones({{TUNE_OFFSET_HZ + 1000, 0.02}, {TUNE_OFFSET_HZ - 1000, 0.02}}, 4.0, 2);
    rx2.process(both.data(), both.size(), a2);
    // The LSB tone would land on 1000 Hz too; compare against the USB-only run.
    const double pboth = tone_power(a2, 1000, settle);
    CHECK(std::fabs(db(pboth) - db(p1000)) < 1.0, "LSB tone leaks into USB: %.2f vs %.2f dB", db(pboth), db(p1000));

    SsbReceiver rx3;
    std::vector<int16_t> a3;
    auto lsb = iq_tones({{TUNE_OFFSET_HZ - 1000, 0.02}, {TUNE_OFFSET_HZ + 2500, 0.0002}}, 4.0, 3);
    rx3.process(lsb.data(), lsb.size(), a3);
    const double leak = tone_power(a3, 1000, settle), weak = tone_power(a3, 2500, settle);
    CHECK(db(weak) - db(leak) > 10, "a weak USB tone stands 40 dB under a strong LSB one: %.1f vs leak %.1f", db(weak), db(leak));
  }

  // Signals well outside the passband (another station 20 kHz away, the DC spike) don't come through.
  {
    SsbReceiver rx;
    std::vector<int16_t> audio;
    auto iq = iq_tones({{TUNE_OFFSET_HZ + 1200, 0.002}, {TUNE_OFFSET_HZ + 20000, 0.2}, {0, 0.2}}, 4.0, 4);
    rx.process(iq.data(), iq.size(), audio);
    const double want = tone_power(audio, 1200, settle);
    double worst = 0;
    for (double f = 100; f < 5900; f += 50) if (std::fabs(f - 1200) > 100) worst = std::max(worst, tone_power(audio, f, settle));
    CHECK(db(want) - db(worst) > 20, "wanted %.1f dB, worst other %.1f dB", db(want), db(worst));
  }

  // Inverted (direct-sampling alias): the mirrored tone comes out where the radio would put it.
  {
    SsbReceiver rx;
    rx.set_inverted(true);
    std::vector<int16_t> audio;
    auto iq = iq_tones({{-(TUNE_OFFSET_HZ + 1500.0), 0.02}}, 4.0, 5);
    rx.process(iq.data(), iq.size(), audio);
    CHECK(db(tone_power(audio, 1500, settle)) - db(tone_power(audio, 2000, settle)) > 40, "inverted tone");
  }

  // AGC: a weak and a strong signal both come out at a usable level, without clipping.
  for (double amp : {0.0005, 0.3}) {
    SsbReceiver rx;
    std::vector<int16_t> audio;
    auto iq = iq_tones({{TUNE_OFFSET_HZ + 1000, amp}}, 8.0, 6);
    rx.process(iq.data(), iq.size(), audio);
    double sum = 0; int peak = 0; size_t n = 0;
    for (size_t k = OUTPUT_RATE * 6; k < audio.size(); ++k, ++n) { sum += (double)audio[k] * audio[k]; peak = std::max(peak, std::abs((int)audio[k])); }
    const double rms = std::sqrt(sum / n) / 32768.0;
    CHECK(rms > 0.03 && rms < 0.3 && peak < 32767, "AGC amp %.4f -> rms %.3f peak %d", amp, rms, peak);
  }

  // Processing time: 10 s of IQ should take far less than 10 s.
  {
    SsbReceiver rx;
    std::vector<int16_t> audio;
    auto iq = iq_tones({{TUNE_OFFSET_HZ + 1000, 0.02}}, 2.0, 7);
    auto t0 = std::chrono::steady_clock::now();
    for (int r = 0; r < 5; ++r) rx.process(iq.data(), iq.size(), audio);
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("10 s of IQ processed in %.3f s on this host\n", s);
    CHECK(s < 5.0, "too slow: %.2f s", s);
  }

  if (failures) { std::printf("%d failure(s)\n", failures); return 1; }
  std::printf("ssb_rx tests passed\n");
  return 0;
}
