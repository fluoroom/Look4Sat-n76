package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.utility.matchesTransponderFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransponderFilterTest {

    @Test
    fun `uplink-only FM does not match an FM filter`() {
        assertFalse(
            matchesTransponderFilter(
                downlinkMode = "USB",
                info = "Linear",
                downlinkLow = 435_000_000L,
                uplinkLow = 145_000_000L,
                modes = listOf("FM", "FMN", "APRS", "SSTV"),
                bands = emptyList()
            )
        )
    }

    @Test
    fun `downlink FM matches an FM filter`() {
        assertTrue(
            matchesTransponderFilter(
                downlinkMode = "FM",
                info = "FM Repeater",
                downlinkLow = 435_600_000L,
                uplinkLow = 145_900_000L,
                modes = listOf("FM", "FMN", "APRS", "SSTV"),
                bands = emptyList()
            )
        )
    }

    @Test
    fun `APRS in info matches even when downlink mode is AFSK`() {
        assertTrue(
            matchesTransponderFilter(
                downlinkMode = "AFSK",
                info = "ISS APRS",
                downlinkLow = 145_825_000L,
                uplinkLow = 145_825_000L,
                modes = listOf("FM", "FMN", "APRS", "SSTV"),
                bands = emptyList()
            )
        )
    }

    @Test
    fun `radio without downlink frequency never matches`() {
        assertFalse(
            matchesTransponderFilter(
                downlinkMode = "FM",
                info = "Uplink only",
                downlinkLow = null,
                uplinkLow = 145_900_000L,
                modes = listOf("FM"),
                bands = emptyList()
            )
        )
    }

    @Test
    fun `mode and band must match on the same radio`() {
        assertFalse(
            matchesTransponderFilter(
                downlinkMode = "FM",
                info = "VHF FM",
                downlinkLow = 145_900_000L,
                uplinkLow = 145_800_000L,
                modes = listOf("FM"),
                bands = listOf("V/U")
            )
        )
        assertTrue(
            matchesTransponderFilter(
                downlinkMode = "FM",
                info = "V/U FM",
                downlinkLow = 435_600_000L,
                uplinkLow = 145_900_000L,
                modes = listOf("FM"),
                bands = listOf("V/U")
            )
        )
    }
}
