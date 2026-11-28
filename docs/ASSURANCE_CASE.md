# Assurance case

Why we believe JS8-Tactical meets its security requirements, laid out as claims, the arguments for
them and the evidence anyone can check. It ties together the threat model
([SECURITY_MODEL.md](SECURITY_MODEL.md)), the architecture ([ARCHITECTURE.md](ARCHITECTURE.md)), the
MASVS self-assessment ([MASVS.md](MASVS.md)) and the CI that checks every change. Known gaps are
listed, not hidden.

## Top-level claim

**JS8-Tactical is adequately secure for its purpose**: operating a radio station with plain-text
JS8 traffic, often off-grid, on Android devices owned by the operator. Adequately secure means:

1. **R1 — The radio transmits only what the operator intends**: no transmission, keying or
   automatic reply that the operator did not ask for or switch on, whatever is received.
2. **R2 — Nothing received can take over the device or the app**: no code execution, memory
   corruption, crash loop or settings change from anything heard on the air, received on the
   network, or downloaded.
3. **R3 — The operator's privacy is protected**: precise location and personal details stay on the
   device unless the operator shares them; reports sent to the project carry no personal details.
4. **R4 — What users install is what this repository built**: releases can be verified to come
   from this source and this CI, unmodified.

What is **not** claimed: confidentiality or authenticity of JS8 traffic (the mode is plain text by
law, and anyone can claim any callsign); protection against someone with the unlocked device in
hand; protection of a rooted device from its owner.

## Threat model and trust boundaries

Summarised from [SECURITY_MODEL.md](SECURITY_MODEL.md), which has the full table:

| Attacker | Reaches the app through | Boundary |
|---|---|---|
| Anyone with a transmitter | Received JS8 frames: audio → JS8Core decoder → JNI → Kotlin parsers | Native decoder, JNI text conversion, Kotlin parsing of received text |
| A device on the same network | rigctld TCP replies; nothing listens on the network | `RigCtlClient` |
| A rogue or faulty radio / Bluetooth device | Serial replies, Bluetooth frames | Hamlib, session parsers |
| A compromised web host | Space weather, map tiles, data files, AI models (HTTPS) | TLS with system CAs; content pinned by SHA-256 where it is code-like (models) |
| A malicious model file | GGUF parsing in llama.cpp | The isolated `:llm` process |
| A supply-chain attacker | Dependencies, CI, the release pipeline | Pinned versions, Dependabot, protected branches and tags, least-privilege CI, attestations |

## Arguments and evidence

### R1: transmissions are the operator's

- *Argument:* every path that can transmit is listed and each needs an operator action or an
  operator-enabled setting; received text can't enable a setting; PTT is tracked so the
  transmitter is always released.
