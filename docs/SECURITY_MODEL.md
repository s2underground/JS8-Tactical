# JS8-Tactical security model

What the app trusts, where untrusted input comes in, and the known gaps. Written for users who
want to judge the risk and for anyone reviewing or auditing the code. Last reviewed for
1.2.0-alpha.3 (November 2026).

## What JS8 itself does not provide

JS8 is a plain-text amateur radio mode. It has **no encryption and no authentication**: anyone can
read every message, and anyone with a transmitter can send a message claiming any callsign.
JS8-Tactical does not change that (amateur rules forbid obscuring the meaning of messages).
Treat every received message as unverified, whatever callsign it shows.

## Where untrusted input enters

| Input | Who controls it | Handled by | Main risks |
| --- | --- | --- | --- |
| Received JS8 frames (audio from the radio) | Anyone transmitting on the frequency | JS8Core decoder and frame unpackers (C++, `core/`), JNI bridge (`adapters/android/jni`), then Kotlin parsing, rollup, digest, store-and-forward | Memory corruption in native code, crashes, crafted text driving auto-replies or stored messages |
| Store-and-forward traffic (`MSG TO:`, `QUERY MSGS`) | Any station | Kotlin store-and-forward code | Stored text from strangers; replies sent automatically when auto-reply is on |
| rigctld connection (TCP, local network) | Whoever runs or spoofs the rigctld server | `RigCtlClient.kt` | Plain TCP with no authentication: a device on the same network could impersonate the radio |
| USB serial and Bluetooth radio links | Physical access / paired device | Hamlib (C), `UvProSession.kt` | Malformed replies from the radio or a rogue Bluetooth device |
| Space-weather data (NOAA SWPC), map tiles (OpenFreeMap) | Those servers, over HTTPS | `SpaceWeatherRepository.kt`, MapLibre | Malformed data from a compromised server |
| Optional AI model files (Hugging Face, over HTTPS) | The model publisher and host | `AiModels.kt`, llama.cpp GGUF loader (C++) | A tampered model file parsed by native code |

## What can make the radio transmit

Only these: the operator sending a message or report; scheduled reports the operator set up;
heartbeats when enabled; auto-replies when auto-reply is on (heartbeat and SNR replies, and
answers to queries for messages the station holds). The Net Switcher only retunes the radio. The optional AI feature never sends anything: its output is only shown on
screen, with links back to the original transmissions.

## Data kept on the device

The callsign, grid square, settings, received traffic, stored and posted messages, and logs live in
the app's private storage. Of your location, only the 4-character grid square is ever transmitted. Diagnostic reports
leave out the callsign and grid but include recent received traffic. Optional network features are
off by default: PSK Reporter spotting (UDP) and the ATAK feed (unauthenticated UDP, unicast or
multicast on the local network, readable by any device on that network).

## Build and release

- Every push to `master` is built and tested by GitHub Actions; release APKs are built from tags in
  the same workflow and signed with the project's release key (fingerprint in
  [SECURITY.md](../SECURITY.md)).
- Automated checks: Kotlin unit tests and native core tests on every push; CodeQL static analysis
  of the native code; Android lint (report only for now); OpenSSF Scorecard; Dependabot for
  GitHub Actions and Gradle dependencies; weekly fuzzing of the JS8 frame decoders
  (`scripts/fuzz-core.sh`, libFuzzer with AddressSanitizer and UndefinedBehaviorSanitizer).
- Third-party native code is pinned: JS8Core and llama.cpp as git submodules at fixed commits,
  Hamlib at a fixed commit in the build workflow.

## Known gaps (being worked on)

1. **AI model downloads are not pinned to a checksum.** They come over HTTPS from Hugging Face's
   `main` branch, so a change upstream would be accepted. Planned: pin a revision and verify the
   SHA-256 before use.
2. **Cleartext HTTP is allowed** by the network security config. The app makes no HTTP requests
   (rigctld uses a raw TCP socket, which this setting does not affect), so this can be turned off.
3. **Test builds are debuggable**, which lets anyone with USB debugging access to the device read
   the app's data. Release builds are not.
4. **Android backup is allowed** (`allowBackup`), so app data can be copied by device backup.
5. **Compiler warnings**: about 57 Kotlin warnings, mostly deprecated Android APIs, are being worked
   through.
6. **No independent audit yet.** One is planned; the report will be published in this repository.
