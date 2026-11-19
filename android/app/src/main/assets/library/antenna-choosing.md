---
title: 2. Which antenna should I use?
summary: Near or far, one support or two, home or field: choosing the right antenna for the job
section: Antennas
order: 20
---
There's no best antenna, only the best one for the distance you need to cover and the supports you have. Start with the question: **how far away are the people you need to talk to?**

# Nearby: up to about 400 km (250 miles)
For a team spread across a region, use **NVIS**: Near Vertical Incidence Skywave. A dipole is mounted **low**, so it sends most of its signal straight up. The signal bounces off the ionosphere and comes back down all around you, filling in the whole area from right next door out to a few hundred kilometres. Hills and valleys matter much less, because the signal comes down from above.

[[diagram:nvis|NVIS: a low antenna sends the signal nearly straight up; it returns all around you.]]

- **Bands:** 80 m and 60 m at night, 40 m in daytime (when the sun is high enough). Higher bands go straight through the ionosphere and don't come back.
- **Antenna:** a dipole or inverted V, about 0.1 to 0.25 wavelength above the ground. That's roughly 14 to 35 feet for 40 m.
- **Bonus:** a low antenna is quick to put up, hard to see, and a lot less work than a tall one.

[[calc:nvis]]

# Far: hundreds to thousands of kilometres
For long distance you want the opposite: signal leaving at a **low angle** towards the horizon, so it travels a long way before the first bounce. That needs a **higher** antenna (half a wavelength or more above the ground) or a vertical.

[[diagram:skywave|Higher antennas send a low-angle signal that lands far away, skipping over the area in between.]]

- **Bands:** 40 m and 20 m by day, 40 m and 80 m at night over long paths. The Prop tab shows which is open.
- **Antenna:** a dipole as high as you can get it, an end-fed wire up into a tall tree, or a vertical with radials.
- **Catch:** there's a **skip zone**. Stations closer than the first bounce may not hear you at all, even though stations much farther away do.

# Which way does it send?
A dipole high above the ground sends best **broadside** (at right angles to the wire) and poorly off its ends. If your people are mostly in one direction, run the wire across that direction. A low NVIS dipole sends almost equally all around, so direction matters little.

[[diagram:dipole-pattern|Top view of a dipole's pattern.]]

# Quick chooser
- **Team in my region, two supports:** NVIS dipole.
- **One tall support only (a tree or mast):** inverted V (NVIS or regional) or end-fed half-wave (regional and far).
- **Several bands from one wire:** end-fed half-wave cut for 40 m (also works on 20, 15 and 10 m).
- **Small space, far stations:** quarter-wave vertical with radials.
- **On the move, in a vehicle:** a whip; for nearby stations tie it back horizontally (see the field guide).

[[guide:antenna-dipole|Next: building a dipole]]
[[guide:antenna-efhw|End-fed and vertical antennas]]
[[guide:antenna-field|Field-expedient antennas]]

> TIP: Set the antenna you actually use under the Prop tab's "My station" button. The predictions then account for how your antenna sends its signal.
