---
title: 3. The dial, the offset and the waterfall
summary: Where JS8 signals actually are, how to read the waterfall, and how to pick a clear spot to send
section: JS8 from zero
order: 30
---
# One frequency, many stations
On a JS8 frequency like 7.078 MHz, many stations share the same few kilohertz at once. Each one sends a narrow signal at a slightly different spot. Your radio, in **USB** mode, hears a slice about 2.5 to 3 kHz wide above the frequency on its display. That slice arrives at the tablet as **audio**, from deep tones to high tones, and every JS8 signal in it is a narrow tone pattern somewhere in that audio.

# Dial frequency and offset
Two numbers describe where a signal is:
- The **dial frequency**: what the radio's display says (for example **7.078 MHz**). Everyone on that band uses the same dial frequency.
- The **offset**: how high the signal's tones are in the audio, in hertz (for example **1,500 Hz**). Each station uses its own offset.

The signal's real frequency is the two added together: **7.078 MHz + 1,500 Hz = 7.0795 MHz**.

[[diagram:passband|USB: dial frequency plus audio offset. Heartbeats low, traffic higher.]]

[[calc:rf]]

By common agreement:
- **500 to 1,000 Hz** is for **heartbeats** (automatic "I'm here" announcements). The app moves heartbeats here by itself.
- **1,000 to 2,500 Hz** is for **conversation and messages**.

> NOTE: The radio must be in **USB**. In LSB the tones arrive upside down and nothing decodes.

# Reading the waterfall
The **waterfall** on the Radio tab is a picture of that audio over time:
- **Left to right** is the offset: low tones on the left, high tones on the right.
- **Top to bottom** is time: new audio appears at the top and scrolls down.
- **Brightness** is strength.

[[diagram:waterfall|Each JS8 frame shows as a short stripe filling most of a time slot.]]

What you'll see:
- **JS8 signals** look like short, narrow vertical stripes, each lasting most of one time slot, then a gap. A station sending a long message makes a row of stripes, one per slot, at the same offset.
- **Noise** is the speckled background. A good receive setup shows a dark-blue speckle with stripes standing out.
- **All bright or white:** too much audio. Turn the radio's volume (or the tablet's input level) down.
- **All black:** no audio. Check the audio device and cable.
- **Wide smears or steady lines:** interference (power supplies, chargers, other signals). Try another offset, or turn off nearby electronics to find the cause.

[[app:radio|Open the Radio tab]]

# Picking where to send
When you transmit, your signal goes out at **your offset**. Choose a spot that's clear:
1. Watch the waterfall for a few minutes.
2. Pick an empty spot between 1,000 and 2,500 Hz, at least one signal width away from others (Normal is 50 Hz wide; leave more room than that).
3. Don't move around much during a conversation; the other station looks for you where you were.

> TIP: When you answer someone, it's usual to reply near your own spot, not on top of them. Their software decodes the whole slice, so they'll find you anywhere in it.

[[guide:js8-04-time|Next: time slots, frames and speeds]]
