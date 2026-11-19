---
title: 5. Feedline, SWR and connectors
summary: Getting the power from the radio to the antenna without losing it on the way
section: Antennas
order: 50
---
The **feedline** carries the signal between the radio and the antenna. For almost every station it's **coaxial cable (coax)**: a centre wire inside insulation, inside a braided or foil shield, inside a jacket. Coax used for radio is "50 ohm" cable (TV cable is 75 ohm and not ideal, though it works in a pinch).

# Coax loses power
All coax turns some of your signal into heat, more for thin cable, long runs and higher frequencies. On HF with short runs, almost any 50-ohm coax is fine. On long runs, thicker coax pays off.

[[calc:feedline]]

- **RG-58:** thin, light, cheap; fine for runs under about 50 ft (15 m) on HF.
- **RG-8X:** a good portable compromise.
- **RG-213 / RG-8 and LMR-400:** thick and heavy, very low loss; for fixed stations and long runs.

> NOTE: Loss works both ways. A lossy feedline also weakens what you receive, so it matters for listeners too.

# SWR in plain words
**SWR (standing wave ratio)** tells you how well the antenna matches the radio and feedline. At 1:1 (written 1.0) every watt goes into the antenna. As the SWR rises, more power bounces back towards the radio.

- **1.0 to 1.5:** excellent.
- **1.5 to 2.0:** fine for nearly all radios.
- **2.0 to 3.0:** most radios start reducing their power to protect themselves; a tuner helps.
- **Over 3.0:** something's wrong (wrong length, broken connection, water in the coax). Find it before transmitting at full power.

[[calc:swr]]

> TIP: A **tuner** makes the radio see a good match, but it doesn't make a bad antenna good. It's useful for small adjustments and for using one antenna on several bands.

# Connectors
- **PL-259 (UHF connector):** the usual HF connector on radios and antennas. Solder or crimp it well; most problems are bad connectors.
- **BNC:** small, quick to connect; common on portable gear.
- Keep connectors dry. Outdoors, wrap them with tape (self-amalgamating tape, then electrical tape over it).

# The choke, again
RF can travel back along the **outside** of the coax shield and make the coax part of the antenna. Symptoms: SWR that changes when you touch the coax, RF "bite" from the radio, the tablet misbehaving when you transmit. A choke at the antenna (or near the radio for end-fed antennas) fixes most of this.

[[diagram:choke|A choke: coax wound into a coil, or passed through ferrite cores.]]

[[guide:antenna-safety|Next: safety]]
