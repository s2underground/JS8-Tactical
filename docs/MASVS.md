# OWASP MASVS self-assessment

A self-assessment of the JS8-Tactical Android app against the
[OWASP Mobile Application Security Verification Standard](https://mas.owasp.org/MASVS/) (MASVS
v2.1), at the **L1** testing profile (baseline security), with notes toward L2 where the app
already goes further. It is a self-assessment by the project, not an independent audit; the
evidence is in the code and CI so anyone can check it.

Assessed: November 2026, `master` after PR #79, with a MobSF static scan of test build 375.

**Context that shapes the answers.** JS8 is amateur radio: everything on the air is plain text by
law and anyone can transmit. The app has no user accounts, no server of its own, no payments and
no secrets of its own. What it must protect is the **radio** (never transmit or key the
transmitter unless the operator meant it), the **device** (nothing received can take over the
app), and the **operator's privacy** (precise location and personal details stay on the device
unless the operator shares them). Hard rule: the app must never stop an operator from doing
something; it may warn. See [SECURITY_MODEL.md](SECURITY_MODEL.md).

Status key: **Meets**, **Partly**, **N/A** (no such feature), **By design** (deliberately not
applied, with the reason).

## MASVS-STORAGE

| Control | Status | Evidence |
|---|---|---|
| STORAGE-1: stores sensitive data securely | **Meets** | Message history (Room database), settings, held messages, rosters and logs are in app-private internal storage (Android sandbox). No credentials exist to store. The release signing key is never in the app. AI models: Android 10+ in the app's scoped external folder; on Android 8/9, where other apps could write there, a verified copy in private storage is the only one loaded (`ai/AiModels.kt`, audit L6). |
| STORAGE-2: prevents leakage of sensitive data | **Meets** | No Android backup or device-to-device transfer (`allowBackup="false"`, `data_extraction_rules.xml` excludes every domain). Logs carry message text as a length only (`text='[25 chars]'`). Diagnostics reports and the error-report dialog are scrubbed before they can leave the device (callsigns → MY-STATION/STATION-n, grids cut to 4 characters, MGRS, coordinates, Bluetooth/IP/email addresses, message text), and are withheld if the scrub fails (`DiagnosticsPrivacy.kt`, fuzzed). Optional message logging to Downloads is off by default and is the operator's export. Clipboard copies only on the operator's tap. |

## MASVS-CRYPTO

| Control | Status | Evidence |
|---|---|---|
| CRYPTO-1: strong cryptography | **N/A** (integrity only) | The app implements no encryption: JS8 on amateur bands must be plain text, and there are no accounts or stored secrets. TLS comes from Android. Integrity checks use SHA-256 from the platform (`MessageDigest`): AI model files, cache keys for AI summaries (moved off SHA-1 in this assessment). |
| CRYPTO-2: key management | **N/A** in the app | The only key is the APK signing key, kept in GitHub secrets and offline backups, used only by CI; rotation and loss are covered in [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md). |

## MASVS-AUTH

| Control | Status | Evidence |
|---|---|---|
| AUTH-1, AUTH-2, AUTH-3 | **N/A** | No accounts, logins or remote sessions. Sensitive operations (transmitting) are always operator actions or automations the operator switched on; nothing remote can switch them on. |

## MASVS-NETWORK

