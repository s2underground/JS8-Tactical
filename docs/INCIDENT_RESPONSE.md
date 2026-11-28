# Incident response plan

What the project does when something goes wrong with JS8-Tactical's security: a vulnerability
report, a bad release, a leaked secret, a compromised account or dependency. It complements
[SECURITY.md](../SECURITY.md) (how to report) and [SECURITY_MODEL.md](SECURITY_MODEL.md) (what the
app trusts).

## Who

JS8-Tactical has one maintainer (S2 Underground), who makes every decision below. AI coding
sessions help investigate and prepare fixes, under the same review and CI rules as any change;
they never publish advisories, rotate keys or post on the project's behalf.

Contact: GitHub private vulnerability reporting (Security → Report a vulnerability) or
**js8tactical@proton.me**, subject "SECURITY".

## Principles

- **The operator stays in control.** The app has no remote kill switch, no forced update and no
  server that can change its behaviour, by design: it must keep working when nothing else does.
  A response therefore works through releases, release notes and settings the operator can change,
  never through switching the app off from afar.
- **Fix first, then tell everyone.** Details stay private until a fixed release is out (or the
  agreed disclosure date passes), then everything is published in full.
- **Nothing is hidden afterwards.** Advisories, release notes and post-incident notes say what
  happened, what was affected and what was done.

## Severity

| Level | Examples | Target |
|---|---|---|
| **Critical** | Anyone on the air or the local network can run code in the app, key the radio or make it transmit without the operator; the signing key or the GitHub account is compromised | Work starts the same day; fixed release or mitigation within 7 days |
| **High** | A received frame crashes the app or stops the receiver; personal data leaves the device unscrubbed; a tampered download is accepted | Fixed release within 30 days |
| **Medium** | Wrong or misleading security-relevant behaviour that needs unusual conditions | Within 60 days (SECURITY.md) |
| **Low** | Hardening gaps, defence-in-depth issues | Next normal release |

Anything that can make the radio transmit when the operator didn't ask is at least **High**, and
**Critical** if it can be triggered from the air.

## General procedure

1. **Acknowledge** the reporter within 7 days (SECURITY.md promises 7, the project's limit is 14).
2. **Triage:** reproduce, set a severity, decide who is affected (versions, radios, settings).
3. **Contain:** if operators need to act before a fix (for example turn off auto-reply, relaying,
   heartbeats, PSK Reporter or the ATAK feed), publish that advice without the technical details:
   a pinned GitHub issue and the README status line.
4. **Fix in private:** open a draft GitHub Security Advisory and work in its temporary private
   fork. Add a regression test, and a fuzz target or property where the bug came from input.
5. **Release** with the [release checklist](RELEASE_CHECKLIST.md); the notes name the problem and
   credit the reporter unless they asked not to be named.
6. **Disclose:** publish the advisory the same day and request a CVE through it for anything
   Medium or above.
7. **Review** (below).

## Playbooks

### A vulnerability report

Follow the general procedure. Agree a disclosure date with the reporter (default: when the fix
ships, at most 90 days after the report). Bugs in upstream components (Hamlib, llama.cpp, JS8Core,
hfcast, MapLibre, libusb, rtl-sdr, airspyhf) are reported upstream privately as well; the app gets
a pinned fix or a workaround meanwhile.

### Unintended transmission or a stuck transmitter

Treat as at least High. Ask the reporter for a diagnostics report (Settings → Diagnostics →
Share: its recent log includes every key and release of the transmitter, with personal details
scrubbed). Until fixed, advise the operating
change that avoids it (auto-reply off, relaying off, heartbeat off, or VOX instead of CAT PTT).
The fix needs a test that reproduces the sequence.

### A bad release (crash, regression, failed verification)

1. Edit the release notes: **"Do not install — see vX.Y.Z"** at the top. Keep the files.
2. If installing it can harm (transmits wrongly, loses data), say what to do in a pinned issue:
   stay on or go back to the previous release (a downgrade needs an uninstall, which deletes app
   data, so first export what matters).
3. Ship a fixed release with a higher version. Obtainium and update checks pick it up.

### The signing key is exposed or lost

The key and its passwords are only in the GitHub secrets `JS8_KEYSTORE_BASE64`,
`JS8_KEYSTORE_PASSWORD`, `JS8_KEY_ALIAS` and `JS8_KEY_PASSWORD`, and in the maintainer's offline
backups.

- **Exposed** (copied, leaked, a backup lost in someone else's hands):
  1. Delete the GitHub secrets so CI can't sign anything more with it.
  2. Make a new key and sign the next release with key rotation (APK Signature Scheme v3:
     `apksigner rotate` then `apksigner sign --lineage`), so installs move to the new key without
     reinstalling. Rotation needs the old key, which in this case we still have.
  3. Publish an advisory and update the fingerprint in SECURITY.md and the README; say from which
     version the new key applies, and that APKs signed by the old key after that date are not
     ours.
- **Lost** (no copy left): updates can't be signed with it again. Make a new key and explain in the
  release notes and a pinned issue that installing it needs an uninstall first (data export
  first). This is why the two encrypted offline backups matter.

### A leaked secret or token

Secret scanning or push protection alerts, or a token found anywhere public: revoke it at the
issuer at once (GitHub, Proton, Hugging Face, …), then check what it could reach and the audit log
for its use, then remove it from the history if it is in the repository.

### The GitHub account or CI is compromised

1. Secure the account: change the password, check 2FA methods and passkeys, sign out all sessions,
   revoke personal access tokens, OAuth apps and GitHub App installations that aren't needed.
2. Check the security log and the repository: rulesets "Master" and "Release Tags" unchanged,
   no new deploy keys, webhooks, secrets, collaborators or workflow changes, no unexpected tags
   or releases.
3. Every release must have a build-provenance attestation naming a commit on `master` and
   `.github/workflows/android-build.yml`. A release without one, or with one from anywhere else,
   is treated as malicious: delete it and say so publicly.
4. If any release could have been signed by an attacker, handle the key as **exposed** (above).

### A compromised or vulnerable dependency

Dependabot alerts, upstream advisories, or a dependency acting suspiciously. Check whether the
affected code is in the APK (the release's `.cdx.json` lists every library and native component
with its version or commit) and reachable. Update or pin to a safe version, or patch on our side;
release with the usual severity targets. AI models and data downloads are pinned to fixed versions
and SHA-256 checksums; a changed upstream file is never accepted silently, only after a reviewed
change to the pin.

### Personal data in a report that reached the project

If a diagnostics report or log arrives with personal details the scrub should have removed:
delete it from wherever it was posted or stored (ask the sender to delete theirs too), fix the
scrub with a test and a fuzz property, and tell the sender what happened. Never forward or quote
the unscrubbed text.

## After an incident

Within two weeks of the fix, write a short review in the advisory or the release notes: timeline,
cause, what was affected, what was changed, and what check now catches it (test, fuzz property,
CI job, lint rule). Update SECURITY_MODEL.md if the model changed, and this plan if a step was
missing or wrong.

## Keeping this plan working

Review it at least once a year and after every incident. Check that the contact address works,
that the offline key backups open and match the published fingerprint, and that the maintainer can
still sign in to GitHub with the recovery codes.
