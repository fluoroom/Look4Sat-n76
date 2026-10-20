/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.source

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.MOON_CATNUM

object Sources {
    // Ordered from the most specific to the most generic source: the first source that knows a
    // satellite gets to name it, with the full catalog acting as the fallback.
    val satelliteDataUrls = listOf(
        "live.ariss.org/iss.txt",
        "r4uab.ru/satonline.txt",
        "mmccants.org/tles/classfd.zip",
        "amsat.org/tle/current/nasabare.txt",
        "celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv",
        "db.satnogs.org/api/tle/?format=3le"
    )
    val transceiversDataUrls = listOf(
        "r4uab.ru/transmitters.json",
        "db.satnogs.org/api/transmitters/?format=json&status=active"
    )
    val satelliteModes = listOf(
        "4FSK", "64-QAM", "AFSK", "AFSK TUBiX10", "AHRPT", "AM", "APT", "ASK", "BPSK",
        "BPSK PMT-A3", "CERTO", "CW", "DATV", "DBPSK", "DOKA", "DPSK", "DQPSK", "DSB", "DSTAR",
        "DUV", "DVB-S2", "FFSK", "FM", "FMN", "FSK", "FSK AX.100 Mode 5", "FSK AX.100 Mode 6",
        "FSK AX.25 G3RUH", "FT8", "GENESIS FSK", "GFSK", "GFSK Pkst", "GFSK Rktr", "GFSK/BPSK",
        "GMSK", "GMSK USP", "HRPT", "LoRa", "LRPT", "LSB", "MFSK", "MSK", "MSK AX.100 Mode 5",
        "MSK AX.100 Mode 6", "OFDM", "OQPSK", "PPM", "PSK", "PSK31", "PSK63", "QPSK", "QPSK31",
        "QPSK63", "SIDLOC", "SQPSK", "SSDV", "SSTV", "UNKNOWN", "USB", "WSJT"
    )

    /**
     * Built-in EME "transceivers": the usual activity frequencies of each band, CW near the bottom
     * of the EME segment and the WSJT modes (JT65/Q65) higher up. Uplink and downlink are the same
     * frequency, so the Doppler correction keeps the signal constant at the Moon (CFOM) and each
     * station tunes out only its own half of the shift, as the WSJT-X default does.
     */
    val moonRadios: List<SatRadio> = listOf(
        eme("50", "6 m EME JT65/Q65", 50_190_000L, "USB"),
        eme("144-cw", "2 m EME CW", 144_025_000L, "CW"),
        eme("144", "2 m EME JT65/Q65", 144_125_000L, "USB"),
        eme("432-cw", "70 cm EME CW", 432_025_000L, "CW"),
        eme("432", "70 cm EME JT65/Q65", 432_065_000L, "USB"),
        eme("1296-cw", "23 cm EME CW", 1_296_025_000L, "CW"),
        eme("1296", "23 cm EME JT65/Q65", 1_296_065_000L, "USB"),
        eme("2304", "13 cm EME CW/Q65", 2_304_100_000L, "CW"),
        eme("3400", "9 cm EME CW/Q65", 3_400_100_000L, "CW"),
        eme("5760", "6 cm EME CW/Q65", 5_760_100_000L, "CW"),
        eme("10368", "3 cm EME CW/Q65", 10_368_100_000L, "CW"),
        eme("24048", "1.2 cm EME CW/Q65", 24_048_100_000L, "CW")
    )

    private fun eme(id: String, info: String, frequency: Long, mode: String) = SatRadio(
        uuid = "moon-eme-$id", info = info, downlinkLow = frequency, downlinkMode = mode,
        uplinkLow = frequency, uplinkMode = mode, catnum = MOON_CATNUM
    )
}