| Control | Status | Evidence |
|---|---|---|
| NETWORK-1: secures all network traffic | **Meets** | `network_security_config.xml`: cleartext forbidden, system CAs only. Every web request is HTTPS (NOAA SWPC space weather, OpenFreeMap tiles, GitHub release data, Hugging Face models pinned to a revision and SHA-256). Protocols that have no TLS are local or standard: rigctld (TCP to the operator's own host), the ATAK feed (UDP to this device or the TAK mesh multicast), PSK Reporter (its UDP protocol; opt-in). rigctld replies are length- and time-bounded (`BoundedLine`). |
| NETWORK-2: identity pinning for developer-controlled endpoints | **N/A** | The project runs no servers; every endpoint belongs to a third party (NOAA, OpenFreeMap, GitHub, Hugging Face, PSK Reporter). Downloads that matter (models) are pinned by content hash instead, which is stronger than pinning a certificate. |

## MASVS-PLATFORM

| Control | Status | Evidence |
|---|---|---|
| PLATFORM-1: uses IPC securely | **Meets** | One exported component: `MainActivity` (launcher and USB-attach intents only). Service, FileProvider and in-process broadcasts are not exported; dynamic receivers use `RECEIVER_NOT_EXPORTED`; PendingIntents are `FLAG_IMMUTABLE`; the restart intent needs the app's own token (audit L5); the USB permission result is read from `UsbManager`, not the broadcast (PR #4). The AI engine is a separate `isolatedProcess` service with no permissions, reached over a typed AIDL interface whose output is size-capped and cleaned (PR #72). Test builds add `DebugDecodeReceiver`, protected by `android.permission.DUMP` (only the adb shell holds it). |
| PLATFORM-2: uses WebViews securely | **N/A** | No WebView. |
| PLATFORM-3: uses the user interface securely | **Meets** (L1) | No secrets are typed into the app. Screenshots are allowed and message notifications follow Android's lock-screen setting: the content is unencrypted radio traffic, and hiding it would get in the operator's way. |

## MASVS-CODE

| Control | Status | Evidence |
|---|---|---|
| CODE-1: requires an up-to-date platform | **By design** (Partly) | minSdk 26 (Android 8). Field tablets and older phones must keep working; the app runs on what the operator has. targetSdk 34; raising it is on the plan after UI testing on Android 15. |
| CODE-2: enforces app updates | **By design** | No forced updates or remote kill switch: the app has to work when nothing else does, and must never stop an operator. Updates are offered through GitHub releases and Obtainium; security fixes are announced in advisories and release notes. |
| CODE-3: no components with known vulnerabilities | **Meets** | Dependabot alerts and security updates on; every APK ships with a CycloneDX SBOM; native code pinned at commits (JS8Core, llama.cpp, hfcast, libusb, rtl-sdr, airspyhf, Hamlib); release checklist requires no open applicable alert. |
| CODE-4: validates and sanitizes untrusted input | **Meets** | Everything received over the air or the network is parsed by code that is unit-tested and fuzzed weekly: JS8 frame decoders (libFuzzer, ASan/UBSan), Kotlin parsers of received text including relays, stored messages, reports, rollup, auto-replies and on-air text (Jazzer, 9 targets with property checks), the VOACAP engine's C boundary (libFuzzer). The native tests also run under ASan/UBSan on every push. Decoded text reaches Java as well-formed, filtered strings (JNI, audit H1). CodeQL scans C/C++ and Kotlin. |

## MASVS-RESILIENCE

| Control | Status | Notes |
|---|---|---|
| RESILIENCE-1 to 4 (root detection, anti-tamper, obfuscation, anti-debugging) | **By design** (not at L1) | Not part of the L1 profile. The app is free software (GPLv3): its source is public and anyone may rebuild it, so obfuscation would hide nothing. It runs on rooted and custom devices on purpose. Release builds are signed and **not debuggable**; each APK has a build-provenance attestation so users can check it came from this repository's CI. |

## MASVS-PRIVACY

| Control | Status | Evidence |
|---|---|---|
| PRIVACY-1: minimizes access to sensitive data and resources | **Meets** | Permissions match features: microphone (receive audio), location (optional: GPS clock, own position on the map, and the grid when the operator taps Update), Bluetooth (radio links; scan flagged `neverForLocation`), USB host, notifications, storage only on Android ≤ 9 for the optional log. The AI engine process has no permissions at all. |
| PRIVACY-2: prevents identification of the user | **Meets** | Only the 4-character grid square is handed to the radio engine (`OnAirText.grid4`); what goes on the air otherwise is what the operator types. Diagnostics never carry the callsign, finer grid characters or message text. |
| PRIVACY-3: transparent about data collection | **Meets** | No analytics, trackers or crash upload: MobSF found 0 of 432 known trackers. Everything that leaves the device does so on the operator's action (share diagnostics, PSK Reporter on, ATAK feed on, model download) and is described in the README and SECURITY_MODEL.md. |
| PRIVACY-4: offers user control over their data | **Meets** | Data lives on the device only; the operator can export (message log, digest exports), share, or delete it (clear data; AI models deleted completely from Settings). PSK Reporter gets exactly the grid the operator entered. |

## MobSF static scan

`.github/workflows/mobsf.yml` scans each published release (and the rolling test build on demand)
with MobSF in a container; the score and the high/warning findings go to the run summary, the full
reports to an artifact. Test build 375, MobSF v4.6.9: **security score 66/100, 0 high, 9
warnings, 1 hotspot, 0 trackers.** Triage:

| Finding | Verdict |
|---|---|
| Base config trusts system certificates | Intended: system CAs only, no user CAs, no cleartext. No developer-controlled endpoints to pin (NETWORK-2). |
| Can be installed on Android 8.0 (minSdk 26) | Accepted by design (CODE-1). |
| `DebugDecodeReceiver` protected by a permission, "check the protection level" | Test builds only; the permission is `android.permission.DUMP` (signature/privileged/development), which no installed app can hold. Accepted. |
| `ProfileInstallReceiver` protected by a permission | AndroidX baseline-profile installer, also `DUMP`. Accepted. |
| IP address disclosure | The ATAK targets 127.0.0.1 / 239.2.3.1 and the IP pattern in the diagnostics scrub. Intended. |
| Hard-coded sensitive information (11 files) | False positive: the matches are preference keys and labels; the app has no credentials. |
| Possible hard-coded secrets (274 strings) | False positive: high-entropy strings in native libraries (C++ symbol names, hex alphabets, the RFC 6455 WebSocket GUID in MapLibre). |
| Read/write external storage | `MessageLogWriter` (opt-in log to Downloads, the operator's own export), `AiModels` (hash-checked; private copy on Android 8/9), MapLibre's tile cache location. Accepted. |
| SHA-1 used | **Fixed in this assessment:** AI summary and claim-check cache keys (bulletin text off the air) used 80 bits of SHA-1; they now use 128 bits of SHA-256, so crafted bulletins can't share a key. |
| 7 "critical" permissions (hotspot) | Reviewed under PRIVACY-1: each backs a feature. |

Also fixed while assessing: the error-report dialog (after a crash, and Settings → Diagnostics →
last report) offered Copy and Share of the **unscrubbed** stored report; it now gets the same scrub
as a diagnostics report and is withheld if the scrub fails.

## Keeping this current

Re-run the MobSF workflow for each release (it runs automatically on publish) and update the
triage table when a finding changes. Revisit this assessment once a year, for every new
permission, exported component, network endpoint or stored data type, and when MASVS changes.
