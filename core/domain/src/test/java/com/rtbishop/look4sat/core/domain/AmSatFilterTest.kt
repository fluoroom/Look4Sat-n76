package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.model.SatDay
import com.rtbishop.look4sat.core.domain.model.SatReport
import com.rtbishop.look4sat.core.domain.model.SatSlot
import com.rtbishop.look4sat.core.domain.model.SatStatus
import com.rtbishop.look4sat.core.domain.utility.AmSatNameMatch
import com.rtbishop.look4sat.core.domain.utility.SatStatusCategory
import com.rtbishop.look4sat.core.domain.utility.categories
import com.rtbishop.look4sat.core.domain.utility.categoryOfReport
import com.rtbishop.look4sat.core.domain.utility.filteredByCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmSatFilterTest {

    private val reports = mutableMapOf<String, SatReport>()
    private var nextId = 0

    private fun sat(name: String, vararg texts: String): SatStatus {
        val ids = texts.map { text ->
            val id = "r${nextId++}"
            reports[id] = SatReport(id, text, "CALL", "AA00", "2026-10-06", "12:00 UTC")
            id
        }
        val slots = (0 until 36).map { index ->
            if (index == 0) SatSlot(0L, ids.size, ids) else SatSlot(0L, 0)
        }
        val days = (0 until 3).map { d -> SatDay("day$d", slots.subList(d * 12, (d + 1) * 12)) }
        return SatStatus(name, days)
    }

    @Test
    fun `report texts map onto the legend buckets`() {
        assertEquals(SatStatusCategory.Active, categoryOfReport("Heard"))
        assertEquals(SatStatusCategory.Active, categoryOfReport("Crew Active"))
        assertEquals(SatStatusCategory.TelemetryOnly, categoryOfReport("Telemetry Only"))
        assertEquals(SatStatusCategory.NotHeard, categoryOfReport("Not Heard"))
        assertEquals(SatStatusCategory.Conflicting, categoryOfReport("Something else"))
    }

    @Test
    fun `a satellite keeps every kind it was reported with`() {
        val status = sat("AO-91", "Heard", "Telemetry Only", "Heard")
        assertEquals(
            setOf(SatStatusCategory.Active, SatStatusCategory.TelemetryOnly),
            status.categories(reports)
        )
    }

    @Test
    fun `filtering keeps satellites with at least one selected kind`() {
        val heard = sat("AO-91", "Heard")
        val telemetry = sat("AO-7", "Telemetry Only")
        val silent = sat("SO-50", "Not Heard")
        val all = listOf(heard, telemetry, silent)

        val active = all.filteredByCategories(setOf(SatStatusCategory.Active), reports)
        assertEquals(listOf("AO-91"), active.map { it.name })

        val activeOrTelemetry = all.filteredByCategories(
            setOf(SatStatusCategory.Active, SatStatusCategory.TelemetryOnly), reports
        )
        assertEquals(listOf("AO-91", "AO-7"), activeOrTelemetry.map { it.name })
    }

    @Test
    fun `an empty selection is no filter`() {
        val all = listOf(sat("AO-91", "Heard"), sat("SO-50", "Not Heard"))
        assertEquals(all, all.filteredByCategories(emptySet(), reports))
    }

    @Test
    fun `a satellite with no reports at all is filtered out`() {
        val all = listOf(sat("AO-91"))
        assertTrue(all.filteredByCategories(setOf(SatStatusCategory.NotHeard), reports).isEmpty())
    }

    @Test
    fun `amsat names match the orbital data names carrying the same designator`() {
        assertTrue(AmSatNameMatch.matches("AO-91 (FOX-1B)", AmSatNameMatch.amSatKeys("AO-91[FM]")))
        assertTrue(AmSatNameMatch.matches("SO-50 (SAUDISAT 1C)", AmSatNameMatch.amSatKeys("SO-50[FM]")))
        assertTrue(AmSatNameMatch.matches("ISS (ZARYA)", AmSatNameMatch.amSatKeys("ISS-FM")))
        assertTrue(AmSatNameMatch.matches("ISS (ZARYA)", AmSatNameMatch.amSatKeys("ISS-DATA")))
        // The designator can live in the parenthesised half of either name.
        assertTrue(AmSatNameMatch.matches("FOX-1A (AO-85)", AmSatNameMatch.amSatKeys("AO-85[FM]")))
        assertTrue(AmSatNameMatch.matches("DIWATA-2B (PO-101)", AmSatNameMatch.amSatKeys("PO-101[FM]")))
    }

    @Test
    fun `designator prefixes do not match each other`() {
        assertFalse(AmSatNameMatch.matches("AO-73 (FUNCUBE-1)", AmSatNameMatch.amSatKeys("AO-7[A]")))
        assertFalse(AmSatNameMatch.matches("AO-7 (AMSAT-OSCAR 7)", AmSatNameMatch.amSatKeys("AO-73")))
        assertFalse(AmSatNameMatch.matches("CAS-4A", AmSatNameMatch.amSatKeys("CAS-5A[A]")))
        assertFalse(AmSatNameMatch.matches("SWISSCUBE", AmSatNameMatch.amSatKeys("ISS-FM")))
        assertFalse(AmSatNameMatch.matches("STARLINK-1007", AmSatNameMatch.amSatKeys("AO-7[A]")))
    }
}
