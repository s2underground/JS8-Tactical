---
title: 8. Troubleshooting and first contact
summary: "I hear nothing", "nobody hears me", high SWR, and a checklist to get on the air
section: Antennas
order: 80
---
# I hear nothing
Work from the tablet towards the antenna:
1. **Is the app listening?** Traffic: the **Receiver** switch at the top is on. The waterfall should move.
2. **Is there audio?** A completely black waterfall means no audio: check the audio device (Settings, Audio device), the cable, and the radio's volume. An all-bright waterfall means too much: turn the volume down.
3. **Is the radio set right?** USB mode, on a JS8 dial frequency, filters wide (2.4 kHz or more), noise reduction off.
4. **Is the clock right?** Signals visible on the waterfall but nothing decodes usually means the clock is off by more than a second or two. Turn on Settings, Timing, Auto time sync, or use the clock options in Traffic's ⋮ menu (Raw decodes, clock and audio details).
5. **Is the band open?** Check the Prop tab. At night the higher bands (20 m and up) often go quiet; try 40 m or 80 m.
6. **Is the antenna connected?** Unplug the coax: if the noise level doesn't drop, the antenna isn't doing anything. Check connectors and the centre connection.

[[app:prop|Check the Prop tab]]

# I can hear others, but nobody hears me
1. **Are you transmitting?** Watch the radio's TX light and power meter while the app sends. If nothing happens, check rig control or VOX and the audio output device.
2. **Is there power out?** Low or no power with the TX light on usually means the audio level is too low (or too high, which the radio may cut). Adjust the app's output level and the radio's data or mic gain until you get the power you set, without ALC action.
3. **Check SWR.** High SWR makes many radios cut their power.
4. **Check the clock.** If your clock is off, you transmit at the wrong time and others can't decode you.
5. **Look at what others report.** If any station replies with an SNR report or lists you in a HEARING reply, the Prop tab records it. Weak reports mean more power, a better antenna or a better band.

# High SWR
- **Changes a lot when you touch or move the coax:** add a choke at the antenna.
- **Lowest SWR well below your frequency:** antenna too long; shorten both legs equally.
- **Lowest SWR well above your frequency:** antenna too short; let out wire.
- **High everywhere:** broken connection, short circuit, water in the coax, or the wrong band. Check each connector.

[[calc:swr]]

# First-contact checklist
1. Callsign and grid square set (Settings).
2. Clock within a second (auto time sync on if off-grid).
3. Antenna up, SWR checked, coax connected and dry.
4. Radio in USB on the JS8 frequency, about 20 W, speech processor and noise reduction off.
5. Prop tab checked for the best band.
6. Listen for a few minutes: stations appear on Traffic.
7. Send a heartbeat, then a short message to a station you hear well ("SNR?").
8. Send a **#CHECKIN** with the report button.

[[app:settings|Open Settings]]

> TIP: Every station you hear and every station that hears you teaches the Prop tab about your own station. Keep the app listening whenever you can.
