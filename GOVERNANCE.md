# Governance

How decisions are made in JS8-Tactical and who holds which role.

## Model

JS8-Tactical is a single-maintainer project run by **S2 Underground** (GitHub:
[@s2underground](https://github.com/s2underground)). The maintainer decides what is built, reviews
and merges every change, and publishes every release. Anyone can propose changes through issues
and pull requests; the reasons for decisions are given in the issue or pull request where they
are made.

The project's standing principles, which decisions are measured against:

- **Never limit the operator.** The app may warn and remind operators of the rules (as JS8Call
  does), but must not block something an operator chooses to do. It has to keep working when
  nothing else does: no remote kill switch, no forced updates, no dependence on a server.
- **Stay standard JS8 on the air.** Everything transmitted by default is ordinary JS8 that any
  JS8Call station can read.
- **Security in the open.** Security work, its evidence and its gaps are public
  ([SECURITY_MODEL.md](docs/SECURITY_MODEL.md), [MASVS.md](docs/MASVS.md),
  [ASSURANCE_CASE.md](docs/ASSURANCE_CASE.md)); only vulnerability details stay private until fixed.
- **Privacy by default.** Personal details and precise location stay on the device unless the
  operator shares them.

## Roles

| Role | Responsibilities | Held by |
|---|---|---|
| **Maintainer** | Direction and roadmap; review and merge of all changes; releases and the signing key; security response ([INCIDENT_RESPONSE.md](docs/INCIDENT_RESPONSE.md)); repository settings and rulesets; code of conduct enforcement | S2 Underground (@s2underground) |
| **Security contact** | Receives private vulnerability reports and conduct reports; acknowledges within 7 days | The maintainer, at js8tactical@proton.me and through GitHub private vulnerability reporting |
| **Contributors** | Propose changes by pull request following [CONTRIBUTING.md](CONTRIBUTING.md); report bugs; test builds on their radios | Anyone |
| **Testers** | Run test builds on the air and send diagnostics reports (scrubbed of personal details) | Volunteer operators |

**AI assistance.** Much of the code is written with an AI coding assistant working under the
maintainer's direction (see "How this software is made" in the [README](README.md)). It has no
role of its own: it cannot merge, release, sign, change repository settings or speak for the
project, and its changes pass the same review and required checks as anyone's.

## Continuity

The project currently depends on one person. What exists today: the release signing key is
kept by the maintainer outside GitHub as well as in its secrets (encrypted offline backups are
being made), the source and full history are public under the
GPL (anyone may fork and carry on), and every build step runs in public CI. A named successor with
access to the repository, the key backups and the project email is planned; until then the
OpenSSF "access continuity" and "bus factor" criteria are not met, and this section says so.

## Changing this document

Changes to governance are made by pull request, like any other change, and are decided by the
maintainer.
