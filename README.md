# JS8-Tactical

JS8-Tactical is an Android app for JS8 digital radio messaging, built for field and
emergency use: weak-signal HF traffic on a phone or tablet, with tools that make a busy
net easier to follow and use. It is a fork of
[JS8Android](docs/UPSTREAM-JS8ANDROID-README.md), the Android port of
[JS8Call](https://github.com/js8call/js8call), and stays on the air compatible with
standard JS8: everything it sends is ordinary JS8 that any JS8Call station can read.

**Status: early alpha (1.2.0-alpha.3).** Expect rough edges. Please send a diagnostic report
(Settings → Diagnostics → Share diagnostic report) after any crash, freeze or odd behaviour;
it is pre-addressed to js8tactical@proton.me.

## What it does

- **Traffic**: all messages rolled up by station, group and #tag, with incomplete messages
  marked and one-tap resend requests; waterfall with station tags; heartbeats, auto-replies
  and a relay finder that suggests who can pass a message on.
- **Digest**: a roll-up of what came in while you were away: needs and emergencies, messages
  for you, bulletins, nets and groups, who heard you. Export as text (reports only or the full
  raw log, optionally with every callsign removed).
- **Reports**: write and save long reports or forms (SITREP, SALUTE, SPOT, check-in, POSREP,
  weather and more), send at a chosen speed, or schedule them to go out automatically.
- **Nets**: the Net Switcher moves the radio between the nets you follow on a UTC schedule,
  with per-net speed and heartbeat settings (needs CAT rig control).
- **Map**: stations, paths, links between other stations and located reports; a propagation
  view with predicted coverage and solar data.
- **Team**: a roster of the stations you work with.
- **Optional on-device AI** (off by default): plain-English summaries of bulletins with
  citations back to the raw transmissions. The model is downloaded only if you choose to, runs
  entirely on the device, and can be deleted at any time.

Radio control: Hamlib over USB (many radios, e.g. Xiegu G90), rigctld over the network,
QMX and truSDX over USB serial, RTS/DTR PTT, and the BTECH UV-Pro over Bluetooth.

## Operating notes

- You are responsible for operating within your licence and local rules.
- Set your radio's transmit time-out timer. If Android stops the app in the middle of a
  transmission, a radio keyed over CAT stays keyed until its own timer releases it.
- Only your 4-character grid square goes out over the air.
- The app keeps receiving in the background when swiped away (Settings → General).
  Traffic ⋮ → Exit shuts everything down.

## Building

See [android/README.md](android/README.md). In short: Android SDK with NDK 28.2.13676358 and
CMake 3.22.1, then `cd android && ./gradlew assembleDebug`. The GitHub Actions workflow builds
every push to `master` and publishes it as the rolling `test-build` release. Pushing a version
tag (for example `v1.2.0-alpha.1`) builds a signed release APK and publishes it as a GitHub
release; the signing key is created once with `scripts/make-release-key.sh`.

APK names follow the app version: releases are `JS8-Tactical-<version>.apk` (for example
`JS8-Tactical-1.2.0-alpha.3.apk`, from tag `v1.2.0-alpha.3`; the tag must match `versionName` in
`android/app/build.gradle.kts`), and test builds are `JS8-Tactical-<version>-test.<build>.apk`
(for example `JS8-Tactical-1.2.0-alpha.3-test.199.apk`), where `<build>` is the versionCode.

## Licence and credits

JS8-Tactical is free software under the GNU General Public License v3 (see [LICENSE](LICENSE)).

- JS8Call by Jordan Sherer, KN4CRD, derived from WSJT-X (K1JT and the WSJT development group).
  JS8Call is not supported or endorsed by the WSJT-X development group.
- JS8Android by Punk Kaos and contributors.
- Third-party components: Hamlib (LGPL 2.1), FFTW3 (GPL 2+), Oboe (Apache 2.0), MapLibre
  Native (BSD 2-clause), llama.cpp (MIT), the SBC codec from HTCommander (Apache 2.0),
  AndroidX and Material Components (Apache 2.0). Map data © OpenStreetMap contributors (ODbL),
  served by OpenFreeMap. Optional AI models: Qwen2.5 (Apache 2.0).

The upstream README, with the history of JS8Call, is kept in
[docs/UPSTREAM-JS8ANDROID-README.md](docs/UPSTREAM-JS8ANDROID-README.md).
