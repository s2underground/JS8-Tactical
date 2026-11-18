# Propagation model calibration

The Prop tab's model (`android/.../prop/Propagation.kt`) is a compact
MINIMUF-style skywave model whose coefficients were fitted to real reception
reports. This folder holds everything needed to repeat or audit that.

## Data
- **WSPR spots** from [wspr.live](https://wspr.live) (every spot reported to
  wsprnet.org), used under its terms: non-commercial research whose results
  are free for everyone; GET-only, time/band-filtered queries, ≤20/minute.
  `fetch_wspr.py` runs on GitHub Actions (`prop-data.yml`, manual) and keeps a
  fixed hash sample of stations (1 in 25 transmitters, 1 in 5 receivers),
  rolled up per hour. Output is published to the `prop-data` release, not
  shipped in the app.
  - `recent`: 5th and 20th of each month, Dec 2024 – Nov 2026, plus the last 7 days (SFI 100–232).
  - `minimum`: 5th and 20th of each month, 2019–2020 (solar minimum, SFI ~67–100).
- **Solar/geomagnetic indices**: GFZ Potsdam Kp/ap/Ap/SN/F10.7 file (CC BY 4.0).

## Method
1. `build_pairs.py`: for each hour and band, every sampled transmitter that
   was on the air × every sampled receiver that was listening is a pair;
   "heard" if that receiver spotted that transmitter. Negatives subsampled 8 %
   (weighted back). ~1.9 M pairs, ~0.94 M heard.
2. `fit.py` / `evaluate.py`: the model's path score is multiplied by a
   detection layer (band, power, distance — how likely a WSPR receiver logs an
   open path) and fitted by weighted log-likelihood (PyTorch). Held out from
   fitting: every 4th sampled day, the most recent week, and 1 in 5
   transmitting stations.
3. `final_fit.py` refits on all data; `export.py` rounds the coefficients,
   builds the display calibration and prints the golden values used by
   `PropagationModelTest`.

## Results (held-out days and stations)
Ranking quality of the path score (AUC: 0.5 = coin flip, 1.0 = perfect):

| Band | v1 (first build) | v2 (fitted) |
|---|---|---|
| 160m | 0.68 | 0.79 |
| 80m | 0.59 | 0.79 |
| 60m | 0.55 | 0.71 |
| 40m | 0.45 | 0.79 |
| 30m | 0.39 | 0.81 |
| 20m | 0.48 | 0.79 |
| 17m | 0.55 | 0.77 |
| 15m | 0.55 | 0.78 |
| 12m | 0.55 | 0.74 |
| 10m | 0.52 | 0.74 |
| All | 0.55 | 0.81 |

Fitted only on 2024–2026, v2 scored 0.81 on the unseen 2019–2020 minimum
(v1: 0.51). Share of all actual receptions that happened on paths the model
called *closed*: v1 46 %, v2 13 % (mostly 10–12 m; 40 m and below ≤ 4 %).

What changed from v1: winter-day / summer-night seasonal terms, the F2
layer lagging the sun by ~2 h, geomagnetic-latitude terms (auroral cut,
equatorial anomaly), stronger D-layer absorption at low frequencies and
steep paths, wider uncertainty around the MUF, longer ground-wave reach,
and a sporadic-E term (summer, mid-latitude, 400–2300 km, strongest on
the high bands).

## Known limits
- WSPR stations are mostly low power with modest antennas; JS8 signals
  decode at a similar level, so the open/closed picture carries over, but
  the absolute chance of a contact depends on your station.
- 10–12 m remain the weakest (some F2 long-haul and TEP openings missed).
- Daily F10.7 is the solar input; a 27-day average fitted no better.

## Offline hardening (validated on the same WSPR data)
Most stations will run air-gapped, so each offline piece was tested before
it was built:

| Question | Script | Result |
|---|---|---|
| How much do stale or wrong solar numbers hurt? | `validate_stale_solar.py` | Ranking quality 0.814 with the true daily SFI; 0.811–0.815 with values 1–60 days old or a fixed 120; 0.785 with a fixed 70. Kp unknown: −0.001 overall, −0.004 during storms. |
| Can recent net traffic tune the sky model? | `validate_live_tune.py` | A 25-station local net's last 3 hours, used to scale the MUF (0.8–1.2, prior ≈ 5 observations), lowered next-hour log-loss 0.1796 → 0.1783 and never made it worse. Positive-only reports alone: 0.1796 → 0.1794. A per-day "perfect knowledge" scale only reached ~0.8 % better, so day-to-day ionosphere swings are a small effect. |
| How should power and JS8 speed shift predictions? | `validate_snr_margin.py` | From ~950 k spot SNRs: share of WSPR-decodable paths still decodable at −26/−24/−22/−20/−18 dB: 0.95/0.88/0.79/0.70/0.62; below the floor about +4.4 % per dB. Used for `StationModel`. |
| Does learning a station's own quirks help? | `validate_station_learning.py` | Per band × distance log-odds offsets (prior sd 0.5) learned from 4 days improved the next 3 days for 145 of 158 receivers (median log-loss 0.151 → 0.131); 38 of 45 even with < 100 heard spots. |

In the app (`prop/Learning.kt`, `prop/Predictor.kt`): listening hours per
band are logged so "not heard" counts as evidence; other stations' SNR
reports and HEARING replies are logged as net paths; NOAA's 27-day outlook and
a daily flux history are saved whenever online; and `#SWX F:.. K:.. T:..`
lines let stations pass solar numbers to each other over the air.
