---
title: 8. Relays and stored messages
summary: Getting a message to a station you can't hear, through one you can; leaving messages to be picked up later
section: JS8 from zero
order: 80
---
HF paths come and go. You may hear W1AW clearly while N0CALL, who you need to reach, can't hear you at all, though W1AW hears both of you. JS8 has two ways around this.

# Relaying through another station
Put the relay station's callsign, a **>**, the destination's callsign and another **>**, then the message:

**W1AW>N0CALL>WATER RESUPPLY AT 1800**

(sent from your station, addressed to W1AW). What happens:
1. W1AW's station receives the whole message.
2. It passes it on to N0CALL, showing where it came from, along the lines of **W1AW: N0CALL>WATER RESUPPLY AT 1800 DE KX4ABC** ("DE" means "from").
3. When N0CALL's software has the whole message, it sends back an **ACK** (acknowledgement). No ACK means it didn't get through complete.

You can chain more than one relay (**A>B>C>MESSAGE**), but each hop takes time and adds a chance of failure. Use the shortest path that works.

> TIP: Before relaying, ask a candidate relay station **HEARING?** or **QUERY CALL N0CALL?** to check it can actually hear the destination.

# Leaving a message for later (store and forward)
Sometimes nobody can reach the destination right now. You can leave a message with a station that will hold it until the destination asks for it.

- **MSG** stores a message in the recipient's own inbox:
  **W1AW MSG CALL ME ON 40M AT 2100Z**
- **MSG TO:** asks a station to hold a message for someone else:
  **W1AW MSG TO:N0CALL CHECK IN WHEN YOU CAN**
  W1AW keeps it until N0CALL asks for it.
- **QUERY MSGS** asks a station whether it's holding anything for you:
  **W1AW QUERY MSGS**
  It answers with the ID of a waiting message, if any.
- **QUERY MSG [id]** asks for that message:
  **W1AW QUERY MSG 32**

Heartbeat replies can also say that a station is holding a message for you, so regular heartbeats double as a way of checking your "mailbox".

> NOTE: Whether a station stores, relays and answers these automatically depends on its software and settings. This app saves MSG messages addressed to you. Agree with your team beforehand which stations act as relays and mailboxes.

# Planning relays for a team
- Pick one or two well-placed stations (good antennas, central location, on the air often) as relays.
- Note who hears whom on the Team tab and the Map at different times of day.
- Keep relayed messages short and complete: put who it's for, what, where and when in one message.

[[app:team|Open the Team tab]]

[[guide:js8-09-groups-reports|Next: groups and reports]]
