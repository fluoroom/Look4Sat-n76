# Look4Sat: Satellite tracker

[![Look4Sat CI](https://github.com/fluoroom/Look4Sat-n76/actions/workflows/release.yml/badge.svg)](https://github.com/fluoroom/Look4Sat-n76/actions/workflows/release.yml)

### N76 fork of Look4Sat, for using the VGC N76 over Bluetooth.

### !!! This fork must be downloaded from [releases page](https://github.com/fluoroom/Look4Sat-n76/releases), NOT app stores, until (maybe) the main Look4Sat project adopts these changes.

### Radio satellite tracker and pass predictor for Android, inspired by Gpredict

<p float="left">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="192"/>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="192"/>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="192"/>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="192">
</p>

### Track satellite passes with ease!

Thanks to [Celestrak](https://celestrak.com/) and [SatNOGS](https://satnogs.org/) you have access to over 9000 active satellites.\
You can search the entire database by NORAD Catalog Number or the satellite's name.

Orbital positions and passes are calculated relative to your location.\
To get reliable data make sure to set the station position via the app Settings.

The application is built using Kotlin, Coroutines, Jetpack Compose and Navigation.\
It is now and always will be completely ad-free and open-source.

## Main features:

*  Predicting satellite positions and passes for up to 10 days
*  Showing the list of currently active and upcoming satellite passes
*  Showing the active pass progress, polar trajectory and transceivers info
*  Showing the satellite positional data, footprint and ground track on the map
*  Custom TLE satellite data import is available via Three Line Element .txt files
*  Offline first: calculations are made offline. Weekly TLE data update is recommended.

## This fork

This fork tracks [upstream Look4Sat](https://github.com/rt-bishop/Look4Sat) (currently merged up to
**v4.4.8**) and adds **direct VGC N76 Bluetooth radio control**, a category-based transceiver
filter, AMSAT status sorting, and extra SSTV audio sources.

The old satlib HTTP API is gone — the handheld is driven over Bluetooth RFCOMM, with no local
Hamlib, no HTTP bridge, and no extra companion app.

### VGC N76 Bluetooth radio control

In **Settings → Radio control**, pick **VGC N76**, then choose the paired handheld from the
Bluetooth device list. On the radar transceiver list, **Connect** and **Track** push
Doppler-corrected RX/TX frequencies to the radio for the whole pass.

| Option | What it does |
|---|---|
| Send satellite info | Shows satellite name, azimuth and elevation on the radio display |
| Sat firmware ≥ 137 | 16-byte SATELLITE-mode frames; off falls back to 14-byte exact-frequency mode for older firmware |
| Poll interval | 250 ms – 10 s, in 250 ms steps |
| Forced RX/TX CTCSS | Pins a tone from the standard CTCSS table, independently per direction |
| RFCOMM RX audio | Second RFCOMM channel, HDLC-framed SBC decoded to 32 kHz mono PCM |
| Speaker monitor | Plays the received HT audio out of the phone |
| Record HT / mic | Records either or both streams, optionally only while tracking a satellite |
| Output folder | User-chosen folder for recordings |

Every option is independently bypassable, so a failing feature can be switched off without losing
radio control.

The expanded transceiver panel also exposes **hold-to-PTT**, monitor, record, and
play-last-recording. SBC decoding uses a bundled decoder (`libsbc.so`, arm64-v8a only).

> Radio control is reverse-engineered against the N76 vendor protocol. Frequencies are pushed to
> the radio — check what it is transmitting on before you key up.

### Transceiver filter categories

Upstream filters transceivers with one flat mode list. This fork replaces that with **filter
categories**: each category pins a **mode set** to a **band set**, and categories combine as
OR-of-ANDs with exclusions subtracted. A transceiver passes when it matches at least one *include*
category and no *exclude* category.

That makes combinations possible that a flat `modes × bands` filter cannot express:

| Category | Modes | Bands | Mode |
|---|---|---|---|
| SSTV | FM, SSTV | V | include |
| FM repeaters | FM, FMN | V/U | include |
| Telemetry | BPSK, GMSK | *any* | exclude |

A flat filter asked for `{FM, SSTV, FMN} × {V, V/U}` would also admit SSTV-on-V/U and FM-on-V,
dragging telemetry in. Pinning the band to the mode per category keeps the two buckets separate.

* Band configurations are `{uplink}/{downlink}` letter pairs — `V`, `U`, `L`, `S`, `V/V`, `V/U`,
  `U/V`, `U/U`, `L/V`, `L/U`, `S/V`, `S/U` — derived from each radio's actual frequencies.
* Matching is **per-radio, not per-satellite**: excluding telemetry drops the ISS telemetry
  downlinks while keeping the ISS itself for its FM/SSTV downlink.
* Categories can be **switched off** without being deleted, so a seasonal filter can be parked.
* The same filter drives both the passes list and the radar transceiver list, so you only see the
  radios you asked for.

### AMSAT status sorting

The AMSAT status list can be sorted by **Name**, **Last heard**, or **Best heard**.

*Best heard* scores `Heard / (Heard + Telemetry Only)`, counting "Crew Active" as heard and
ignoring "Not Heard" reports — those are often filed for a pass that was never going to work.
Ordering uses a Laplace-smoothed ratio so a single lucky report cannot outrank a long good record;
the percentage shown is the true ratio, with the sample count beside it.

> There is no time-range option. The AMSAT API clamps reports to the most recent 500 (~33 h),
> ignores a wider `hours` parameter, and offers no pagination, so 1-week/1-month/1-year choices
> would all rank identical data. Widening the window needs locally accumulated history.

### SSTV

The decoder can take audio from any of:

* **Microphone**
* **Line-in / Unprocessed**
* **Bluetooth SCO**
* **Internal audio** (via MediaProjection, so another app's audio can be decoded)
* **N76 HT (direct)** — straight off the RFCOMM audio channel, no acoustic coupling

Pick the source in the SSTV control bar before recording.

**Auto mode** can lock on mid-image from line timing, and retunes when the pulse family changes
(Scottie → Martin) instead of painting the next header as shredded lines. The image buffer grows
on demand rather than being fixed to one mode's line count.

---

## Star History

<a href="https://star-history.dera.page/#rt-bishop/Look4Sat&type=timeline&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&legend=top-left" />
   <img alt="Star History Chart" src="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&legend=top-left" />
 </picture>
</a>
