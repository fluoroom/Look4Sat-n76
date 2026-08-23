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
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio

// Band letter assigned to a frequency in Hz.
// Boundaries follow ITU designations; L/S are split from the wider UHF range
// so that 430 MHz → "U" and 1.27 GHz → "L" as expected in amateur radio.
fun frequencyToBand(hz: Long): String = when {
    hz < 30_000_000L -> "H"           // HF  < 30 MHz
    hz < 300_000_000L -> "V"          // VHF 30–300 MHz  (2 m ≈ 144 MHz)
    hz < 1_000_000_000L -> "U"        // UHF 300 MHz–1 GHz  (70 cm ≈ 435 MHz)
    hz < 2_000_000_000L -> "L"        // L-band 1–2 GHz  (23 cm ≈ 1268 MHz)
    hz < 4_000_000_000L -> "S"        // S-band 2–4 GHz  (13 cm ≈ 2400 MHz)
    else -> "X"                        // X-band and above
}

// Returns the band-pair config string for a transponder, e.g. "V/U", "V", "U/U".
// Format: "{uplinkBand}/{downlinkBand}" when uplink exists, else "{downlinkBand}".
// Returns null when no downlink frequency is known.
fun transponderBandConfig(downlinkHz: Long?, uplinkHz: Long?): String? {
    val dlBand = downlinkHz?.let { frequencyToBand(it) } ?: return null
    val ulBand = uplinkHz?.let { frequencyToBand(it) }
    return if (ulBand != null) "$ulBand/$dlBand" else dlBand
}

// Fixed list of common amateur-satellite band configurations shown in the filter dialog.
val allBandConfigs: List<String> = listOf(
    "V", "U", "L", "S",
    "V/V", "V/U", "U/V", "U/U",
    "L/V", "L/U", "S/V", "S/U"
)

// Same predicate used for pass inclusion and the radar transponder list.
// A satellite is kept only if it has at least one radio that would actually be shown:
// alive downlink, selected mode (downlink or APRS-in-info), and selected band on that same radio.
fun matchesTransponderFilter(
    downlinkMode: String?,
    info: String,
    downlinkLow: Long?,
    uplinkLow: Long?,
    modes: List<String>,
    bands: List<String>
): Boolean {
    if (downlinkLow == null) return false
    val modeOk = modes.isEmpty() ||
        (downlinkMode != null && downlinkMode in modes) ||
        ("APRS" in modes && info.contains("APRS", ignoreCase = true))
    if (!modeOk) return false
    if (bands.isEmpty()) return true
    return transponderBandConfig(downlinkLow, uplinkLow) in bands
}

fun SatRadio.matchesTransponderFilter(modes: List<String>, bands: List<String>): Boolean =
    matchesTransponderFilter(downlinkMode, info, downlinkLow, uplinkLow, modes, bands)
