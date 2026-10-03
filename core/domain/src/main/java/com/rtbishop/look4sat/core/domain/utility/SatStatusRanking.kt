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

import com.rtbishop.look4sat.core.domain.model.SatReport
import com.rtbishop.look4sat.core.domain.model.SatStatus

/** Ordering offered by the AMSAT status list. */
enum class SatStatusSort { Name, LastHeard, BestHeard }

/** Width of one AMSAT status slot, used to turn [SatStatusRating.freshnessRank] into an age. */
const val STATUS_SLOT_HOURS = 2

/**
 * What the reports say about one satellite.
 *
 * "Not Heard" and conflicting reports are deliberately left out of the count: they are often
 * filed for a pass that was never going to work, so counting them would punish a healthy
 * satellite for its observers' luck rather than for its own payload.
 */
data class SatStatusRating(
    val heard: Int,
    val telemetryOnly: Int,
    /** Index of the newest slot holding any report; -1 when the satellite has none at all. */
    val freshnessRank: Int
) {
    val sampleCount: Int get() = heard + telemetryOnly

    /** Share of usable reports that were voice-grade. Null when nothing usable came in. */
    val heardRatio: Double? get() = if (sampleCount == 0) null else heard.toDouble() / sampleCount

    /** Hours since the newest report, rounded down to the slot grid; null when never reported. */
    val hoursSinceLastReport: Int? get() = if (freshnessRank < 0) null else freshnessRank * STATUS_SLOT_HOURS

    /**
     * [heardRatio] pulled toward the middle by one notional report either way (Laplace smoothing),
     * used for ordering only. Without it a satellite with a single lucky report would outrank one
     * heard 34 times out of 41; the displayed figure stays the true ratio.
     */
    val rankingScore: Double get() = (heard + 1).toDouble() / (sampleCount + 2)
}

/** Counts a satellite's reports. Slots run newest to oldest, so the first non-empty one is latest. */
fun SatStatus.rate(reports: Map<String, SatReport>): SatStatusRating {
    val slots = days.flatMap { it.slots }
    var heard = 0
    var telemetryOnly = 0
    for (slot in slots) {
        for (id in slot.reportIds) {
            val text = reports[id]?.statusText ?: continue
            when {
                isHeardReport(text) -> heard++
                isTelemetryReport(text) -> telemetryOnly++
            }
        }
    }
    return SatStatusRating(heard, telemetryOnly, slots.indexOfFirst { it.count > 0 })
}

// "Crew Active" is the ISS crew working voice, so it counts the same as "Heard".
private fun isHeardReport(text: String) =
    (text.contains("Heard", ignoreCase = true) && !text.contains("Not", ignoreCase = true)) ||
        text.contains("Crew Active", ignoreCase = true)

private fun isTelemetryReport(text: String) =
    text.contains("Telemetry", ignoreCase = true) || text.contains("Beacon", ignoreCase = true)

/**
 * Orders the list for display. Satellites with nothing to go on sink to the bottom of the
 * data-driven orderings rather than floating to the top on an empty score.
 */
fun List<SatStatus>.sortedForDisplay(
    sort: SatStatusSort,
    ratings: Map<String, SatStatusRating>
): List<SatStatus> {
    val byName = compareBy<SatStatus> { it.name.lowercase() }
    return when (sort) {
        SatStatusSort.Name -> sortedWith(byName)
        SatStatusSort.LastHeard -> sortedWith(
            // -1 means "never reported": map it past every real rank instead of ahead of them.
            compareBy<SatStatus> { ratings[it.name]?.freshnessRank?.takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
                .then(byName)
        )
        SatStatusSort.BestHeard -> sortedWith(
            compareByDescending<SatStatus> { ratings[it.name]?.sampleCount?.coerceAtMost(1) ?: 0 }
                .thenByDescending { ratings[it.name]?.rankingScore ?: 0.0 }
                .thenByDescending { ratings[it.name]?.sampleCount ?: 0 }
                .then(byName)
        )
    }
}

/** Ratings for every satellite in the list, keyed by name. */
fun List<SatStatus>.rateAll(reports: Map<String, SatReport>): Map<String, SatStatusRating> =
    associate { it.name to it.rate(reports) }
