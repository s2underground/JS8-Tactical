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
| Space-weather data (NOAA SWPC), map tiles (OpenFreeMap) | Those servers, over HTTPS only (cleartext HTTP and user-installed certificate authorities are refused) | `SpaceWeatherRepository.kt`, MapLibre | Malformed data from a compromised server |
| Optional AI model files (Hugging Face, over HTTPS) | The model publisher and host | `AiModels.kt` (pinned revision, SHA-256 checked before use), then the llama.cpp GGUF loader (C++) **in an isolated process** (`LlmService`) | A tampered model file parsed by native code: contained by the isolated process |

## What can make the radio transmit

Only these: the operator sending a message or report; scheduled reports the operator set up;
heartbeats when enabled; auto-replies when auto-reply is on (heartbeat and SNR replies, and
answers to queries for messages the station holds). The Net Switcher only retunes the radio. The optional AI feature never sends anything: its output is only shown on
screen, with links back to the original transmissions.

## Data kept on the device

The callsign, grid square, settings, received traffic, stored and posted messages, and logs live in
the app's private storage. Of your location, only the 4-character grid square is ever transmitted. Diagnostic reports
leave out callsigns, cut grid squares to 4 characters and include recent received traffic. Optional network features are
off by default: PSK Reporter spotting (UDP) and the ATAK feed (unauthenticated UDP, unicast or
multicast on the local network, readable by any device on that network).

## Build and release

- Every push to `master` is built and tested by GitHub Actions; release APKs are built from tags in
  the same workflow and signed with the project's release key (fingerprint in
  [SECURITY.md](../SECURITY.md)).
- Automated checks: Kotlin unit tests and native core tests on every push, the native tests a second
  time under AddressSanitizer, UndefinedBehaviorSanitizer and LeakSanitizer; CodeQL static analysis
  (security-extended queries) of the native code and of the app's Java/Kotlin; Android lint, which fails on any new finding; OpenSSF Scorecard; Dependabot for
  GitHub Actions and Gradle dependencies; weekly fuzzing of the JS8 frame decoders
  (`scripts/fuzz-core.sh`, libFuzzer with AddressSanitizer and UndefinedBehaviorSanitizer);
  weekly fuzzing of the Kotlin code that reads received text (`scripts/fuzz-kotlin.sh`, Jazzer),
  with property checks: a message held for one station is only handed to that station, and the
  diagnostics scrub leaves no callsign, fine grid characters or IP address behind; unit-test line coverage
  (JaCoCo) reported on every build.
- Third-party native code is pinned: JS8Core, llama.cpp and the VOACAP engine (hfcast) as git
  submodules at fixed commits, Hamlib at a fixed commit in the build workflow.
- Weekly fuzzing of the VOACAP engine's entry points (`scripts/fuzz-voacap.sh`).
- Every published APK (releases and the rolling test build) comes with a CycloneDX software bill
  of materials (`.cdx.json`: the Gradle libraries resolved for that build, the native code built
  from source at its pinned commit, and the APK's SHA-256), and GitHub signs a build-provenance
  attestation for both files, tying them to the workflow run and commit that produced them.

## The AI engine runs isolated

llama.cpp (the optional on-device language model) runs in its own process, declared
`android:isolatedProcess="true"` (`android/llm`, `LlmService`). Android gives that process no
permissions and its own sandbox: it can't open the app's files, reach the network, USB, the radio
or PTT, or read the app's settings. The app opens the model file (after its SHA-256 check) and
hands the engine a read-only descriptor; the engine reads and maps the model through it. Only text
comes back over Binder, capped in length and stripped of control characters before display. If the
engine crashes, or a booby-trapped model file takes it over, it stays inside that process: the
summary shows as unavailable and the receiver keeps running. Checked on a Galaxy Tab Active5 Pro
(Android 16) before the move: the isolated process could read and map a passed descriptor, could
not open files by name, and could not list the app's data folder.

## Known gaps (being worked on)

1. **Older lint findings**: about 240 style and performance findings (unused resources, Kotlin
   extension suggestions and similar, none security-related) are recorded in
   `android/app/lint-baseline.xml` and being worked off. New findings fail CI.
2. **No independent audit yet.** One is planned; the report will be published in this repository.
