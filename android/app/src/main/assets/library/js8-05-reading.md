---
title: 5. Reading a JS8 message
summary: Who sent it, who it's for, what the numbers mean, and how the Traffic screen shows it
section: JS8 from zero
order: 50
---
# The shape of a message
Almost every JS8 message has the same shape:

**FROM: TO MESSAGE**

- **KX4ABC: W1AW SNR?** means KX4ABC is asking W1AW "how well do you hear me?"
- **W1AW: KX4ABC SNR -12** is W1AW's answer: "I hear you at −12 dB."
- **KX4ABC: @TEAM MEET AT 1800** is KX4ABC sending to everyone in the group @TEAM.
- **KX4ABC: @ALLCALL CQ CQ CQ EM83** is KX4ABC calling anyone at all, from grid square EM83.

The part before the colon is always the **sender**. The first word after it is the **recipient**: a callsign, a group (starting with **@**), or **@ALLCALL** for everyone.

# Callsigns and grid squares
- A **callsign** identifies a station: letters and numbers like W1AW, KX4ABC or VE3XYZ. The first letters show the country (W, K, N and A for the United States; VE for Canada; G for England, and so on). A suffix like **/P** means portable and **/M** mobile.
- A **grid square** (Maidenhead locator) is a short code for a location: **EM83** covers 1° of latitude by 2° of longitude (around 110 by 185 km in the southern US); **EM83KX** narrows it to about 5 by 8 km. JS8 stations often include their grid so others know roughly where they are and how far the signal went.

# Signal reports (SNR)
**SNR** (signal-to-noise ratio) is how strong a signal was compared with the background noise, in decibels:
- **+10 or more:** very strong.
- **0 to −10:** comfortable.
- **−10 to −20:** weak but solid in Normal speed.
- **−20 to −24:** near the limit for Normal; expect missed frames.
- **Below −24:** Normal will rarely decode it; Slow may.

Each decoded line on the Traffic screen shows the SNR, so you can see how well you hear each station. When others report your SNR back to you, you learn how well they hear **you**, which is usually different.

# Messages arrive in pieces
A long message arrives one frame per time slot. Most JS8 programs print each piece as it comes. **This app joins the pieces into one message** as they arrive, and:
- shows a message as **in progress** while frames are still coming,
- marks any piece that didn't arrive as **missing frame** (or "2 frames missing"), so you know the text is incomplete and can ask for it again,
- merges a repeat of the same message into the original, filling gaps from the second copy.

[[diagram:frames|A missed frame shows as a gap instead of silently running the text together.]]

# The Traffic screen
- The **station list** shows everyone heard, with how long ago and their SNR. Groups you're in and the "My team" row are at the top.
- Tapping a station or group opens its **thread**: just the messages to and from it.
- **Reports** (#CHECKIN, #SPOT and so on) show as cards with their fields and a map position.
- **Heartbeats** are kept off the message list (there are many of them), but they still add stations to the list, the map and the propagation learning.

[[app:traffic|Open the Traffic tab]]

> TIP: If a message to you came in with gaps, reply **AGN?** ("again?") and the sender's software will usually repeat its last message.

[[guide:js8-06-heartbeats|Next: heartbeats, CQ and who hears whom]]
