# JS8-Tactical

[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/17055/badge)](https://www.bestpractices.dev/projects/17055)

JS8-Tactical is an Android app for JS8 digital radio messaging, built for field and
emergency use: weak-signal HF traffic on a phone or tablet, with tools that make a busy
net easier to follow and use. It is a fork of
[JS8Android](docs/UPSTREAM-JS8ANDROID-README.md), the Android port of
[JS8Call](https://github.com/js8call/js8call), and stays on the air compatible with
standard JS8: everything it sends is ordinary JS8 that any JS8Call station can read.

**Status: early alpha (1.2.0-alpha.5).** Expect rough edges. Please report bugs (see below).

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
- Static analysis: CodeQL on the native code and on the app's Kotlin/Java, and Android lint on the app.
- The JS8 frame decoders, which handle everything received over the air, are fuzz-tested with
  sanitizers ([scripts/fuzz-core.sh](scripts/fuzz-core.sh)), and so is the app code that reads
  received text: store-and-forward commands, report lines, message rollup
  ([scripts/fuzz-kotlin.sh](scripts/fuzz-kotlin.sh)). Unit-test coverage is measured on every build.
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

### Updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from their GitHub
releases and tells you when a new version is out, so you don't have to check the Releases page.

[<img src="docs/images/badge_obtainium.png" alt="Get it on Obtainium" height="48">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.s2underground.js8tactical%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fs2underground%2FJS8-Tactical%22%2C%22author%22%3A%22S2%20Underground%22%2C%22name%22%3A%22JS8-Tactical%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22filterReleaseTitlesByRegEx%5C%22%3A%5C%22%5EJS8-Tactical%20v%3F%5B0-9%5D%5C%22%2C%5C%22versionDetection%5C%22%3Atrue%7D%22%7D)

The button adds JS8-Tactical to Obtainium with the right settings. It goes through Obtainium's
redirect page (apps.obtainium.imranr.dev), which has its own analytics. To add it by hand
instead, in Obtainium tap **Add App** and:

- **App source URL:** `https://github.com/s2underground/JS8-Tactical`
- **Include prereleases:** on. Every release so far is marked pre-release (alpha), so without
  this Obtainium finds nothing.
- **Filter release titles by regular expression:** `^JS8-Tactical v?[0-9]`. This skips the
  repository's other releases (*Latest test build*, the data releases and the build cache).

### Check that the app is genuine

Every release and test build is signed with the same key. Its SHA-256 certificate fingerprint is:

```
com.s2underground.js8tactical
AA:58:1D:27:25:C7:5F:08:5A:D4:23:1A:E0:A8:F3:0F:1B:0A:8A:D7:41:D8:B1:28:B3:E6:28:C0:14:4D:10:8B
```

[AppVerifier](https://github.com/soupslurpr/AppVerifier) (which works with Obtainium) checks an
installed or downloaded app against it: copy both lines and AppVerifier compares them. On a
computer, `apksigner verify --print-certs JS8-Tactical-<version>.apk` shows the same SHA-256.
Android itself refuses an update signed with a different key, so once the first install checks
out, later updates can't be swapped.

## Reporting bugs

Open an [issue](../../issues/new/choose), or send a diagnostic report from the app
(Settings → Diagnostics → Share diagnostic report), which is pre-addressed to
js8tactical@proton.me. A diagnostic report helps most: it has the app version, your settings (without your callsign;
grid squares cut to 4 characters) and the recent log, which includes traffic heard on the air.

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

**No radio? An RTL-SDR dongle receives JS8 on its own** (receive only): plug it into the tablet
with an OTG adapter, connect an HF antenna, and choose Settings → Radio → Rig Type → *RTL-SDR
dongle*. An RTL-SDR Blog V3 receives HF through direct sampling (best below 14.4 MHz; the bands
above work as mirrored signals, with more interference); a V4 through its built-in upconverter.
An **Airspy HF+** (Discovery or Dual Port) works the same way: Rig Type → *Airspy HF+*.
Suggested by doodlecomms ([issue #54](https://github.com/s2underground/JS8-Tactical/issues/54)).

**Xiegu X6100 / X6200:** use the radio's DEV port with a USB-A to USB-C cable and the OTG
adapter at the tablet end. Rig Control: Serial (Hamlib), model Xiegu X6200 (or X6100),
19200 baud, serial port Auto or Port 1 (CAT is on the second port of its dual serial chip).
If the radio keys but shows no power, see "Connecting your radio over USB" in the app's
Library.

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
- The "Get it on Obtainium" badge is from the [Obtainium](https://github.com/ImranR98/Obtainium)
  project (GPL-3.0).
- SDR receive: [librtlsdr](https://gitea.osmocom.org/sdr/rtl-sdr) (osmocom, GPL-2.0-or-later),
  [libairspyhf](https://github.com/airspy/airspyhf) (Airspy, BSD-3-Clause) and
  [libusb](https://libusb.info) (LGPL-2.1-or-later), built from source as submodules.
- VOACAP predictions (Prop tab): [hfcast](https://github.com/jonathanmcsweet/hfcast-engine), a Rust port of
  VOACAP by Jonathan McSweet (Apache-2.0), built from source as a submodule. VOACAP is the Voice of America
  Coverage Analysis Program by NTIA/ITS, maintained by Greg Hand; its Linux port voacapl is by J.A. Watson
  (CC0). Its coefficient data come from NTIA/ITS and from CCIR Reports 322 and 340, which the ITU publishes
  for implementers free from copyright assertions (see the submodule's NOTICE). No endorsement by NTIA/ITS
  or the US Government is implied. The monthly sunspot forecast is NOAA SWPC's (public domain). Credit to
  doodlecomms, who suggested it in issue #57.
- Approximate grid squares for US stations that send none: from the FCC Universal Licensing
  System amateur licence data and the US Census Bureau ZCTA gazetteer (both public domain). Only
  callsign and 4-character grid are kept; see [tools/callgrid](tools/callgrid/build_callgrid.py).

Ideas from the community: syncing the clock from GPS was suggested by doodlecomms
([issue #52](https://github.com/s2underground/JS8-Tactical/issues/52)), after their
[GPS UTC Clock](https://github.com/doodlecomms/gps_utc_clock) app, and installing with Obtainium
by the same author ([issue #53](https://github.com/s2underground/JS8-Tactical/issues/53)), as was
placing stations from their licence data ([issue #55](https://github.com/s2underground/JS8-Tactical/issues/55)).

The upstream README, with the history of JS8Call, is kept in
[docs/UPSTREAM-JS8ANDROID-README.md](docs/UPSTREAM-JS8ANDROID-README.md).
