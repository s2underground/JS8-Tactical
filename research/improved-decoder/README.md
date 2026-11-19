# JS8Call-improved decoder in JS8Core: port and bench results

Status (Nov 19, 2026): **ported and measured; not shipped.** The app keeps
JS8Core's standard decoder (`legacy_decode`).

## What was done

The newest desktop decoder (JS8Call-improved commit
`2c13392b87db1071acb920c64063246637b987eb`, Nov 17, 2026: soft combining of
repeats, pilot-aided coherent likelihoods, whitening, LDPC feedback and rescue,
decoder-aided re-demodulation, frequency/timing tracking, successive
interference cancellation, deep search) was compiled into JS8Core **unchanged**:

- `tools/js8i/sync_from_upstream.sh` copies the upstream files and removes only
  the Qt thread wrapper (`Worker`, `Public Interface - Decoding`) from `JS8.cpp`.
- Small stand-ins replace Qt logging (compiled out), FFTW (on pocketfft, which
  JS8Core already uses), and three Boost pieces (CRC-12, constexpr round, the
  sync-candidate multi-index container).
- `improved_decode()` replaces upstream's Worker: one persistent decoder per
  submode, upstream's optional-work budget with a deadline, upstream events
  converted to JS8Core events.
- `run_decoder()` picks the decoder from a process-wide switch; the app side
  (`app-decoder-switch.patch` + the two `.snippet` files) adds a Settings switch
  "Experimental decoder (JS8Call-improved)" through a JNI call.

It builds for Android (NDK 26, arm64/armv7) and on the host; JS8Core's existing
round-trip and engine smoke tests still pass.

## Why it is not shipped

`tools/decoder_compare.cpp` (in the patch) feeds identical signals to both
decoders, each trial in a fresh thread (no carried-over history):

- **Upstream's recorded test WAVs (`media/tests`, 9 files):** both decoders find
  exactly the same 28 frames. The improved decoder takes about 2x the time.
- **White noise, 40 trials per point (`bench-awgn-40-trials.txt`):** the 50%
  points are the same within a fraction of a dB in Normal, Fast, Turbo and
  Slow. With drift, late start and two- or three-station overlaps, it is equal
  or slightly worse. Repeating a frame twice at the weakest levels did not
  trigger soft combining: upstream merges two receptions only when their rough
  bit patterns already agree. No false decodes from either decoder.
- **CPU:** about 2x per decode pass (Normal, 1000–2000 Hz: ~120 ms vs ~57 ms on
  the build host). On a phone that roughly doubles battery use while receiving.

The upstream changes may still help on real HF paths (fading, phase noise,
crowded bands) that these tests don't model. The way to find out is on-air A/B
use, which the Settings switch would allow.

## To use it

JS8Core is a submodule pointing at `JS8Call-improved/JS8Core`, which this
project can't push to. Either:

1. offer the JS8Core patch upstream as a pull request, or
2. fork JS8Core, point the submodule at the fork, and apply the patches:

```
cd core && git apply ../research/improved-decoder/js8core-improved-decoder.patch
tools/js8i/sync_from_upstream.sh /path/to/JS8Call-improved   # at the commit above
cd .. && git apply research/improved-decoder/app-decoder-switch.patch
# then paste the two .snippet blocks into preferences.xml (before the ATAK
# category) and strings.xml (before </resources>)
```

Bench on the host:

```
cmake -S core -B build -DCMAKE_BUILD_TYPE=Release -DEIGEN3_INCLUDE_DIR=$PWD/vendor
make -C build js8core-decoder-compare
build/js8core-decoder-compare 40 ABCE          # synthetic
build/js8core-decoder-compare /path/to/JS8Call-improved/media/tests/*.wav
```
