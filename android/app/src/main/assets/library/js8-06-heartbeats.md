---
title: 6. Heartbeats, CQ and who hears whom
summary: How JS8 stations find each other automatically, and how to tell which band reaches whom
section: JS8 from zero
order: 60
---
# Heartbeats
A **heartbeat** is a short automatic transmission that says "my station is on, and here's my grid square":

**KX4ABC: @HB HEARTBEAT EM83 ♢**

Heartbeats are sent every so often (you choose how often under Settings, Heartbeat, or turn them off), in the **500 to 1,000 Hz** part of the audio so they stay out of the way of conversations.

Stations with automatic replies turned on may answer a heartbeat with a signal report:

**W1AW: KX4ABC HEARTBEAT SNR -14**

That tells you W1AW heard you at −14 dB. Over an hour of heartbeats you build a picture of who can hear you, on which band, at what time, without anyone typing anything.

[[app:settings|Settings: Heartbeat interval and Autoreply]]

> TIP: Heartbeats every 10 to 30 minutes are plenty. Very frequent heartbeats crowd the band for everyone.

# Calling CQ
**CQ** means "calling anyone". To start a conversation with whoever is listening:

**KX4ABC: @ALLCALL CQ CQ CQ EM83**

Anyone who hears you can reply to you directly. Give it a few minutes before calling again.

# Who hears whom
Knowing who can hear whom is the core skill of HF operating. JS8 gives you several tools:
- **SNR?** asks a station how well it hears you; it replies with a number.
- **HEARING?** asks a station for the list of stations it currently hears. If you can't reach someone directly, a station that hears both of you can relay (see the relay chapter).
- **QUERY CALL [callsign]?** asks a station whether it can hear a particular station.
- **Heartbeat replies** do the same automatically.

This app records all of this. Every station you hear, every report of how others hear you, and every HEARING list feeds the **Prop tab**, which learns how your station actually performs. The **Map** shows where stations are, and the **Team tab** shows whether you can likely reach each member right now.

A US station that never sends a grid can still appear on the Map, as a hollow ring marked **≈**: its grid square is looked up from the address on its FCC licence. That's right most of the time but not always (a portable station, a PO box, an old address), so it's only shown, never used as propagation evidence, sent to ATAK or transmitted. Turn it off under **Settings, Map**. (Suggested by doodlecomms.)

[[app:map|Open the Map]]
[[app:prop|Open the Prop tab]]

[[guide:js8-07-sending|Next: sending messages and commands]]
