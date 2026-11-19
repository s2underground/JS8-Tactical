---
title: 2. What JS8 is
summary: Text messaging over shortwave that works when voice can't: what it does well, what it doesn't, and why it suits field and emergency use
section: JS8 from zero
order: 20
---
**JS8** is a way of sending short text messages by HF radio. The original program is called **JS8Call**; this app, JS8-Tactical, speaks the same language, so the two talk to each other. JS8 is built on **FT8**, a mode designed to get through when signals are extremely weak.

# How it works, in one paragraph
You type a message. The app turns it into a pattern of eight quiet tones and plays it into the radio, which sends it on the air. Every station listening on that frequency records the tones, and their software works out the text, even when the signal is far below the hiss you'd hear in a speaker. Messages are sent in small pieces called **frames**, each lasting a few seconds, at fixed times. The receiving software puts the pieces back together.

# What JS8 is good at
- **Weak signals.** In Normal speed JS8 can decode signals down to about **−24 dB**, around 250 times weaker than the noise. Voice needs the signal to be **stronger** than the noise. In practice, JS8 gets through with 5 to 20 watts and a wire antenna where voice would need far more, or wouldn't work at all.
- **Small, low-power stations.** A battery, a small radio and a piece of wire are enough.
- **Text that's exact.** Grid squares, times and numbers arrive exactly as sent; nothing is misheard.
- **Unattended operation.** Stations can answer automatic requests ("how do you hear me?", "what's your location?"), announce themselves with heartbeats, store messages for others, and relay messages to stations they can hear when you can't.
- **Groups.** One message can go to a whole team with a group name like @TEAM.

# What it's not
- **Not fast.** Normal speed is about 16 words per minute, roughly a short text message every minute or two. Keep messages short.
- **Not private.** Anyone with a receiver and the free software can read every message. In amateur radio, hiding the meaning of messages is not allowed in most countries.
- **Not instant.** Messages go out at fixed times, and propagation may mean the other station simply can't hear you right now.
- **Not a replacement for voice** when voice works. It's the tool for when it doesn't, or when you need a written record.

# What you need
- **To listen:** a receiver with SSB (USB), its audio connected to this tablet, and the clock right. No licence.
- **To send:** a transceiver, an antenna, power, a connection to the tablet, and (in most countries) an amateur radio licence.

[[guide:start-listener|Listening with a receiver]]
[[guide:start-operator|Setting up your station]]
[[guide:js8-03-waterfall|Next: the dial, the offset and the waterfall]]
