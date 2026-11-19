---
title: 4. End-fed and vertical antennas
summary: One support, several bands: the end-fed half-wave; small space, long distance: the vertical
section: Antennas
order: 40
---
# The end-fed half-wave (EFHW)
An EFHW is one half-wavelength wire fed at one end instead of the middle. Because the feedpoint is at the end, you need only **one high support** (a tree), and the radio end can sit on the ground next to you. A wire cut for 40 m also works, without changes, on 20, 15 and 10 m, which is why EFHWs are so popular for field and portable use.

The catch is that the end of a half-wave wire is a very high-impedance point (thousands of ohms), so it needs a **transformer** (usually 49:1, sometimes 64:1) between the coax and the wire. You can buy one, or wind one on a ferrite core from a kit.

[[diagram:efhw|An EFHW: transformer near the ground, wire sloping up into a tree, a short counterpoise.]]

## Building it
1. Get a 49:1 transformer rated for your power.
2. Cut the radiator wire for your **lowest** band using the calculator, a little long.
3. Connect the wire to the transformer's antenna terminal and add a short **counterpoise** wire (or let the coax shield serve as one; many designs do).
4. Raise the far end as high as you can: sloping, horizontal, or as an inverted L all work. Higher is better.
5. Put a **choke** on the coax near the radio: EFHWs push some current onto the outside of the coax.
6. Trim the wire for lowest SWR on the lowest band (shortening it raises the frequency). The higher bands usually fall into place.

[[calc:efhw]]

> WARNING: The far end of an EFHW carries very high voltage. Keep it out of reach and away from leaves and buildings.

# The quarter-wave vertical
A vertical is a quarter-wavelength element standing upright, with **radials** spreading out from its base. The radials act as the other half of the antenna. A vertical sends its signal at a low angle in all directions: good for long distance, poor for nearby stations (they fall in the skip zone).

[[diagram:vertical|A quarter-wave vertical with radials on the ground.]]

- **Radials on the ground** don't need to be any particular length; lay out as many as you can (8 to 16 is good, more is better).
- **Raised radials** (a few feet up) should be a quarter wave long; two to four are enough.
- A vertical on a vehicle uses the vehicle body as its "radials".

[[calc:vertical]]

# Whips and portable antennas
A short whip (like those on manpack radios) is much shorter than a quarter wave on HF bands, so it needs a tuner and works far less well than a full-size wire. It's fine for strong signals and short distances. For nearby stations, a whip **tied back horizontally** sends more energy upwards and works better as an NVIS antenna; see the field guide.

[[guide:antenna-feedline|Next: feedline, SWR and connectors]]
