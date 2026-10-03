# Look4Sat: Satellite tracker

[![Look4Sat CI](https://github.com/fluoroom/Look4Sat-n76/actions/workflows/release.yml/badge.svg)](https://github.com/fluoroom/Look4Sat-n76/actions/workflows/release.yml)

### N76 fork of Look4Sat, for using the VGC N76 over Bluetooth.

### !!! This fork must be downloaded from [releases page](https://github.com/fluoroom/Look4Sat-n76/releases), NOT app stores, until (maybe) the main Look4Sat project adopts these changes.

<img src="https://play.google.com/intl/en_gb/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"> <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">

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

This fork tracks [upstream Look4Sat](https://github.com/rt-bishop/Look4Sat) (AMSAT status, custom data sources, IC-705 CAT, Doppler, AOS/elevation filters) and adds **direct HYS N76 Bluetooth radio control** plus a few filter and SSTV extras. The old satlib HTTP API is gone — the HT is driven over Bluetooth, with no local Hamlib or HTTP bridge.

### HYS N76 Bluetooth

In Settings → radio control, pick **N76 BT**, then the paired handheld. On the radar transponder list, **Connect** and **Track** send Doppler-corrected RX/TX frequencies and optional satellite name / az / el to the radio.

Optional N76 settings (all independently bypassable):

* Sat firmware ≥137 (16-byte SAT mode) vs older exact-frequency mode
* Poll interval (250 ms–10 s)
* Forced RX/TX CTCSS
* RFCOMM RX audio for SSTV and digimodes, with optional speaker monitor
* Record HT audio and/or phone mic, including auto-record while tracking

The expanded transponder panel also exposes hold-to-PTT, monitor, record, and play-last-recording.

### Transponder filters

Mode and band filters on the passes screen (e.g. FM, APRS, V/U) are built from the modes present on the selected satellites. The same filter is applied to the radar transponder list, so only matching radios are shown.

### SSTV

The decoder can take audio from **Microphone**, **Line-in / Unprocessed**, **Bluetooth SCO**, **Internal audio**, or **N76 HT (direct)**. Choose the source in the SSTV control bar before recording.

Auto mode can lock mid-image from line timing and will retune when the pulse family changes (Scottie → Martin) instead of painting the next header as shredded lines.

---

## Star History

<a href="https://star-history.dera.page/#rt-bishop/Look4Sat&type=timeline&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&legend=top-left" />
   <img alt="Star History Chart" src="https://star-history.dera.page/svg?repos=rt-bishop/Look4Sat&type=timeline&legend=top-left" />
 </picture>
</a>
