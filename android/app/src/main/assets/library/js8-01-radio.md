---
title: 1. Radio in plain words
summary: Frequencies, bands, why HF reaches over the horizon, day and night, and the kinds of signals on the air
section: JS8 from zero
order: 10
---
This series starts from nothing. If you've never touched a radio, read it in order; each chapter builds on the one before. If you know radio already, skip to the chapter you need.

# What a radio signal is
A radio transmitter makes an electric current that swings back and forth very fast. An antenna turns that into a **radio wave** that spreads out at the speed of light. A receiver's antenna picks up a tiny copy of the wave, and the receiver turns it back into sound or data.

How fast the wave swings is its **frequency**, measured in hertz (Hz), meaning times per second:
- **kHz** (kilohertz) = thousand times a second
- **MHz** (megahertz) = million times a second

**7.078 MHz** means 7,078,000 swings a second. Every radio station, voice or data, sits on a frequency, and you "tune" a receiver to that frequency to hear it.

[[diagram:wave|A radio wave. Higher frequency means a shorter wavelength.]]

# Bands
Frequencies are grouped into **bands**, named by their wavelength in metres. The ones JS8 uses most:

- **80 m** (around 3.5 MHz): nearby stations, best at night.
- **40 m** (around 7 MHz): the busiest JS8 band; regional by day, farther at night.
- **30 m** (10.1 MHz): a good in-between band, day and night.
- **20 m** (14 MHz): long distance in daylight.
- **17, 15, 12, 10 m** (18 to 28 MHz): long distance when the sun is active, mostly by day.

All of these are **HF** (high frequency, 3 to 30 MHz), also called **shortwave**.

# Why HF goes over the horizon
Radios like handhelds, CB, FRS or GMRS use **VHF and UHF**. Their signals travel in fairly straight lines, so hills, buildings and the curve of the earth block them. Range is usually a few to a few tens of kilometres.

HF is different. High above us, the sun turns part of the upper atmosphere into a layer of charged particles called the **ionosphere**. HF signals that reach it are bent back down to earth, sometimes hundreds or thousands of kilometres away. That's how a small station with a wire antenna can talk across a country with no towers, no internet and no satellites.

[[diagram:line-of-sight|VHF/UHF go in straight lines; HF bends back from the ionosphere.]]

[[diagram:skywave|A signal bouncing off the ionosphere lands far away.]]

# Day, night and the sun
The ionosphere changes all the time, which is why a band that worked at noon can be silent at midnight.

- **Daytime:** the sun is strong. Higher bands (20 m and up) open for long distance. A low layer also soaks up signals on the lowest bands, so 80 m covers less distance by day.
- **Night:** that low layer fades, so 80 m and 40 m carry much farther. The higher bands usually close.
- **Sunrise and sunset** shift things quickly.
- **The 11-year sun cycle** and solar storms raise or lower what's possible for days at a time.

You don't have to memorise this. The **Prop tab** predicts which band works for which distance at which time, and it learns from what your own station hears.

For a second opinion, tap any point on the Prop tab's map, then **VOACAP** in the message that appears. VOACAP is the standard HF prediction program (from the Voice of America and NTIA/ITS) that most operators know. It shows a whole day for every band on that path, as the chance the path is open by hour (UTC), for your power and JS8 speed. It runs on the phone with no internet. It is a monthly average, so the Prop tab's own chart, which also learns from recent traffic, is usually closer for today. Set the noise where you are (city, residential, rural, remote) with **Noise…**: it moves the answer a lot.

[[app:prop|Open the Prop tab]]

# Kinds of signals
On the bands you'll find several ways of sending information (called **modes**):
- **AM:** broadcast stations; simple but wasteful of power.
- **FM:** clear voice; used on VHF/UHF handhelds, rarely on HF.
- **SSB** (single sideband, either **USB** upper or **LSB** lower): the efficient way to send voice on HF. Data modes like JS8 also use **USB**.
- **CW:** Morse code.
- **Digital modes:** text or data sent as tones that a computer decodes. JS8 is one of these, built for weak signals.

# How strong is a signal? Decibels
Radio uses **decibels (dB)** to compare strengths. You only need three facts:
- **+3 dB** is about twice the power; **+10 dB** is ten times.
- **0 dB** means "equal". Negative numbers mean "weaker than".
- A JS8 signal report like **−12** means the signal was 12 dB **below** the background noise (measured over a standard 2,500 Hz slice). JS8 can still read signals far weaker than the noise you can hear.

[[guide:js8-02-what|Next: what JS8 is]]
