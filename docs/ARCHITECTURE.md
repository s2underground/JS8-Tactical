# Architecture

A high-level map of JS8-Tactical: the parts, how a message flows through them, and where the trust
boundaries are. For what each boundary defends against, see [SECURITY_MODEL.md](SECURITY_MODEL.md).

## The pieces

```
 radio audio ──► Oboe/AAudio ──► JS8Core (C++) ──► JNI bridge ──► JS8EngineService (Kotlin)
                                   ▲  decoder, encoder,              │  foreground service:
 radio audio ◄── Oboe/AAudio ◄─────┘  frame packing              │  receive, transmit, PTT,
                                                                   │  auto-replies, relays,
 rig control ◄── Hamlib (USB serial) / rigctld (TCP) /             │  store-and-forward,
                 TruSDX, QMX, UV-Pro (Bluetooth), RTS PTT ◄────────┤  heartbeats, Net Switcher
                                                                   │
 SDR dongles ──► libusb + rtl-sdr / airspyhf (:sdr) ───────────────┤
                                                                   ▼
                              AppBroadcasts (in-process events) ◄──► UI: MainActivity, fragments,
                                                                        ViewModels (Traffic,
                                                                        Reports, Digest, Map,
                                                                        Nets, Team, Settings)
                                                                   │
   Room database, app-private files ◄──────────────────────────────┤
   Isolated :llm process (llama.cpp) ◄── AIDL, model fd ───────────┤  optional AI summaries
   VOACAP engine (Rust, :voacap) ◄── C entry point ────────────────┤  propagation heatmap
   HTTPS: NOAA SWPC, OpenFreeMap, GitHub data, Hugging Face ◄──────┤
   UDP: ATAK (127.0.0.1 / TAK mesh multicast), PSK Reporter ◄──────┘  optional outputs
```

### Repository layout

| Path | What it is | Language |
|---|---|---|
| `core/` | **JS8Core**, the JS8 protocol engine from upstream (git submodule, never edited here): DSP, decoder, LDPC, frame packing/unpacking, varicode, TX scheduling | C++20 |
| `adapters/android/jni/` | The bridge between JS8Core and Kotlin: engine JNI, USB and Bluetooth serial bridges for Hamlib, text conversion (decoded bytes reach Java as well-formed, filtered strings), Kotlin wrappers (`JS8Engine`, `UsbSerialBridge`) | C++, Kotlin |
| `android/js8core-lib/` | Android library that builds the above into `libjs8core-jni.so` with Oboe for audio | Gradle/CMake |
| `android/app/` | The app | Kotlin |
| `android/llm/` | Optional on-device language model: llama.cpp (`vendor/llama.cpp`) in an **isolated process** behind an AIDL interface | Kotlin, C++ |
| `android/sdr/` | Receive-only SDR support: RTL-SDR and Airspy HF+ through libusb, SSB demodulation to audio | Kotlin, C, C++ |
| `android/voacap/` | VOACAP propagation predictions: the hfcast engine (`vendor/hfcast-engine`) through a small Rust crate with a C entry point | Rust, C++, Kotlin |
| `android/hamlib/` | Script and patches that build Hamlib for Android (pinned commit) | Shell |
| `tools/` | Fuzz harnesses (`tools/fuzz`), host tests of app-side native code, propagation data tools, MobSF summary | C, Kotlin, Python |
| `scripts/` | Build, test, fuzz and SBOM scripts used locally and in CI | Shell, Python |

### Inside the app (`android/app/src/main/java/com/js8call/example/`)

| Package | Role |
|---|---|
| `service/` | `JS8EngineService` (the receiver and transmitter, rig control, PTT bookkeeping) and the pure logic pulled out of it so it can be tested and fuzzed: `RelayProtocol`, `RelayAssembler`, `MsgAssembler`, `AutoReply`, `OnAirText`, `PttLedger`, `TxProgress`, rig sessions (`RigCtlClient`, `TruSdxSerialSession`, `QmxCatSession`, `UvProSession`, `SdrSession`) |
| `ui/` | Screens and ViewModels; the send queue (`TransmitViewModel`, `TxQueueRules`) |
| `data/` | Room database of messages |
| `storeforward/`, `rollup/`, `digest/`, `reports/`, `nets/`, `team/`, `net/` | Features built on received traffic: held messages, merging repeats, the Digest, report forms, Net Switcher schedules, team roster, net rosters |
| `prop/`, `interference/` | Propagation model and space weather; interference watch from the spectrum |
| `ai/` | Optional AI summaries: model download and SHA-256 check, prompts, grounding checks |
| `atak/`, `network/` | Outputs: CoT to ATAK over UDP, PSK Reporter spots |
| top level | `MainActivity`, `AppBroadcasts` (in-process event bus), `CrashReporter` and `DiagnosticsPrivacy` (diagnostics with personal details scrubbed) |

## How a message flows

**Receiving.** Audio from the radio's sound card (through Oboe), or demodulated audio from an SDR
(through `:sdr`), is fed to JS8Core, which finds signals, decodes frames and unpacks their text. The JNI bridge turns
each decode into a Kotlin event for `JS8EngineService`, which logs it, assembles multi-frame
messages, applies store-and-forward and relay handling, decides on automatic replies (only when
the operator has enabled them), stores messages in the database, and publishes each event on
`AppBroadcasts`, where the ViewModels pick it up for the screens.

**Transmitting.** The operator writes a message or report; it goes into the send queue
(`TransmitViewModel`), which orders operator sends ahead of automatic replies. When the slot comes
the service hands the text to JS8Core, which packs it into frames and produces the audio. The
service keys the transmitter through the configured rig control (or VOX), plays the audio, and
unkeys, recording every key and release in `PttLedger` so shutdown and dropouts can always unkey
the radio. Only the 4-character grid square is ever handed to the engine.

**Automation.** Heartbeats, scheduled reports, auto-replies, relays and the Net Switcher run in the
service, each behind a setting the operator controls. Nothing received over the air can turn them
on.

## Processes and trust boundaries

- **Main app process**: UI and the service. Holds the radio, USB, Bluetooth, microphone and the
  operator's data.
- **`:llm` isolated process**: llama.cpp parses the model file and runs inference with no
  permissions and no access to the app's files, network or devices. It receives a read-only file
  descriptor of the already-verified model and bulletin text; it returns text only, size-capped and
  cleaned before display.
- **Native code inside the main process**: JS8Core, the JNI bridge, Hamlib, libusb/SDR drivers, the
  VOACAP engine. These parse untrusted input (received audio and frames, radio replies, prediction
  requests), so they are fuzzed and tested under sanitizers ([ASSURANCE_CASE.md](ASSURANCE_CASE.md)).
- **Network**: HTTPS only for web requests (cleartext refused, system CAs only); local UDP/TCP for
  ATAK, PSK Reporter and rigctld.

## Building

Gradle builds the app and its modules; CMake builds the native code (JS8Core, the JNI bridge,
llama.cpp, the SDR drivers), cargo builds the VOACAP crate, and a script builds Hamlib. CI
(`.github/workflows/android-build.yml`) builds every push and pull request, runs the Kotlin and
native tests (also under ASan/UBSan), CodeQL, lint and coverage, and publishes signed APKs with an
SBOM and a build-provenance attestation. See [CONTRIBUTING.md](../CONTRIBUTING.md) to build and
test locally.
