# Release checklist

How a JS8-Tactical release is cut and checked. Releases are built and signed by GitHub Actions from
a tag on `master`; nobody builds a release APK on their own machine. The signing key exists only in
the repository's GitHub secrets (and the maintainer's offline backups).

## 1. Before the version bump

- [ ] `master` is green: **Build Android (debug)**, **Core native tests**, **Core native tests
      (ASan + UBSan)**, **Analyze C/C++**, **Analyze Java/Kotlin**.
- [ ] No open code-scanning alert of high or critical severity that applies to this release
      (Security → Code scanning). Each open alert is fixed or dismissed with a written reason.
- [ ] No open Dependabot security alert that reaches shipped code (Security → Dependabot).
- [ ] The latest weekly fuzz runs (**Fuzz Kotlin parsers**, **Fuzz JS8 decoders and the VOACAP
      engine**) finished without findings,
      or every finding is fixed.
- [ ] No unfixed vulnerability report is due to be disclosed in this release without its fix
      (see [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md)).
- [ ] The last rolling test build has been installed on a real device and has:
      received and decoded on the air, sent a message and a heartbeat with the operator's radio,
      unkeyed cleanly, and shared a diagnostics report that shows no callsign, no grid finer than
      4 characters and no message text.

## 2. Version bump (pull request)

- [ ] `versionName` in `android/app/build.gradle.kts` (for example `1.2.0-alpha.6`) and the status
      line in `README.md`. `versionCode` comes from the workflow run number, so it always rises.
- [ ] Draft release notes in the PR: **Fixed**, **New**, **Security** (every security fix, with its
      advisory/CVE if there is one, and credit to the reporter unless they asked not to be named),
      **Verify your download**, **Reporting bugs**. Plain language; say what changes for the
      operator.
- [ ] Merge once the required checks pass.

## 3. Tag and publish

- [ ] Releases → **Draft a new release** → new tag `v<versionName>` on `master` → mark
      **pre-release** for alpha/beta/rc → publish. (Tags `v*` are protected by the "Release Tags"
      ruleset: only repository admins and the apps on its bypass list can create them.)
- [ ] The tag build attaches to the release: the signed APK, `<apk>.sha256` and
      `<apk-name>.cdx.json` (SBOM). It also records a build-provenance attestation for the APK and
      the SBOM.
- [ ] Paste the release notes, with the APK's SHA-256 from the `.sha256` file under **Verify your
      download**.

## 4. Verify what was published

Download the APK from the release page (not from the build artifacts) and check:

- [ ] Signer: `apksigner verify --print-certs <apk>` shows certificate SHA-256
      `aa581d2725c75f085ad4231ae0a8f30f1b0a8ad741d8b128b3e628c0144d108b`.
- [ ] Not debuggable: `aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep debuggable`
      prints nothing.
- [ ] Checksum: `sha256sum <apk>` matches the `.sha256` file and the release notes.
- [ ] Provenance: `gh attestation verify <apk> -R s2underground/JS8-Tactical` passes and names the
      tag's commit and `.github/workflows/android-build.yml`.
- [ ] SBOM: the `.cdx.json` lists the APK's SHA-256 under `metadata.component.hashes`.
- [ ] Install over the previous release on a device (Android refuses an update with a different
      signer, so this also proves the key): it starts, keeps its settings, and receives.

## 5. After publishing

- [ ] Obtainium users get the release automatically (it follows release titles
      `JS8-Tactical v…`); check that the title has that form.
- [ ] Security fixes: publish the GitHub Security Advisory (and its CVE) the same day, per
      [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md).
- [ ] Note the release (version, versionCode, APK SHA-256) in the security plan log.

## If something is wrong

A release that fails any check in section 4 is not announced. Edit its notes to say
**"Do not install — see vX.Y.Z"**, keep the files (deleting them hides what happened), and follow
the "bad release" playbook in [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md).
