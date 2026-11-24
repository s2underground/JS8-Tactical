# JS8-Tactical

[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/17055/badge)](https://www.bestpractices.dev/projects/17055)

JS8-Tactical is an Android app for JS8 digital radio messaging, built for field and
emergency use: weak-signal HF traffic on a phone or tablet, with tools that make a busy
net easier to follow and use. It is a fork of
[JS8Android](docs/UPSTREAM-JS8ANDROID-README.md), the Android port of
[JS8Call](https://github.com/js8call/js8call), and stays on the air compatible with
standard JS8: everything it sends is ordinary JS8 that any JS8Call station can read.

**Status: early alpha (1.2.0-alpha.3).** Expect rough edges. Please report bugs (see below).

**Built with AI:** JS8-Tactical's own code is written by an AI model (Anthropic's Claude) under
the maintainer's direction. See [How this software is made](#how-this-software-is-made) for what
that means and how the code is checked.

## How this software is made

We want you to be able to judge this for yourself, so here is exactly how JS8-Tactical is built.

**Who writes the code.** JS8-Tactical forked from JS8Android in November 2026. Since then, the
code changes in this repository have been written by Claude, an AI model made by Anthropic, working
at the maintainer's direction. Every one of those commits is labelled: its author is "Claude", or it
carries a `Co-Authored-By: Claude` line, so the git history shows exactly which changes the AI
wrote.
The code this project builds on (JS8Call, JS8Android, the JS8Core library, Hamlib, llama.cpp and the
other components listed under [Licence and credits](#licence-and-credits)) comes from those
projects and their own developers.

**What the maintainer does.** Decides what gets built and how it must behave, tests builds on real
phones, tablets and radios, field-tests them on the air (including on the weekly GhostNet net),
and decides what goes into each release.

**How the code is checked.**

- All of the source code is public, and every release APK is built by GitHub Actions from the
  tagged source, not on a personal computer. How to verify a download: [SECURITY.md](SECURITY.md).
- Automated tests (Kotlin unit tests and native core tests) run on every change.
- Static analysis: CodeQL on the native code and Android lint on the app.
- The JS8 frame decoders, which handle everything received over the air, are fuzz-tested with
  sanitizers ([scripts/fuzz-core.sh](scripts/fuzz-core.sh)).
- Dependencies are monitored for known vulnerabilities, and the project's practices are scored
  by OpenSSF Scorecard.
- What the app trusts, and the known gaps, are written down in
  [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md).
- An independent security review is planned; its report will be published here.

**Not the same as the AI feature in the app.** This section is about how the code is written. The
app's optional on-device AI (see below) is a separate feature: off by default, downloaded only if
you choose, and it never transmits anything.

If you find a problem, please report it. Security problems: see [SECURITY.md](SECURITY.md).

## Install

1. Download the APK from the latest release on the [Releases](../../releases) page: the file named
   `JS8-Tactical-<version>.apk`. Releases marked *Latest test build* are untested daily builds.
2. Open it on the phone or tablet (Android 8.0 or newer) and allow installs from that source
   when Android asks.
3. New versions install over the old one; settings and logs are kept.

## Reporting bugs

Open an [issue](../../issues/new/choose), or send a diagnostic report from the app
(Settings → Diagnostics → Share diagnostic report), which is pre-addressed to
js8tactical@proton.me. A diagnostic report helps most: it has the app version, your settings (without your callsign
or grid) and the recent log, which includes traffic heard on the air.

## What it does

- **Traffic**: all messages rolled up by station, group and #tag, with incomplete messages
  marked and one-tap resend requests; waterfall with station tags; heartbeats, auto-replies
  and a relay finder that suggests who can pass a message on.
- **Store and forward**, compatible with desktop JS8Call: stations can leave messages with you
  for others (`MSG TO:`) and collect what you hold (`QUERY MSGS`, `QUERY MSG n`); post your own
  reports for pickup by a group or callsign. Traffic → Inbox shows For me / Holding / Posted.
- **Digest**: a roll-up of what came in while you were away: needs and emergencies, messages
  for you, bulletins, nets and groups, who heard you. Export as text (reports only or the full
  raw log, optionally with every callsign removed).
- **Reports**: write and save long reports or forms (SITREP, SALUTE, SPOT, check-in, POSREP,
  weather and more), send at a chosen speed, or schedule them to go out automatically.
- **Nets**: the Net Switcher moves the radio between the nets you follow on a UTC schedule,
  with per-net speed and heartbeat settings (needs CAT rig control).
- **Map**: stations, paths, links between other stations and located reports; a propagation
  view with predicted coverage and solar data.
- **Team**: a roster of the stations you work with: when each was last heard, their latest
  check-in or SITREP, and a per-band estimate of whether you can reach them now.
- **Optional on-device AI** (off by default): plain-English summaries of bulletins with
  citations back to the raw transmissions. The model is downloaded only if you choose to, runs
  entirely on the device, and can be deleted at any time.

Radio control: Hamlib over USB (many radios, e.g. Xiegu G90), rigctld over the network,
QMX and truSDX over USB serial, RTS/DTR PTT, and the BTECH UV-Pro over Bluetooth.

## Operating notes

- You are responsible for operating within your licence and local rules.
- Set your radio's transmit time-out timer. If Android stops the app in the middle of a
  transmission, a radio keyed over CAT stays keyed until its own timer releases it.
- With auto-reply on, the station answers by itself (heartbeat and SNR replies, message
  requests for anything you hold or have posted). Make sure unattended operation is allowed
  where and on the frequency you operate.
- Only your 4-character grid square goes out over the air.
- The app keeps receiving in the background when swiped away (Settings → General).
  Traffic ⋮ → Exit shuts everything down.

## Security

- Report security problems privately: see [SECURITY.md](SECURITY.md) (GitHub private reporting
  or js8tactical@proton.me).
- Check that an APK really came from this project: compare its signing certificate with the
  fingerprint in [SECURITY.md](SECURITY.md#verifying-a-download).
- What the app trusts and the known gaps: [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md).

## Contributing

Bug reports, on-air testing and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md)
for how changes are proposed, tested and reviewed.

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
