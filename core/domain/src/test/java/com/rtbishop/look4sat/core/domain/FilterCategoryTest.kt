package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.model.FilterCategory
import com.rtbishop.look4sat.core.domain.model.decodeFilterCategories
import com.rtbishop.look4sat.core.domain.model.encodeToString
import com.rtbishop.look4sat.core.domain.utility.matchesFilterCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCategoryTest {

    // "SSTV on V only" plus "FM on V/U", the two buckets that a flat mode x band filter conflates.
    private val sstv = FilterCategory("SSTV", listOf("FM", "SSTV"), listOf("V"))
    private val fmRepeaters = FilterCategory("FM", listOf("FM", "FMN"), listOf("V/U"))

    private fun matches(
        mode: String?,
        downlink: Long?,
        uplink: Long?,
        categories: List<FilterCategory>,
        info: String = ""
    ) = matchesFilterCategories(mode, info, downlink, uplink, categories)

    @Test
    fun `V-only FM downlink matches the SSTV category`() {
        assertTrue(matches("FM", 145_800_000L, null, listOf(sstv, fmRepeaters)))
    }

    @Test
    fun `V-up U-down FM repeater matches the FM category`() {
        assertTrue(matches("FM", 435_600_000L, 145_900_000L, listOf(sstv, fmRepeaters)))
    }

    @Test
    fun `V-only BPSK telemetry matches neither category`() {
        assertFalse(matches("BPSK", 145_900_000L, null, listOf(sstv, fmRepeaters)))
    }

    // The point of the band being pinned to the mode: this pairing is admitted by the flat
    // {FM,SSTV,FMN} x {V,V/U} cross product, but by neither category on its own.
    @Test
    fun `SSTV on V-U is not admitted by either category`() {
        assertFalse(matches("SSTV", 435_800_000L, 145_900_000L, listOf(sstv, fmRepeaters)))
    }

    @Test
    fun `an exclude category subtracts from the include results`() {
        val excludeAprs = FilterCategory("APRS", listOf("APRS"), exclude = true)
        val categories = listOf(sstv, fmRepeaters, excludeAprs)
        assertTrue(matches("FM", 145_800_000L, null, categories, info = "SSTV downlink"))
        assertFalse(matches("FM", 145_825_000L, null, categories, info = "ISS APRS digipeater"))
    }

    @Test
    fun `exclude-only filter keeps everything it does not name`() {
        val excludeUhf = FilterCategory("UHF", bands = listOf("U"), exclude = true)
        assertTrue(matches("FM", 145_800_000L, null, listOf(excludeUhf)))
        assertFalse(matches("FM", 435_800_000L, null, listOf(excludeUhf)))
    }

    @Test
    fun `no categories means no filtering`() {
        assertTrue(matches("BPSK", 145_900_000L, null, emptyList()))
    }

    @Test
    fun `a category with nothing selected is inert in both directions`() {
        val blank = FilterCategory("unfinished")
        assertTrue(matches("BPSK", 145_900_000L, null, listOf(blank)))
        assertTrue(matches("BPSK", 145_900_000L, null, listOf(blank.copy(exclude = true))))
        // Inert alongside a real include: it must neither widen nor hide the result.
        assertFalse(matches("BPSK", 145_900_000L, null, listOf(blank, sstv)))
        assertTrue(matches("FM", 145_800_000L, null, listOf(blank, sstv)))
    }

    @Test
    fun `a switched-off category is ignored but kept`() {
        // Off is not the same as deleted: the modes/bands survive for when it is switched back on.
        val parked = sstv.copy(enabled = false)
        assertEquals(sstv.modes, parked.modes)
        assertFalse(parked.isActive)

        // With the only include category off, nothing constrains the filter any more.
        assertTrue(matches("BPSK", 145_900_000L, null, listOf(parked)))
        // ...and a switched-off exclude stops subtracting.
        val excludeUhf = FilterCategory("UHF", bands = listOf("U"), exclude = true)
        assertFalse(matches("FM", 435_800_000L, null, listOf(excludeUhf)))
        assertTrue(matches("FM", 435_800_000L, null, listOf(excludeUhf.copy(enabled = false))))
    }

    @Test
    fun `switching one include off leaves the others filtering`() {
        val categories = listOf(sstv, fmRepeaters.copy(enabled = false))
        assertTrue(matches("FM", 145_800_000L, null, categories))
        // The V/U repeater only passed through the category that is now off.
        assertFalse(matches("FM", 435_600_000L, 145_900_000L, categories))
    }

    @Test
    fun `the enabled flag round-trips and defaults to on for older stored filters`() {
        val parked = sstv.copy(enabled = false)
        assertEquals(listOf(parked), decodeFilterCategories(listOf(parked).encodeToString()))
        // A filter stored before the flag existed has no "enabled" key and must come back active.
        val legacy = decodeFilterCategories("""[{"name":"Old","modes":["FM"],"bands":["V"]}]""")
        assertEquals(true, legacy?.single()?.enabled)
        assertTrue(legacy!!.single().isActive)
    }

    @Test
    fun `a radio without a downlink frequency never matches`() {
        assertFalse(matches("FM", null, 145_900_000L, listOf(sstv)))
        assertFalse(matches("FM", null, 145_900_000L, emptyList()))
    }

    @Test
    fun `categories survive a round trip through their stored form`() {
        val categories = listOf(sstv, fmRepeaters, FilterCategory("Odd, name", exclude = true))
        assertEquals(categories, decodeFilterCategories(categories.encodeToString()))
        assertNull(decodeFilterCategories(null))
        assertNull(decodeFilterCategories("not json"))
    }
}
