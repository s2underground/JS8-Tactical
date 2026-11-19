---
title: 1. Antenna basics
summary: What an antenna does, why its length matters, and what "resonant" means, without the maths
section: Antennas
order: 10
---
Your radio makes a tiny, fast-changing electric current. The antenna is the piece of wire that turns that current into a radio wave that travels away from you, and turns waves arriving from far away back into a tiny current your radio can hear. **The same antenna does both jobs**: if it's good for sending, it's good for receiving.

A great radio on a poor antenna does worse than a cheap radio on a good one. Of everything in your station, the antenna is where effort pays off most.

# Frequency and wavelength
A radio wave repeats millions of times a second. How often it repeats is its **frequency**, in megahertz (MHz): 7.078 MHz means 7,078,000 times a second. While it repeats once, it travels a certain distance: its **wavelength**, written λ (the Greek letter lambda).

[[diagram:wave|A radio wave. Higher frequency means a shorter wavelength.]]

The rule you'll use over and over:

**Wavelength in metres ≈ 300 ÷ frequency in MHz**

So on 7.078 MHz the wavelength is about 42 m (139 ft). That's why radio "bands" are named in metres: the 40-metre band is around 7 MHz, the 20-metre band around 14 MHz, the 80-metre band around 3.5 MHz.

[[calc:wavelength]]

# Why length matters: resonance
A wire works best when its length fits the wave, in the same way a guitar string of a certain length rings at a certain note. The simplest antenna, the **half-wave dipole**, is a wire half a wavelength long, cut in the middle where the feedline connects.

When the length is right, the antenna is **resonant**: the radio sees an easy load, nearly all the power goes into the antenna, and the SWR (a measure of how well the two match) is low. When it's too long or too short, some power bounces back towards the radio instead.

[[diagram:dipole-current|In a half-wave dipole, current (blue) is greatest at the centre where the feedline connects; voltage (red) is greatest at the two ends.]]

Two practical things follow from this picture:
- **The middle does the work.** The high-current centre radiates most of the signal, so get the middle of the wire as high and clear as you can.
- **The ends are "hot".** The voltage at the ends can be hundreds of volts even at modest power. Keep the ends out of reach of people and animals, and away from leaves and walls.

> NOTE: A real wire antenna comes out about 5% shorter than half of the free-space wavelength, mostly because of the way the wave behaves at the ends of the wire ("end effect"). That's why the dipole rule is **468 ÷ MHz = total length in feet**, not 492 ÷ MHz. Always cut a little long and trim.

# Bands and distance
Lower frequencies (80 m, 60 m, 40 m) are best for nearby stations (up to a few hundred kilometres) and at night. Higher ones (20 m and above) carry farther but mostly in daylight, and they skip over nearby stations. The Prop tab shows this for your location and time.

[[guide:antenna-choosing|Next: which antenna should I use?]]
