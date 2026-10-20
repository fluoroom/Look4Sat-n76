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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.MOON_ORBITAL_DATA
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.repository.IAmSatRepository
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

class MoonPassesTest {

    private val start = Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()

    @Test
    fun `the next three moon passes are listed whatever the window and the filters`() {
        // Mid latitudes, and far north where the Moon can stay up most of the day.
        for (station in listOf(GeoPos(45.0, 9.0), GeoPos(-34.6, -58.4), GeoPos(69.6, 18.9))) {
            val passes = moonPasses(station)
            assertEquals("passes at $station", 3, passes.size)
            assertTrue(passes.first().losTime > start)
            passes.zipWithNext { a, b ->
                // Consecutive and distinct: no moonrise skipped, none listed twice.
                assertTrue("overlap at $station", b.aosTime > a.losTime)
                assertTrue("gap at $station", b.aosTime - a.aosTime in 20 * HOUR..30 * HOUR)
            }
        }
    }

    private fun moonPasses(station: GeoPos) = runBlocking {
        val settings = stub<ISettingsRepo> { name ->
            when (name) {
                "getStationPosition" -> MutableStateFlow(station)
                "getOtherSettings" -> MutableStateFlow(OtherSettings(false, false, false, false, false, false, false, false))
                else -> null
            }
        }
        val repo = SatelliteRepo(Dispatchers.Default, stub<ILocalSource> { null }, settings, stub<IAmSatRepository> { null })
        // initRepository() would load the selection forever; hand it the Moon directly instead.
        @Suppress("UNCHECKED_CAST")
        (SatelliteRepo::class.java.getDeclaredField("_satellites").apply { isAccessible = true }
            .get(repo) as MutableStateFlow<List<OrbitalObject>>).value = listOf(MOON_ORBITAL_DATA.getObject())
        // A window of one hour and 89 degrees would drop every Moon pass if it applied.
        repo.calculatePasses(start, 1, 89.0, 0, 1439, false, emptyList(), false)
        repo.passes.value
    }

    private inline fun <reified T> stub(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T

    private companion object {
        const val HOUR = 3_600_000L
    }
}
