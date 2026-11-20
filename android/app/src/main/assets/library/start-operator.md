---
title: Setting up your station
summary: From a radio in a box to your first JS8 contact, step by step
section: Start here
order: 20
---
This guide takes you from "I have a radio" to "I sent a message and someone heard it". Work through it in order; each step links to the part of the app it talks about.

If radio is new to you, read the "JS8 from zero" series first; this guide assumes you know what a band, a dial frequency and a time slot are.

[[guide:js8-01-radio|JS8 from zero: radio in plain words]]

# What you need
- **An HF transceiver** that does USB (upper sideband). Almost all do.
- **An antenna** for the band you'll use, and the coax (feedline) to reach it. If you don't have one yet, the antenna guides show you how to build one from wire.
- **A power supply or battery** for the radio (most need about 13.8 volts).
- **A way to connect the radio to the tablet**: a USB cable for radios with built-in sound and control, or an audio interface. The app supports several radios directly under Settings, Radio.

# 1. Who and where you are
Enter your **callsign** and your **grid square** (a short location code like EM83). The app can fill in the grid from GPS. Your grid lets other stations, and this app's propagation predictions, know roughly where you are.

[[app:settings|Open Settings: Callsign and Grid Square]]

> NOTE: The grid you send is about 100 km across (four characters) or 5 km (six). Send the shorter one if you don't want to be precise.

# 2. Connect the radio
- **Rig control** lets the app change the radio's frequency and switch it to transmit. Pick your radio under Settings, Radio, Rig Control.
- **Without rig control**, set the radio's **VOX** on so it transmits when it hears the app's audio, and set the frequency by hand.
- Choose the audio device under Settings, Audio.

Set the radio to **USB**, turn off speech processing and noise reduction, and set its **power to about 20 watts** to begin. JS8 is designed for low power.

# 3. Set the clock
JS8 stations take turns in fixed time slots. The tablet's clock must be within about one second. Off-grid, turn on **Settings, Timing, Auto time sync**; the app corrects its clock from the signals it hears.

# 4. Put up an antenna and check it
A fair antenna matters more than a big radio. Read the antenna guides, build or set up your antenna, and check its SWR (most radios can show it).

[[guide:antenna-choosing|Which antenna should I use?]]

# 5. Listen first
On **Traffic**, switch the **Receiver** on, pick a band at the top, and watch the waterfall. Within a few minutes on an open band you should see stations on the Traffic tab. If you hear nothing, check the troubleshooting guide before transmitting.

[[guide:antenna-troubleshooting|Troubleshooting: I hear nothing / nobody hears me]]

[[app:prop|Check which band is open: Prop tab]]

# 6. Make your first contact
1. Send a **heartbeat** (or wait for the automatic one). Other stations that hear you may reply, and you'll see who heard you and how well.
2. Pick a station you can hear well on the Traffic tab and send them a short directed message, such as "SNR?" (asks how well they hear you).
3. Send a **#CHECKIN** report with the report buttons. It's a simple, structured "here's my status and location".

> TIP: The Prop tab learns from every station you hear and every station that hears you. The more you listen and send, the better its predictions for your own station become.

# Licences and the law
In most countries, transmitting on these frequencies requires an amateur radio licence. In the US, a Technician licence allows JS8 on 10 metres (28.078 MHz); a General licence covers the other JS8 frequencies. Rules also limit things like power and identification. This app reminds you of these things; it does not stop you. You are responsible for how you use your transmitter.

[[guide:antenna-safety|Before you transmit: antenna and RF safety]]
