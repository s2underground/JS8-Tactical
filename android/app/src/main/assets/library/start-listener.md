---
title: Listening to JS8 with a receiver
summary: No transmitter, no licence: hear and read JS8 traffic with a shortwave receiver and this tablet
section: Start here
order: 10
---
JS8 is a way of sending short text messages by radio over hundreds or thousands of kilometres. Stations send text as a pattern of quiet tones. You can't understand it by ear; this app listens to the tones and turns them back into text.

You do not need a licence to listen. You need three things: a receiver that can do **SSB**, a way to get its sound into this tablet, and the tablet's clock set close to the right time.

# 1. Check your receiver
JS8 is sent in a mode called **upper sideband (USB)**, a kind of SSB. A radio that only has **AM** and **FM** cannot receive it, however good it is.

- Look on the radio or in its manual for **SSB**, **USB/LSB**, or a **BFO** knob. If it has one of these, it can probably work.
- Many portable shortwave radios have SSB (for example the Tecsun PL-330 or Sangean ATS-909X2). A ham transceiver always does.
- A software-defined radio (SDR) with a computer or phone also works if it can tune HF (3–30 MHz) in USB mode.

> TIP: Fine tuning in steps of 10 Hz to 100 Hz is ideal. A radio that only tunes in 1 kHz steps still works; the signals just appear in a slightly different place on the waterfall.

# 2. Tune to a JS8 frequency
Set the radio to **USB** and tune to one of these "dial" frequencies. The signals are just above the dial, inside the audio you hear.

- **7.078 MHz** (40 m): the busiest; day and night.
- **3.578 MHz** (80 m): best at night and for nearby stations.
- **14.078 MHz** (20 m): daytime, long distance.
- Others: 10.130, 18.104, 21.078, 28.078 MHz.

[[app:prop|Which band is open right now? Open the Prop tab]]

Then tell the app the frequency: on the **Radio** tab, set the radio frequency to the same dial frequency. This labels what you hear and lets the app learn which bands work for you.

# 3. Get the sound into the tablet
The app needs the receiver's audio. In order of preference:

1. **A cable** from the radio's headphone or line-out socket to the tablet. If the tablet has a headset socket you need a cable with a "TRRS" plug wired for a microphone; if it has no socket, use a small USB-C audio adapter that has a microphone input.
2. **A USB sound card** on a USB-C adapter, with the radio's audio into its microphone input.
3. **No cable at all:** put the tablet's microphone right next to the radio's speaker in a quiet room. It works for strong signals and is better than nothing.

Turn the radio's volume **down** to start. Too much audio is the most common problem.

[[app:settings|Choose the audio input: Settings, Audio device]]

# 4. Set the clock
JS8 stations take turns in fixed time slots (every 15 seconds in Normal speed). The tablet's clock has to be within about **one second** of real time; at two seconds or more, nothing decodes.

- With internet or GPS, Android usually keeps the clock right by itself.
- With neither, turn on **Settings, Timing, Auto time sync**. The app then corrects its own clock from the signals it hears, so once one station is decoded, the rest follow.
- On the **Radio** tab, the ⋮ menu has **Sync clock to next decode** and **Adjust time drift** for doing it by hand.

[[app:settings|Open Settings]]

# 5. Start listening
On the **Radio** tab, switch **Monitoring** on. The waterfall shows the audio from left (low tones) to right (high tones), with time scrolling down.

- JS8 signals look like thin, short stripes, usually between 500 and 2,500 on the waterfall's scale.
- If the waterfall is all bright, the audio is too loud: turn the radio down. If it's black, turn it up, or check the cable.
- Decodes appear on the **Traffic** tab. Messages arrive in pieces every few seconds; the app joins the pieces into whole messages and shows gaps where a piece was missed.

[[app:radio|Open the Radio tab]]

# 6. Reading what you hear
- **Heartbeats** ("HB", "HEARTBEAT") are stations saying "I'm here"; they're hidden from the message list but put the station on the list and the map.
- **@ALLCALL** and other **@GROUPS** are messages to everyone or to a group.
- **Reports** such as #CHECKIN, #SPOT or #SITREP show as cards, with their location on the **Map**.
- Needs and emergencies are collected on the **Digest** (the Digest button on the Traffic tab).

> NOTE: You chose "listen", so the message box and report buttons are hidden on the Traffic tab. Tap the "Listening only" line there, or change "I am a…" in Settings, to show them.

[[guide:antenna-basics|Better reception starts with the antenna: Antenna basics]]
[[guide:antenna-field|Quick antennas from wire and paracord]]
