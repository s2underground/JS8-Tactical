# Contributing to JS8-Tactical

Thanks for helping. Bug reports, testing on real radios, documentation and code are all welcome.

## Reporting bugs and asking for features

- Bugs: open an [issue](../../issues/new/choose). A diagnostic report from the app
  (Settings → Diagnostics → Share diagnostic report) helps most.
- Features: open an issue describing what you want to do on the air and why.
- Security problems: **do not** open a public issue; see [SECURITY.md](SECURITY.md).

Issues and pull requests are in English.

## Changing the code

1. Fork the repository and create a branch from `master`.
2. Make the change. Keep it focused: one feature or fix per pull request.
3. Build and run the tests (below). Test on a real device and radio when the change touches
   receiving, transmitting or rig control, and say in the pull request what you tested.
4. Open a pull request against `master`. GitHub Actions builds it and runs the tests; the
   maintainer reviews and merges.

### Requirements for a pull request

- **Tests:** new functionality and bug fixes come with automated tests where the code can be
  tested off the device (Kotlin unit tests in `android/app/src/test`, native tests in `core/`). A
  pull request that adds a major feature without tests needs a reason in its description.
- **Stays compatible with standard JS8:** everything the app transmits must remain ordinary JS8
  that any JS8Call station can decode.
- **No new warnings:** the Kotlin build has no compiler warnings and must stay that way. Android lint
  fails on any finding not already recorded in `android/app/lint-baseline.xml`; fix new findings rather
  than adding them to the baseline.
- **No secrets:** never commit keystores, passwords or API keys.
- **Licence:** contributions are accepted under the GNU GPL v3, the project's licence.
- **AI-assisted contributions** are welcome, as long as you say so in the pull request and you
  have built and tested the change yourself. Most of this project's own code is written with an AI
  assistant (see "How this software is made" in the [README](README.md)), so the same standard of
  tests and on-air checks applies to everyone.

## Building and testing

See [android/README.md](android/README.md) for the full setup. In short:

```
# Android app (Android SDK with NDK 28.2.13676358 and CMake 3.22.1)
cd android
./gradlew assembleDebug          # build
./gradlew test                   # Kotlin unit tests
./gradlew :app:createDebugUnitTestCoverageReport  # unit-test coverage (app/build/reports/coverage)
./gradlew :app:lintDebug         # Android lint

# Native JS8 core (cmake, a C++20 compiler, Eigen)
scripts/test-core.sh             # native unit tests
scripts/fuzz-core.sh 300         # fuzz the frame decoders for 5 minutes (needs clang)

# Kotlin parsers of received text (a JDK; downloads Kotlin and Jazzer, checksum-verified)
scripts/fuzz-kotlin.sh 120       # every Jazzer target for 2 minutes each
```

## Coding style

Follow the style of the surrounding code: Kotlin official style for the app, the existing
formatting in C++. Prefer small, readable functions and comments that explain *why*.

## Releases

Versions follow `MAJOR.MINOR.PATCH` with `-alpha.N` / `-beta.N` suffixes. A release is a git tag
(for example `v1.2.0-alpha.3`) that matches `versionName` in `android/app/build.gradle.kts`; GitHub
Actions builds, signs and publishes it with release notes describing the changes and any security
fixes.
