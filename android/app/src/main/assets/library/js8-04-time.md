---
title: 4. Time slots, frames and speeds
summary: Why the clock matters, how messages are cut into frames, and when to use Slow, Normal, Fast or Turbo
section: JS8 from zero
order: 40
---
# Everyone sends at the same moments
JS8 divides time into **slots**. In Normal speed a new slot starts every 15 seconds: at :00, :15, :30 and :45 of each minute. Every station that sends starts at the beginning of a slot and stops a little before the next one. The gap at the end gives everyone's software time to decode before the next slot.

Because the receiving software only looks for signals that start at slot boundaries, **your tablet's clock must be within about 2 seconds of the right time**, and within 1 second is better. A clock that's off sends at the wrong moment, and nobody decodes you; it also stops you decoding others.

[[diagram:timeslots|Stations start together at slot boundaries. A clock off by more than about 2 seconds isn't decoded.]]

# Keeping the clock right
- **With internet or GPS,** Android sets the clock itself. Check that "automatic date and time" is on in the tablet's settings.
- **With neither,** turn on **Settings, Timing, Auto time sync**. The app then nudges its own clock to match the stations it decodes. Once it decodes anyone, it's in step with the net.
- **By hand:** Traffic's ⋮ menu → **Raw decodes, clock and audio details**, then its ⋮ menu: **Sync clock to next decode** and **Adjust time drift**.
- Another source of exact time: the time stations WWV and WWVH broadcast UTC on 2.5, 5, 10, 15 and 20 MHz (AM), with a tick each second.

[[app:settings|Settings: Timing]]

# Frames
Each slot carries one **frame**: a small packet of about a dozen characters (it varies, because common words and callsigns are packed tightly). A longer message is cut into several frames sent in back-to-back slots. The first frame carries who it's from and who it's to; the last frame is marked so the receiver knows the message is complete (JS8Call shows this as **♢**).

[[diagram:frames|A message cut into frames. A missed frame leaves a gap; ♢ marks the end.]]

If one frame is lost to fading or interference, the rest still arrive. This app shows where the gap was ("missing frame") instead of quietly joining the text together, so you know to ask for a repeat.

# Speeds
JS8 has four speeds. Slower is more sensitive; faster gets messages through quicker when signals are strong.

- **Slow:** 30-second slots, 25 Hz wide, about 8 words per minute, decodes down to about −28 dB.
- **Normal:** 15-second slots, 50 Hz wide, about 16 words per minute, about −24 dB. **The default; use it to start every contact.**
- **Fast:** 10-second slots, 80 Hz wide, about 24 words per minute, about −20 dB.
- **Turbo:** 6-second slots, 160 Hz wide, about 40 words per minute, about −18 dB.

How to choose:
- Start in **Normal**.
- If the other station reports you well above the limit (say −10 or better), you can both move up to **Fast** or **Turbo** for long messages.
- If signals are near the limit, move to **Slow**.
- Both stations must use the same speed to hear each other. This app decodes all speeds at once, but sends in the one you pick.

# How long will it take?
Each frame takes a whole slot, so a message's time on air is **frames × slot length**. Type a message to see:

[[calc:airtime]]

> WARNING: JS8 keeps the transmitter on for most of every slot (about 84% of the time in Normal and Slow). Long messages heat the radio more than voice does. If you're not sure your radio can handle it, use no more than half its rated power.

[[guide:js8-05-reading|Next: reading a JS8 message]]
