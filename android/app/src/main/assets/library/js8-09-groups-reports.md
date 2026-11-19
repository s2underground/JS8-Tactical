---
title: 9. Groups and reports
summary: Sending to a whole team at once, and the structured reports that turn messages into map points and status boards
section: JS8 from zero
order: 90
---
# Groups
A **group** is a name beginning with **@** that several stations agree to listen for, such as **@TEAM** or **@NORTHNET**. A message to a group reaches every station that has the group in its list:

**KX4ABC: @TEAM ALL STATIONS CHECK IN BY 1900Z**

- Add the groups you belong to under **Settings, My Groups**. The Traffic tab shows them at the top of the station list, with their messages together in one thread.
- **@ALLCALL** is a built-in group that means everyone.
- Group names should be short (they take space in the frame) and agreed in advance.

[[app:settings|Settings: My Groups]]

# Reports
JS8-Tactical has report buttons that build short, structured messages. Each starts with a **#TAG** and uses one-letter field names, so it's compact on air and still readable by a person using ordinary JS8Call:

- **#CHECKIN** S (status: GOOD, NEED or EMERG), L (location), N (note).
  Example: **#CHECKIN S:NEED L:EM83KX N:WATER FOR 4**
- **#SITREP** S (GREEN, AMBER or RED), L (location), R (needs), N (notes).
- **#SPOT** S (size), A (activity), L (location), T (time).
- **#SALUTE** S (size), A (activity), L (location), U (unit or who), T (time), E (equipment).
- **#POSREP** L (location), S (status, e.g. moving or holding), T (time).

Locations can be a grid square, MGRS or latitude/longitude. The report writer fills in your position and the time, and shows how many frames the report will take before you send it.

When this app receives a report:
- it shows as a card with labelled fields on the Traffic tab,
- its location appears on the **Map** (and in ATAK, if you've turned that on),
- check-ins and SITREPs feed the **Team tab** and the **Digest**, so needs and emergencies stand out.

[[app:digest|Open the Digest]]

> TIP: Send one report rather than a long chat. "#CHECKIN S:GOOD L:EM83" takes a single frame or two; a sentence saying the same thing takes several.

[[guide:js8-10-procedure|Next: on-air manners and a full example contact]]
