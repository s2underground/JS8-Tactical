# Security policy

## Reporting a vulnerability

Please report security problems **privately**, not in a public issue. Either:

- use GitHub's private reporting: the **Security** tab of this repository → **Report a vulnerability**, or
- email **js8tactical@proton.me** with "SECURITY" in the subject.

Include what you found, how to reproduce it (a recording of the transmission, a crafted frame,
steps in the app), the app version (Settings → About) and the device. If you are not sure whether
something is a security problem, report it privately anyway.

What happens next:

1. You get an acknowledgement within **7 days** (the project's limit is 14 days).
2. We confirm the problem and agree a disclosure date with you. The usual target is a fix
   within 60 days of confirmation, sooner for anything serious.
3. The fix ships in a release whose notes name the problem (and its CVE, if one was assigned),
   and credit you unless you would rather stay anonymous.

How the project handles reports and other security incidents (bad releases, leaked secrets, a
compromised key or account) is written down in [docs/INCIDENT_RESPONSE.md](docs/INCIDENT_RESPONSE.md).

## Supported versions

JS8-Tactical is in early alpha. Only the newest release on the
[Releases](../../releases) page gets security fixes. The rolling *Latest test build* is
for testing and may contain unreviewed changes.

## What counts

The most important cases are those an outsider can trigger without touching the device:

- anything a transmitted JS8 frame can do beyond showing text (crashes, hangs, memory corruption,
  sending or keying the radio unexpectedly, changing settings, leaking data);
- traffic on the local network (rigctld, the ATAK feed) that the app accepts or sends when it
  should not;
- tampered downloads (AI models, map tiles, space-weather data) being accepted;
- the app transmitting something the operator did not ask for.

See [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) for what the app trusts and what it does
not. Bugs in upstream components (Hamlib, llama.cpp, JS8Core, MapLibre) are welcome here too; we
will pass them upstream.

## Verifying a download

Release APKs are built by GitHub Actions from the tagged source and signed with the project's
release key (RSA 4096, `CN=JS8-Tactical, O=S2 Underground`). Its certificate SHA-256 fingerprint is:

```
aa581d2725c75f085ad4231ae0a8f30f1b0a8ad741d8b128b3e628c0144d108b
```

Check an APK with `apksigner verify --print-certs JS8-Tactical-<version>.apk` (from the Android SDK
build tools): the "certificate SHA-256 digest" line must match. Each release also carries a
`.sha256` file with the APK's own checksum and the signer fingerprint. Android itself refuses to
install an update signed with a different key over an existing install.

Each APK also has a build-provenance attestation, signed by GitHub, that records the workflow run
and commit it was built from. With the GitHub CLI:

```
gh attestation verify JS8-Tactical-<version>.apk -R s2underground/JS8-Tactical
```

The matching `.cdx.json` file is a CycloneDX software bill of materials listing every library and
native component in that APK, with versions and commits; it is attested the same way.

Rolling test builds from the main repository are signed with the same release key. Builds made
from forks or pull requests are signed with a public debug key and must not be trusted.
