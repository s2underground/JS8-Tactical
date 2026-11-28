# Roadmap

What JS8-Tactical plans to work on over the next twelve months (December 2026 to November 2027),
what we are exploring, and what we have decided not to do. Plans change with field testing and
feedback; this file is updated when they do. Ideas and requests are welcome as GitHub issues.

The direction behind it: keep stations in touch when the usual infrastructure is gone, stay
readable by every standard JS8 station, never limit the operator, and keep security work public
([GOVERNANCE.md](GOVERNANCE.md)).

## Planned

**Reaching other stations**
- **Relay finder improvements**: two-hop relays, time of day from the propagation model, and "hold
  until the path opens".
- **Link quality memory**: every decode updates how well each station is heard per band and hour,
  so the app can suggest the best band to reach a station now.
- **Call on the best channel**: one-tap retune over CAT to the band most likely to work, working
  down a PACE list if there is no answer, using plain JS8 text that stock users can follow.
- **Store-and-forward helpers**: "hold for" a station through `MSG TO:`, and a one-tap
  `QUERY MSGS` to recently heard stations.

**Traffic and reports**
- **Compact report lines**: shorter on-air formats for each report form that still read as plain
  JS8, with the app turning incoming compact lines back into fields.
- **Smarter receive side**: merging repeats by content, a listen-only bulletin view that shows
  missing parts, and a precedence picker (FLASH / IMMEDIATE / PRIORITY).
- **Propagation**: VOACAP antenna patterns, a combined VOACAP + learned-model map, and deeper
  learning from what the station actually hears.
- **ATAK**: from JS8 reports → ATAK today towards ATAK → JS8 (starting with weather), possibly
  with a small companion plugin.

**Quality and security** (details in [docs/ASSURANCE_CASE.md](docs/ASSURANCE_CASE.md))
- Test coverage of the app logic towards 80%, by moving logic out of the screens and the service
  into tested, fuzzed code.
- Reproducible builds (anyone can rebuild a byte-identical APK), then F-Droid.
- Gradle dependencies pinned by checksum; automatic formatting checks.
- Target SDK raised once the layouts are tested on Android 15.
- OpenSSF Best Practices Silver badge; an independent security audit when the project is ready
  for one.

## Exploring

- **Scheduled team scanning**: stations on a shared channel list and schedule tied to the JS8
  slot clock, so team members are on the same channel at the same time.
- **Link upkeep**: watching SNR trends and missed ACKs to slow down or move channel before a link
  fails, handing off to the relay finder when nothing direct works.
- **A native JS8-Tactical mode** (opt-in, on agreed frequencies only): compact binary records with
  repair pieces, researched off the air first and only used where every station can read it.
- **2G ALE** (MIL-STD-188-141) interoperability, only if bench tests show a real benefit.
- **MANET gateway**: an HF base station sharing traffic with clients over a local mesh network,
  with strong authentication before anyone on the mesh can make it transmit.
- **Soft combining** of repeated frames, ported from JS8Call-Improved.
- **Memory-safe code**: new low-level code in Rust; replacing C++ parts only behind switches and
  bit-for-bit compatibility tests.

## Not planned

- **Encryption or hidden meaning on amateur bands.** JS8 traffic stays plain text.
- **Anything that limits the operator**: no remote kill switch, forced updates, accounts or
  server the app depends on. The app reminds operators of the rules; it doesn't enforce them.
- **Breaking compatibility with standard JS8** by default.
- **Closed-source components**: everything stays under the GPL (for example, no proprietary ALE
  modem).
- **Bundling AI models in the app** or running AI off the device; AI stays optional and only
  summarises what was received.
- **Large file transfer over JS8**: the mode is for short messages.
- **A full rewrite** of the app or the JS8 core.
