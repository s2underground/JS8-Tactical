---
title: 3. Building a dipole or inverted V
summary: Measure, cut, connect, raise and tune the most dependable antenna there is
section: Antennas
order: 30
---
The half-wave dipole is two equal lengths of wire with the feedline connected in the middle. It's cheap, works well, needs no tuner when cut right, and is the antenna to build first.

# What you need
- **Wire:** about 70 ft (21 m) for 40 m, 135 ft (41 m) for 80 m. Almost any copper wire works: stranded insulated wire (14 to 18 gauge) is easy to handle and doesn't kink. Insulation is fine and makes it a little shorter; you'll trim it anyway.
- **A centre connector:** a store-bought dipole centre or **1:1 balun**, or simply a strong insulator with the coax tied to it.
- **Two end insulators:** plastic, or anything that doesn't conduct (a piece of PVC pipe with two holes works).
- **Rope:** UV-resistant paracord or nylon line for the ends and to raise the centre.
- **Coax** to reach the radio, with the right connector (usually PL-259) on the radio end.
- **An SWR meter**, or a radio that shows SWR, or an antenna analyser.

# 1. Work out the lengths
Pick your band below. The calculator gives the total length and the length of each leg. **Cut each leg about 5% longer** than shown, then trim it later. You can always cut wire off; you can't add it back.

[[calc:dipole]]

# 2. Put it together
1. Thread each wire through its end insulator, fold back about 6 inches (15 cm) and twist it around itself. Don't cut the extra; it's your tuning margin.
2. Fix the other end of each leg to the centre connector or balun: one leg to the coax's centre conductor, the other to the braid (shield).
3. Seal the coax connection from rain (tape, self-amalgamating tape, or a plastic box). Water in coax slowly ruins it.
4. Add a **choke** where the coax meets the antenna: a 1:1 current balun, or 6–8 turns of the coax itself wound into a coil about 6 inches (15 cm) across, taped together. It stops the coax itself from radiating, which keeps RF out of your radio and your tablet.

[[diagram:choke|A choke keeps the signal on the antenna instead of on the outside of the coax.]]

# 3. Raise it
- **Flat dipole:** two supports (trees, poles, a building), one end to each, the centre hanging between them. Pull it up so the wire is fairly straight; a little sag is fine.
- **Inverted V:** one support in the middle. Raise the centre as high as you can, and slope the two legs down to stakes or low branches. Keep the angle at the top **90 degrees or wider**, and keep the ends at least 8 ft (2.5 m) off the ground: they carry high voltage.

[[diagram:inverted-v|An inverted V: one mast, legs sloping down, ends kept up out of reach.]]

> TIP: For a team in your region (NVIS), 15 to 30 feet (5 to 9 m) high is plenty on 40 m. For long distance, go higher.

[[guide:antenna-field|Getting a line over a tree branch: field techniques]]

# 4. Tune it
1. Set the radio to a low power (5–10 W) on the JS8 frequency of your band, in a mode that transmits a steady carrier (most radios have a TUNE button, or use FM or AM briefly).
2. Read the SWR. Then check it a little lower and a little higher in frequency to find where the SWR is lowest.
3. **Lowest SWR below the JS8 frequency?** The antenna is too long: shorten **both** legs by the same small amount (start with 2–3 inches / 5–8 cm each). **Lowest SWR above it?** It's too short: let out some of the folded-back wire.
4. Repeat until the SWR is below about 1.5 on your frequency. Below 2 is fine for most radios.

[[calc:swr]]

> NOTE: Nearby objects, the ground and the height all change the tuning slightly. Tune the antenna where and how it will actually be used.

# 5. Bring the coax in
Run the coax away from the antenna at right angles for as far as you can, then to the radio. Avoid coiling extra coax near the radio (except a deliberate choke). Keep runs short: long or thin coax wastes power, especially on higher bands.

[[guide:antenna-feedline|Next: feedline, SWR and connectors]]
