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
package com.rtbishop.look4sat.core.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class DatabaseState(
    val numberOfRadios: Int,
    val numberOfSatellites: Int,
    val updateTimestamp: Long
)

data class PassesSettings(
    val showDeepSpace: Boolean = true,
    val hoursAhead: Int,
    val minElevation: Double,
    val aosStartMinute: Int = 0,
    val aosEndMinute: Int = 23 * 60 + 59,
    val invertAosTimeWindow: Boolean = false,
    val categories: List<FilterCategory> = emptyList()
)

/**
 * One clause of the transceiver filter: a mode set AND a band set that must both match on the
 * same radio. Categories combine as OR-of-ANDs, with [exclude] ones subtracted from the result:
 * a radio passes when it matches at least one include category and no exclude category.
 *
 * This lets a single filter hold "FM on V only" (SSTV) alongside "FM on V/U" (repeaters) without
 * the mode x band cross product also admitting the pairings you never asked for.
 */
@Serializable
data class FilterCategory(
    val name: String = "",
    val modes: List<String> = emptyList(),
    val bands: List<String> = emptyList(),
    val exclude: Boolean = false,
    val enabled: Boolean = true
) {
    /** A category that constrains nothing is ignored, so an unfinished one can never hide everything. */
    val isEmpty: Boolean get() = modes.isEmpty() && bands.isEmpty()

    /** Only active categories reach the filter; the rest are kept so they can be switched back on. */
    val isActive: Boolean get() = enabled && !isEmpty
}

private val filterCategoryJson = Json { ignoreUnknownKeys = true }

fun List<FilterCategory>.encodeToString(): String = filterCategoryJson.encodeToString(this)

/** Returns null when [value] is absent or unparsable, so callers can fall back to a migration. */
fun decodeFilterCategories(value: String?): List<FilterCategory>? {
    if (value.isNullOrBlank()) return null
    return try {
        filterCategoryJson.decodeFromString<List<FilterCategory>>(value)
    } catch (_: Exception) {
        null
    }
}

data class RCSettings(
    val rotatorState: Boolean,
    val rotatorAddress: String,
    val rotatorPort: String,
    val rotatorFormat: String,
    val frequencyState: Boolean,
    val frequencyAddress: String,
    val frequencyPort: String,
    val frequencyFormat: String,
    val frequencyOffsetHz: Long = 0L,
    val bluetoothRotatorState: Boolean,
    val bluetoothRotatorFormat: String,
    val bluetoothRotatorName: String,
    val bluetoothRotatorAddress: String,
    val bluetoothFrequencyState: Boolean,
    val bluetoothFrequencyFormat: String,
    val bluetoothFrequencyAddress: String
)

data class OtherSettings(
    val stateOfAutoUpdate: Boolean,
    val stateOfSensors: Boolean,
    val stateOfSweep: Boolean,
    val stateOfUtc: Boolean,
    val stateOfLightTheme: Boolean,
    val stateOfNightMode: Boolean = false,
    val shouldSeeWarning: Boolean,
    val shouldSeeWhatsNew: Boolean,
    val sstvMode: String = "Auto",
    val audioSource: AudioSource = AudioSource.Mic,
    val lowElevation: Double = 15.0,
    val highElevation: Double = 45.0,
    val radarCompassOffset: Float = 0f,
    val radarCompassOffsetElev: Float = 0f
)

/**
 * Data source URLs (TLE / transceivers) and their per-source enabled flags.
 * [satelliteEnabled] and [transceiversEnabled] must be positionally aligned with the
 * corresponding URL list: the flag at index [i] applies to the URL at index [i].
 * Missing flags are treated as enabled (see [isSatelliteEnabled]/[isTransceiverEnabled]).
 */
data class DataSourcesSettings(
    val satelliteUrls: List<String>,
    val transceiversUrls: List<String>,
    val satelliteEnabled: List<Boolean> = emptyList(),
    val transceiversEnabled: List<Boolean> = emptyList()
) {
    fun isSatelliteEnabled(index: Int): Boolean = satelliteEnabled.getOrElse(index) { true }
    fun isTransceiverEnabled(index: Int): Boolean = transceiversEnabled.getOrElse(index) { true }
}

data class RadioControlSettings(
    val enabled: Boolean,
    val radioModel: String,
    val txRadioAddress: String,
    val rxRadioAddress: String,
    val txRadioName: String,
    val rxRadioName: String,
    val baudRate: Int,
    /** IC-705 only: use single-radio split-VFO mode instead of two radios. */
    val splitMode: Boolean = false
) {
    companion object {
        const val MODEL_YAESU_FT817   = "Yaesu FT-817/818"
        const val MODEL_YAESU_FT857   = "Yaesu FT-857/897"
        const val MODEL_ICOM_IC705    = "Icom IC-705"
        const val MODEL_N76           = "VGC N76"

        /** Label used for the N76 before it was identified as a VGC; still in stored settings. */
        const val LEGACY_MODEL_N76    = "HYS N76"

        val SUPPORTED_RADIOS = listOf(
            MODEL_YAESU_FT817, MODEL_YAESU_FT857, MODEL_ICOM_IC705, MODEL_N76
        )

        /** Baud rates available for Yaesu radios. */
        val BAUD_RATES_YAESU = listOf(4800, 9600, 38400)
        /** Baud rates available for Icom IC-705 (higher speeds supported via CI-V USB/BT). */
        val BAUD_RATES_ICOM  = listOf(4800, 9600, 19200, 38400, 57600, 115200)

        /**
         * Maps a stored radio model onto the current label set. The N76 was labelled
         * [LEGACY_MODEL_N76] until it was identified as a VGC, and that is the string sitting
         * in existing installs' preferences. Normalizing on read keeps their radio selected,
         * instead of falling through to a Yaesu and silently switching N76 control off.
         */
        fun normalizeModel(stored: String?): String = when {
            stored.isNullOrBlank() -> MODEL_YAESU_FT817
            stored == LEGACY_MODEL_N76 -> MODEL_N76
            else -> stored
        }
    }
}
