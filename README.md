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
**v4.4.11**) and adds **direct VGC N76 Bluetooth radio control**, an **APRS** tab, a category-based
transceiver filter, AMSAT status sorting, filters and per-pass icons, GPS position and time sync,
and extra SSTV audio sources.

### VGC N76 Bluetooth radio control

In **Settings → Bluetooth output**, switch the **N76 device** row on and choose the paired handheld
from the list; its options appear underneath once it is on. On the radar transceiver list,
**Connect** and **Track** push Doppler-corrected RX/TX frequencies to the radio for the whole pass.

| Option | What it does |
|---|---|
| Send satellite info | Shows satellite name, azimuth and elevation on the radio display |
| Sat firmware ≥ 137 | 16-byte SATELLITE-mode frames; off falls back to 14-byte exact-frequency mode for older firmware |
| Poll interval | 250 ms – 15 s, in 250 ms steps; 10 s by default |
| Send station position on connect | Hands the radio the app's station position, so APRS has one before the radio's own GPS gets a fix |
| Send TX power | High / Medium / Low, sent with the satellite frequencies. The level values are not confirmed against the radio: check its power indicator |
| Open squelch while tracking | Switches the radio's monitor on at track start and off at stop. The radio only offers a toggle, so the monitor must be off when you connect |
| Forced RX/TX CTCSS | Pins a tone from the standard CTCSS table, independently per direction |
| RFCOMM RX audio | Second RFCOMM channel, HDLC-framed SBC decoded to 32 kHz mono PCM |
| Speaker monitor | Plays the received HT audio out of the phone |
| Record HT / mic | Records either or both streams, optionally only while tracking a satellite. Files are stereo: phone mic left, HT right |
| Phone mic / gain | Which phone microphone to record, and a boost of up to +24 dB to level it with the HT |
| Output folder | User-chosen folder for recordings |

Every option is independently bypassable, so a failing feature can be switched off without losing
radio control.

The expanded transceiver panel also exposes **hold-to-PTT**, monitor, record, and
play-last-recording. SBC decoding uses a bundled decoder (`libsbc.so`, arm64-v8a only).

> Radio control is reverse-engineered against the N76 vendor protocol. Frequencies are pushed to
> the radio — check what it is transmitting on before you key up.

### APRS

The tracking screen has an **APRS** tab next to SSTV. It sends a position (or, with no location, a
status) report and logs every packet sent and received in raw `CALL>DEST,PATH:text` form.

* **Fields**: callsign-SSID, message, path (default `ARISS,SGATE,WIDE2-2`), icon and location.
  The location is typed (`lat, lon` or a grid locator) or filled once from the **GPS** button; it
  never follows the phone on its own.
* **Beacon every N s** repeats Send on a timer you switch on by hand (30 s minimum).

| Connection | How it sends | Receives |
|---|---|---|
| N76 | Asks the radio for its own beacon, built from the callsign, path, message and icon stored **in the radio**; set the radio's digital channel to *Current* | no |
| BT TNC | The app's packet as KISS to any Bluetooth TNC, by address | yes, with **Listen** on |
| Audio | The app's packet as 1200-baud tones on a chosen audio output; the radio needs VOX | yes, from a chosen audio input (including the N76 HT audio) |

> Transmitting needs a licence and a callsign. The audio decoder is a simple one and misses weak
> signals; nothing here has been tested on the air beyond the author's own station.

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

### AMSAT status: sorting, filters and pass icons

The AMSAT status list can be sorted by **Name**, **Last heard**, or **Best heard**. Both rank
satellites that were heard first, then those only ever reported as not heard, then those with no
reports: a "Not Heard" report is never taken as the time a satellite was last heard.

The pass filter has an **Only AMSAT heard** switch (heard or beacon/telemetry only), and every
pass in the list carries an icon: tick for heard, radio for beacon only, cross for not heard, dot
for no data. Without a connection the filter does nothing and the icons stay on "no data".

*Best heard* scores `Heard / (Heard + Telemetry Only)`, counting "Crew Active" as heard and
ignoring "Not Heard" reports — those are often filed for a pass that was never going to work.
Ordering uses a Laplace-smoothed ratio so a single lucky report cannot outrank a long good record;
the percentage shown is the true ratio, with the sample count beside it.

> There is no time-range option. The AMSAT API clamps reports to the most recent 500 (~33 h),
> ignores a wider `hours` parameter, and offers no pagination, so 1-week/1-month/1-year choices
> would all rank identical data. Widening the window needs locally accumulated history.

### Data updates, GPS position and GPS time

In **Settings → Other**:

* **Auto-update interval**: 15 min to 24 h, checked while the app is running. Short intervals may
  get you rate-limited by the data providers.
* **Auto GPS**: takes the station position from a fresh GPS fix on app start, pass-list refresh
  and track start. It overwrites a hand-entered position.
* **GPS time**: corrects the app's clock from the same fix, for passes and Doppler. Android does
  not let an app set the phone's clock, so the correction lives inside the app only.

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
