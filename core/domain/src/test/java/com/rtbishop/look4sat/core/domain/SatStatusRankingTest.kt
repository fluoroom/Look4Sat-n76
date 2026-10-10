package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.model.SatDay
import com.rtbishop.look4sat.core.domain.model.SatReport
import com.rtbishop.look4sat.core.domain.model.SatSlot
import com.rtbishop.look4sat.core.domain.model.SatStatus
import com.rtbishop.look4sat.core.domain.utility.SatStatusSort
import com.rtbishop.look4sat.core.domain.utility.rate
import com.rtbishop.look4sat.core.domain.utility.rateAll
import com.rtbishop.look4sat.core.domain.utility.sortedForDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatStatusRankingTest {

    private val reports = mutableMapOf<String, SatReport>()
    private var nextId = 0

    /** Builds a satellite from report texts per 2-hour slot, newest slot first. */
    private fun sat(name: String, vararg slotReports: List<String>): SatStatus {
        val slots = (0 until 36).map { index ->
            val texts = slotReports.getOrElse(index) { emptyList() }
            val ids = texts.map { text ->
                val id = "r${nextId++}"
                reports[id] = SatReport(id, text, "CALL", "AA00", "2026-10-03", "12:00 UTC")
                id
            }
            SatSlot(statusColor = 0L, count = ids.size, reportIds = ids)
        }
        val days = (0 until 3).map { d -> SatDay("day$d", slots.subList(d * 12, (d + 1) * 12)) }
        return SatStatus(name, days)
    }

    @Test
    fun `heard and crew active count as heard, telemetry counts against, not heard is ignored`() {
        val status = sat(
            "ISS",
            listOf("Heard", "Crew Active"),
            listOf("Telemetry Only"),
            listOf("Not Heard", "Not Heard")
        )
        val rating = status.rate(reports)
        assertEquals(2, rating.heard)
        assertEquals(1, rating.telemetryOnly)
        // Not Heard is excluded entirely, so the sample is 3 reports and not 5.
        assertEquals(3, rating.sampleCount)
        assertEquals(2.0 / 3.0, rating.heardRatio!!, 1e-9)
    }

    @Test
    fun `a satellite with only Not Heard reports has no usable ratio`() {
        val rating = sat("DEAD", listOf("Not Heard")).rate(reports)
        assertNull(rating.heardRatio)
        assertEquals(0, rating.sampleCount)
        // It was reported on, but a Not Heard report says nothing about when it was last heard.
        assertNull(rating.hoursSinceLastReport)
        assertEquals(0, rating.anyReportRank)
        assertEquals(1, rating.trustTier)
    }

    @Test
    fun `a newer Not Heard report does not refresh the last-heard time`() {
        val rating = sat("FADING", listOf("Not Heard"), emptyList(), listOf("Heard")).rate(reports)
        assertEquals(2, rating.freshnessRank)
        assertEquals(0, rating.anyReportRank)
    }

    @Test
    fun `freshness comes from the newest slot holding a report`() {
        val never = sat("NEVER").rate(reports)
        assertEquals(-1, never.freshnessRank)
        assertNull(never.hoursSinceLastReport)

        // Nothing in slots 0-1, one report in slot 2 => 2 slots x 2h = 4h ago.
        val stale = sat("STALE", emptyList(), emptyList(), listOf("Heard")).rate(reports)
        assertEquals(2, stale.freshnessRank)
        assertEquals(4, stale.hoursSinceLastReport)
    }

    @Test
    fun `a single lucky report does not outrank a long good record`() {
        val lucky = sat("LUCKY", listOf("Heard"))
        val solid = sat("SOLID", *Array(10) { listOf("Heard", "Heard", "Heard", "Telemetry Only") })
        val ratings = listOf(lucky, solid).rateAll(reports)

        // Raw ratio favours the one-report satellite...
        assertEquals(1.0, ratings["LUCKY"]!!.heardRatio!!, 1e-9)
        assertTrue(ratings["SOLID"]!!.heardRatio!! < 1.0)
        // ...but smoothing puts the satellite with an actual track record first.
        val sorted = listOf(lucky, solid).sortedForDisplay(SatStatusSort.BestHeard, ratings)
        assertEquals(listOf("SOLID", "LUCKY"), sorted.map { it.name })
    }

    @Test
    fun `satellites with no usable reports sink to the bottom of the best-heard sort`() {
        val good = sat("GOOD", listOf("Heard", "Heard"))
        val silent = sat("AAA-SILENT")
        val onlyNotHeard = sat("BBB-NOTHEARD", listOf("Not Heard"))
        val all = listOf(silent, onlyNotHeard, good)
        val sorted = all.sortedForDisplay(SatStatusSort.BestHeard, all.rateAll(reports))
        // Not Heard only is worth just more than no data at all, whatever the names say.
        assertEquals(listOf("GOOD", "BBB-NOTHEARD", "AAA-SILENT"), sorted.map { it.name })
    }

    @Test
    fun `last-heard sort runs newest first and parks never-reported satellites last`() {
        val fresh = sat("FRESH", listOf("Heard"))
        val older = sat("OLDER", emptyList(), emptyList(), listOf("Telemetry Only"))
        val never = sat("AAA-NEVER")
        // Reported on a moment ago, but only as Not Heard: that must not beat a real reception.
        val notHeard = sat("AAB-NOTHEARD", listOf("Not Heard"))
        val all = listOf(never, notHeard, older, fresh)
        val sorted = all.sortedForDisplay(SatStatusSort.LastHeard, all.rateAll(reports))
        assertEquals(listOf("FRESH", "OLDER", "AAB-NOTHEARD", "AAA-NEVER"), sorted.map { it.name })
    }

    @Test
    fun `name sort ignores case`() {
        val all = listOf(sat("zz-sat"), sat("AO-7"), sat("so-50"))
        val sorted = all.sortedForDisplay(SatStatusSort.Name, all.rateAll(reports))
        assertEquals(listOf("AO-7", "so-50", "zz-sat"), sorted.map { it.name })
    }

    @Test
    fun `sorting never drops or duplicates a satellite`() {
        val all = listOf(sat("A", listOf("Heard")), sat("B"), sat("C", listOf("Telemetry Only")))
        val ratings = all.rateAll(reports)
        for (sort in SatStatusSort.entries) {
            val sorted = all.sortedForDisplay(sort, ratings)
            assertEquals(all.size, sorted.size)
            assertEquals(all.map { it.name }.toSet(), sorted.map { it.name }.toSet())
        }
    }
}