- *Evidence:* the list of what can transmit (SECURITY_MODEL.md, "What can make the radio
  transmit"); auto-reply decisions in pure code with unit tests and a fuzz target
  (`service/AutoReply.kt`, `AutoReplyFuzz`); store-and-forward access rules fuzzed
  (`StoreForwardFuzz`: a held message only goes to its addressee); AGN? replays only the operator's
  own message, rate-limited (audit M3, PR #39); automatic replies can't starve operator sends
  (PR #43); PTT key/release ledger with retries on shutdown, crash and USB dropout (`PttLedger`,
  PRs #35, #47, #62, #75); the on-air grid is only ever the 4-character square (`OnAirTextFuzz`).

### R2: received input can't take over the app

- *Argument:* all untrusted input is parsed by code that is tested and fuzzed; memory-unsafe code
  is fuzzed with memory-error detectors and runs its tests under sanitizers; the riskiest parser
  (llama.cpp) is isolated; static analysis runs on every change.
- *Evidence:*
  - libFuzzer with ASan/UBSan on the JS8 frame decoders and the VOACAP C boundary, weekly and on
    changes (`fuzz.yml`, `scripts/fuzz-core.sh`, `scripts/fuzz-voacap.sh`).
  - Jazzer with property checks on every Kotlin parser of received text: store-and-forward,
    reports, rollup, diagnostics scrub, relay protocol and assembly, message assembly, auto-reply,
    on-air text (`fuzz-kotlin.yml`, `tools/fuzz/kotlin`).
  - Native core tests under AddressSanitizer, UndefinedBehaviorSanitizer and LeakSanitizer on every
    push (`android-build.yml`, job "Core native tests (ASan + UBSan)").
  - CodeQL (security-extended) on C/C++ and on Kotlin/Java, required on `master` (`codeql.yml`).
  - JNI hands Java only well-formed, filtered text (audit H1, PR #36); rigctld replies bounded in
    length and time (`BoundedLine`, PR #70).
  - llama.cpp in an `isolatedProcess` service with no permissions, model passed as a file
    descriptor after SHA-256 verification, output capped (PRs #15, #72).
  - Fuzzing has found and fixed real bugs (5 in PR #14, a VOACAP panic in PR #70), each with a
    regression test.

### R3: privacy

- *Argument:* the app keeps data on the device, sends nothing without an operator action, limits
  what goes on the air to what the operator types plus the 4-character square, and scrubs anything
  meant for the project.
- *Evidence:* no backups or device transfers (PR #41); no analytics or trackers (MobSF: 0 of 432);
  `DiagnosticsPrivacy` scrub with unit tests and a fuzz property (no callsign, finer grid
  characters or IP address survives), applied to diagnostics reports and the error-report dialog,
  and the report is withheld if the scrub fails (PRs #11, #19, #20, #37, #48, #74, #81); log lines
  carry message text as a length only; CodeQL `java/sensitive-log` findings fixed (PR #78).

### R4: releases are genuine

- *Argument:* only CI builds releases, from a protected tag, with a key that exists only in CI
  secrets and offline; every artifact is attested and inventoried; users can verify all of it.
- *Evidence:* rulesets on `master` (pull requests, required checks, no force push) and `v*` tags;
  least-privilege workflow tokens, actions pinned by SHA, Gradle wrapper validated, signing key
  confined to one build step and never given to pull requests (PRs #13, #38, #44); release
  checklist ([RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)); SBOM and SLSA build-provenance
  attestation for every APK (PR #73); signer fingerprint published in SECURITY.md; release builds
  not debuggable (PR #47).

## Secure design principles applied

| Principle | How |
|---|---|
| Least privilege | Permissions only for features; the AI engine has none; CI tokens read-only except publish jobs; no exported components but the launcher activity |
| Fail-safe defaults | Auto-reply, relaying, PSK Reporter, the ATAK feed, message logging and AI are off until switched on; cleartext refused; a scrub failure withholds the report; a model hash mismatch deletes the file |
| Complete mediation | Every received frame goes through the same parsers and the same auto-reply decision; store-and-forward checks the addressee on every request |
| Economy of mechanism | Decisions pulled out of the service into small pure functions that can be tested and fuzzed |
| Separation of privilege | llama.cpp in its own process; signing only in CI, only on tags |
| Open design | All source, CI and security documents are public; no security through obscurity |
| Least common mechanism | No shared server; each device stands alone |
| Psychological acceptability | Warnings instead of blocks; plain-language prompts; security never stops the operator |

## Common implementation weaknesses countered

| Weakness (CWE) | Where it could arise | Countered by |
|---|---|---|
| Out-of-bounds read/write (CWE-125, CWE-787), use after free (CWE-416) | JS8Core, JNI, llama.cpp, SDR drivers | Fuzzing with ASan; tests under ASan/UBSan; CodeQL C/C++; llama.cpp isolated |
| Improper input validation (CWE-20) | All received text, radio replies, files | Allowlist parsing (callsign, grid, command patterns), length caps, fuzz properties |
| Integer overflow (CWE-190) | Frame and audio sizes, VOACAP area sizes | UBSan; explicit range checks (PR #70) |
| Uncontrolled resource consumption (CWE-400) | rigctld replies, message assembly, AI output | Length and time bounds (`BoundedLine`), assembler caps, output caps |
| Sensitive information in logs (CWE-532) | Received text, callsigns, grids | Text logged as length only; CodeQL `java/sensitive-log`; diagnostics scrub |
| Cleartext transmission (CWE-319) | Web requests | Network security config: HTTPS only |
| Download of code without integrity check (CWE-494) | AI models | Pinned revision and SHA-256 before load |
| Exposed components / intent spoofing (CWE-926, CWE-925) | Android IPC | Not exported; `RECEIVER_NOT_EXPORTED`; immutable PendingIntents; USB permission from `UsbManager`; restart token |
| Hard-coded credentials (CWE-798) | — | None exist; secret scanning and push protection on |
| Command injection (CWE-78), SQL injection (CWE-89) | — | No shell with external input (only a fixed `logcat` call); Room queries only, no raw SQL |
| Path traversal (CWE-22) | Shared files | FileProvider with fixed cache paths |

## Known gaps

- Single maintainer: no second reviewer and no named successor yet ([GOVERNANCE.md](../GOVERNANCE.md)).
- No independent audit yet (planned once the project is more mature).
- Statement coverage of the Kotlin code is about 38% of app logic; the riskiest parsers are
  covered and fuzzed, much of the UI and the service is not.
- rigctld has no authentication (a protocol limit); use it on a trusted network.
- Builds are not yet reproducible bit for bit.
- Gradle dependencies are pinned by version but not yet by checksum.

## Keeping it current

Every security-relevant pull request updates the evidence here or in SECURITY_MODEL.md. This
document is reviewed with each minor release and at least yearly.
