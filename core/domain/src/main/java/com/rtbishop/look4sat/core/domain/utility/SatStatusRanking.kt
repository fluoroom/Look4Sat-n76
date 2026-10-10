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
import com.rtbishop.look4sat.core.domain.model.SatStatusPage

/** Ordering offered by the AMSAT status list. */
enum class SatStatusSort { Name, LastHeard, BestHeard }

/** Width of one AMSAT status slot, used to turn [SatStatusRating.freshnessRank] into an age. */
const val STATUS_SLOT_HOURS = 2

/**
 * What the reports say about one satellite.
 *
 * "Not Heard" and conflicting reports are deliberately left out of the count: they are often
 * filed for a pass that was never going to work, so counting them would punish a healthy
 * satellite for its observers' luck rather than for its own payload. For the same reason they
 * are no evidence of when a satellite was last heard, so they earn it nothing but a place just
 * above the satellites nobody reported on at all, see [trustTier].
 */
data class SatStatusRating(
    val heard: Int,
    val telemetryOnly: Int,
    /** Index of the newest slot holding a heard or telemetry report; -1 when there is none. */
    val freshnessRank: Int,
    /** Index of the newest slot holding a report of any kind; -1 when the satellite has none. */
    val anyReportRank: Int = freshnessRank
) {
    val sampleCount: Int get() = heard + telemetryOnly

    /** 2 = heard or telemetry on record, 1 = reported on but never heard, 0 = no data. */
    val trustTier: Int
        get() = when {
            sampleCount > 0 -> 2
            anyReportRank >= 0 -> 1
            else -> 0
        }

    /** Share of usable reports that were voice-grade. Null when nothing usable came in. */
    val heardRatio: Double? get() = if (sampleCount == 0) null else heard.toDouble() / sampleCount

    /** Hours since it was last heard, rounded down to the slot grid; null when it never was. */
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
    var freshnessRank = -1
    slots.forEachIndexed { index, slot ->
        for (id in slot.reportIds) {
            val text = reports[id]?.statusText ?: continue
            when (categoryOfReport(text)) {
                SatStatusCategory.Active -> heard++
                SatStatusCategory.TelemetryOnly -> telemetryOnly++
                // "Not Heard" and conflicting reports stay out of the count, see SatStatusRating.
                SatStatusCategory.NotHeard, SatStatusCategory.Conflicting -> continue
            }
            if (freshnessRank < 0) freshnessRank = index
        }
    }
    return SatStatusRating(heard, telemetryOnly, freshnessRank, slots.indexOfFirst { it.count > 0 })
}

/**
 * The report kinds the AMSAT legend shows, and the unit the status filters work in.
 *
 * "Crew Active" is the ISS crew working voice, so it lands in [Active] alongside "Heard".
 * Anything unrecognised is [Conflicting], matching the deep-orange bucket the AMSAT site uses.
 */
enum class SatStatusCategory { Active, TelemetryOnly, NotHeard, Conflicting }

/** The kinds that prove a satellite is alive: voice-grade, or at least a telemetry beacon. */
val HEARD_CATEGORIES = setOf(SatStatusCategory.Active, SatStatusCategory.TelemetryOnly)

/** Classifies one report's status text. Mirrors the colouring in the data layer. */
fun categoryOfReport(text: String): SatStatusCategory = when {
    text.contains("Not Heard", ignoreCase = true) -> SatStatusCategory.NotHeard
    text.contains("Heard", ignoreCase = true) || text.contains("Crew Active", ignoreCase = true) ->
        SatStatusCategory.Active
    text.contains("Telemetry", ignoreCase = true) || text.contains("Beacon", ignoreCase = true) ->
        SatStatusCategory.TelemetryOnly
    else -> SatStatusCategory.Conflicting
}

/** Every report kind filed for this satellite, which is what an "at least one" filter tests. */
fun SatStatus.categories(reports: Map<String, SatReport>): Set<SatStatusCategory> =
    days.asSequence()
        .flatMap { it.slots.asSequence() }
        .flatMap { it.reportIds.asSequence() }
        .mapNotNull { id -> reports[id]?.statusText?.let(::categoryOfReport) }
        .toSet()

/**
 * Keeps the satellites with at least one report in [selected]. An empty selection is no filter,
 * so the list never collapses to nothing just because the chips were all switched off.
 */
fun List<SatStatus>.filteredByCategories(
    selected: Set<SatStatusCategory>,
    reports: Map<String, SatReport>
): List<SatStatus> {
    if (selected.isEmpty()) return this
    return filter { status -> status.categories(reports).any { it in selected } }
}

/** Designators (see [AmSatNameMatch]) of the satellites reported with at least one of [selected]. */
fun SatStatusPage.amSatKeysReportedAs(selected: Set<SatStatusCategory>): Set<String> =
    statuses.filter { status -> status.categories(reports).any { it in selected } }
        .flatMapTo(HashSet()) { AmSatNameMatch.amSatKeys(it.name) }

/**
 * The strongest thing reported about each of [satelliteNames] (orbital-data names): heard beats
 * telemetry only, which beats not heard. A satellite AMSAT says nothing usable about is left out.
 */
fun SatStatusPage.bestCategories(satelliteNames: Collection<String>): Map<String, SatStatusCategory> {
    val ranked = listOf(SatStatusCategory.Active, SatStatusCategory.TelemetryOnly, SatStatusCategory.NotHeard)
    val reported = statuses.mapNotNull { status ->
        val kinds = status.categories(reports)
        val best = ranked.firstOrNull { it in kinds } ?: return@mapNotNull null
        AmSatNameMatch.amSatKeys(status.name) to best
    }
    val result = HashMap<String, SatStatusCategory>()
    for (name in satelliteNames.toSet()) {
        // AMSAT lists some satellites once per payload ("ISS-FM", "ISS-DATA"): take the best of them.
        val keys = AmSatNameMatch.satelliteKeys(name)
        reported.filter { (amSatKeys, _) -> keys.any { it in amSatKeys } }
            .minByOrNull { (_, category) -> ranked.indexOf(category) }
            ?.let { (_, category) -> result[name] = category }
    }
    return result
}

/**
 * Orders the list for display. The data-driven orderings rank by [SatStatusRating.trustTier]
 * first: satellites that were heard, then the ones only ever reported as not heard, then the
 * ones with no data, so neither of the last two floats to the top on an empty score.
 */
fun List<SatStatus>.sortedForDisplay(
    sort: SatStatusSort,
    ratings: Map<String, SatStatusRating>
): List<SatStatus> {
    val byName = compareBy<SatStatus> { it.name.lowercase() }
    val byTrustTier = compareByDescending<SatStatus> { ratings[it.name]?.trustTier ?: 0 }
    return when (sort) {
        SatStatusSort.Name -> sortedWith(byName)
        SatStatusSort.LastHeard -> sortedWith(
            byTrustTier
                // -1 means "never heard": map it past every real rank instead of ahead of them.
                .thenBy { ratings[it.name]?.freshnessRank?.takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
                .then(byName)
        )
        SatStatusSort.BestHeard -> sortedWith(
            byTrustTier
                .thenByDescending { ratings[it.name]?.rankingScore ?: 0.0 }
                .thenByDescending { ratings[it.name]?.sampleCount ?: 0 }
                .then(byName)
        )
    }
}

/** Ratings for every satellite in the list, keyed by name. */
fun List<SatStatus>.rateAll(reports: Map<String, SatReport>): Map<String, SatStatusRating> =
    associate { it.name to it.rate(reports) }
