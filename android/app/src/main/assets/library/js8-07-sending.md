---
title: 7. Sending messages and commands
summary: How to address a message, the standard JS8 commands and what they do, and how to keep traffic short and clear
section: JS8 from zero
order: 70
---
# Addressing a message
On the Traffic tab, pick who you're sending to (tap a station or group in the list, or type it), then type the message. The app adds your callsign as the sender automatically. What goes on the air looks like:

**KX4ABC: W1AW ROAD NORTH IS CLOSED. USE ROUTE 9.**

Keep it short. Every 12 or so characters is another frame, and in Normal speed each frame is 15 seconds. The calculator in the "time slots" chapter shows exactly how long a message will take.

[[guide:js8-04-time|How long will my message take?]]

# Standard commands
JS8 has built-in short commands. Other stations' software often answers them automatically, even when nobody is at the radio. Send them as **[callsign] [command]**, for example **W1AW SNR?**. The Traffic tab's quick buttons insert the common ones.

Questions (a "?" asks):
- **SNR?** How well do you hear me? (Reply: SNR −12)
- **GRID?** What is your grid square?
- **INFO?** What is your station? (Reply might be: INFO 50W VERTICAL)
- **STATUS?** What is your status message?
- **HEARING?** Which stations do you hear?
- **QSL?** Did you get my last transmission?
- **AGN?** Please send your last message again.
- **HW CPY?** How do you copy (understand) me?
- **QUERY CALL N0CALL?** Can you hear N0CALL directly?

Short answers and courtesies (each fits in one frame):
- **QSL:** I received your last transmission.
- **RR:** Roger, received.
- **YES** / **NO**
- **FB:** Fine business (good).
- **73:** Best regards (a friendly sign-off).
- **SK:** End of contact.

# Acknowledgements
For some kinds of message (relays, stored messages), the receiving station's software automatically sends back an **ACK** when it has the **whole** message. No ACK means it didn't arrive complete; send it again later or by another route.

# Good habits
- **Listen before you send.** Make sure the spot you're using is clear and the station you're calling isn't busy with someone else.
- **One question at a time.** Wait for the answer before sending more.
- **Use plain words and standard abbreviations.** No slang that a stressed or tired operator might misread.
- **Numbers and places exactly:** grid squares, MGRS or latitude/longitude, times in UTC (Z).
- **If you get no answer,** wait at least a few slots before repeating; the other station may be sending to someone else, or band conditions may have changed. Check the Prop tab or try a different band.

> NOTE: Report buttons (CHECKIN, SPOT, SALUTE, SITREP, POSREP) build a structured message for you. See the groups and reports chapter.

[[guide:js8-08-relay|Next: relays and stored messages]]
