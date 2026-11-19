---
title: 10. On-air manners and a full example contact
summary: How a real JS8 conversation goes, start to finish, with the habits that make a net run smoothly
section: JS8 from zero
order: 100
---
# A complete example
Here is a typical first contact, as it appears on the Traffic tab. KX4ABC (you) is in grid EM83; W1AW is several hundred kilometres away. Each line is one transmission; most take one or two time slots.

1. You've been listening on 40 m and see W1AW's heartbeat: **W1AW: @HB HEARTBEAT FN31**
2. You ask how well W1AW hears you: **KX4ABC: W1AW SNR?**
3. W1AW's software answers: **W1AW: KX4ABC SNR -15**. You're getting through, but not strongly.
4. You send a short message: **KX4ABC: W1AW GM. OPERATING PORTABLE 20W WIRE. HOW ARE CONDITIONS?**
5. W1AW (a person this time) replies: **W1AW: KX4ABC GM. YOU ARE WEAK BUT READABLE. BAND IS GOOD HERE. 73**
6. You finish: **KX4ABC: W1AW TU 73 SK**

Nothing more is needed. Note what made it work: you listened first, asked one thing at a time, kept messages short, and closed clearly.

# A team check-in
A net control station (NCS) collecting check-ins from a team on @TEAM:
1. NCS: **NC1S: @TEAM CHECK IN NOW. CHECKIN REPORTS PLEASE**
2. Members reply, one per slot or two, with report buttons: **W2AB: @TEAM #CHECKIN S:GOOD L:FN20** · **K3CD: @TEAM #CHECKIN S:NEED L:FN10 N:FUEL**
3. NCS watches the Team tab: who has checked in, who hasn't, who needs something.
4. NCS follows up directly with anyone missing: **NC1S: W4EF SNR?**, then a relay if needed.
5. NCS closes: **NC1S: @TEAM NET CLOSED. NEXT CHECK IN 2100Z ON 40M, BACKUP 80M**

# Habits that matter
- **Listen first.** Watch the waterfall and Traffic for a minute or two before you send.
- **Don't transmit on top of others.** Pick a clear offset (1,000 to 2,500 Hz) and stay there.
- **Short and complete.** Who, what, where, when, in as few words as make it clear.
- **Times in UTC** (also called Zulu, written Z). The clock on the Traffic tab shows UTC.
- **Emergency traffic first.** If someone sends an emergency or priority message, stop other traffic and help or stand by.
- **Identify.** In amateur radio your callsign must be sent regularly; JS8 puts it in every message for you.
- **Close the contact** with 73 and SK so the other station knows you're done.

# Common abbreviations
- **CQ:** calling anyone
- **DE:** from ("DE KX4ABC" = from KX4ABC)
- **GM / GA / GE:** good morning / afternoon / evening
- **TU / TNX:** thank you / thanks
- **73:** best regards; **SK:** end of contact
- **QSL:** I confirm receipt; **QSL?** did you receive?
- **RR:** received, understood
- **AGN?:** again, please repeat
- **QTH:** location; **QRP:** low power (5 W or less); **QRZ?:** who is calling me?
- **QSY:** change frequency ("QSY 80M" = let's move to 80 metres)
- **WX:** weather; **NCS:** net control station; **UTC / Z:** universal time

[[guide:js8-11-glossary|Next: glossary]]
