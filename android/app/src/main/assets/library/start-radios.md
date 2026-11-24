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
