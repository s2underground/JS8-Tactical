---
title: Connecting your radio over USB
summary: Cables, ports and settings for radios with a USB connection, with notes for the Xiegu X6100 and X6200
section: Start here
order: 30
---
Many radios have a single USB socket that carries both **rig control** (frequency and transmit) and **sound**. Plugged into the tablet, such a radio shows up as two things: a **USB serial port** for control and a **USB audio device** for sound. This guide covers getting both working.

[[guide:start-operator|Back to: setting up your station]]

# The cable
A tablet only powers and talks to a USB device when it acts as the "host". That takes an **OTG** (on-the-go) adapter or cable.

- Put the **OTG adapter at the tablet end**, not the radio end.
- If the radio has a USB-C socket, a plain USB-C to USB-C cable often does **not** work. Use a **USB-A to USB-C** cable with the OTG adapter (USB-C plug, USB-A socket) on the tablet.
- When it's right, Android asks whether to let the app use the USB device. Say yes. If nothing pops up, the tablet isn't seeing the radio: check the cable, the adapter and the radio's port first.

# Settings in the app
1. **Settings, Radio, Rig Control:** choose **Serial (Hamlib)**.
2. **Hamlib Rig Model:** your radio's model.
3. **Hamlib Serial Port:** leave it on **Auto**, or pick the port by hand. Some radios have two serial ports on one USB chip, and only one of them talks to the radio. Auto tries each one and remembers the one that answers.
4. **Hamlib Baud Rate:** must match the radio's CAT setting.
5. **Settings, Audio:** pick the radio's **USB audio device**, not the tablet's microphone. Transmit audio goes to the same device.

[[app:settings|Open Settings]]

# Checking it works
- **Control:** change the band in the app and watch the radio's frequency follow.
- **Receive:** the waterfall should change when you tune the radio or change the band. If it looks the same as the room's sound, the app is still listening to the tablet's microphone.
- **Transmit:** send a short heartbeat with the radio into a dummy load or a tuned antenna, and watch its power meter.

# No power on transmit
If the radio switches to transmit but shows no power, the app's sound isn't reaching it, or is too quiet:
- Check that the audio device in the app is the radio's USB audio.
- Press **volume up** on the tablet while the radio is connected. Android keeps a separate volume for a USB audio device.
- Raise the radio's **USB or line input level** (the name depends on the radio).
- Turn the radio's **VOX** off. The app keys the radio over CAT, and with VOX on, any sound the tablet makes (a notification, say) can key the transmitter.

# Xiegu X6100 and X6200
- Use the radio's **DEV** port, with a **USB-A to USB-C** cable and the OTG adapter at the tablet end. (The **HOST** port is for plugging things into the radio.)
- The radio shows up as a dual serial port ("USB Dual_Serial") and a USB audio device.
- **Rig Control:** Serial (Hamlib). **Rig Model:** Xiegu X6200 (or X6100). **Baud rate:** 19200.
- **Serial Port:** Auto, or **Port 1**. Port 0 doesn't answer; control is on Port 1.
- Set the radio to **USB-D**.
- On the open firmware, if you get no power, raise **Settings, Audio, Line-in** (0 to 36) a few steps at a time.

> NOTE: These notes come from testing on an X6200. If your radio needed something different, please report it so this guide can be corrected.

# No radio: an RTL-SDR dongle
An **RTL-SDR** USB dongle (about $30–40) receives JS8 without a radio: no sound card, no cables beyond the antenna. It **can't transmit**, so it's for listening: following a net, checking a band, or learning before you have a radio.

1. Plug the dongle into the tablet with the **OTG adapter**, and connect an **HF antenna** to it (for an RTL-SDR Blog V3, the HF antenna goes on its normal SMA input).
2. **Settings, Radio:** turn on **Rig Control** and choose **Rig Type: RTL-SDR dongle (receive only)**.
3. Start the receiver. Android asks to let the app use the USB device: say yes.
4. Pick a band as usual: the dongle tunes to it.

Which dongle:
- **RTL-SDR Blog V4:** the easiest for HF. Its built-in upconverter handles everything; leave **HF sampling** on Automatic.
- **RTL-SDR Blog V3** (and most other RTL2832U dongles): HF works through **direct sampling**, which Automatic turns on below 28.8 MHz. It's clearest below 14.4 MHz (80, 40, 30, 20 m). Above that (17, 15, 12, 10 m) it still works, receiving a mirror image of the band, with more interference from other signals.

Settings, under **RTL-SDR (receive only)**:
- **Frequency correction (ppm):** 0 for TCXO dongles like the Blog V3 and V4. A cheap dongle can be tens of ppm off; if stations decode only at the edges, adjust this.
- **RF gain:** blank for automatic. If strong local stations swamp the waterfall, set a lower number. Direct sampling (V3 on HF) doesn't use it.

# Airspy HF+
The **Airspy HF+** (Discovery or Dual Port) is a much better HF receiver than an RTL-SDR, and needs no settings: choose **Rig Type: Airspy HF+ (receive only)**, connect the antenna to its HF input, plug it in with the OTG adapter and start the receiver. It covers every HF band up to 10 m (its HF input stops at 31 MHz, so not 6 m), with its own automatic gain.

# Receive only
With either dongle, anything you try to send says the SDR is receive only, and heartbeats and automatic replies are skipped. To transmit, choose your radio again under **Settings, Radio**.

> NOTE: SDR support was suggested by doodlecomms. It's new: if your dongle doesn't work, please send a diagnostic report.

