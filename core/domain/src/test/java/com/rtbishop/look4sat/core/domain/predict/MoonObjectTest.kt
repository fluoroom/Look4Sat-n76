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
package com.rtbishop.look4sat.core.domain.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MoonObjectTest {

    private val moon = MOON_ORBITAL_DATA.getObject()
    private val observer = GeoPos(45.0, 9.0, 120.0)
    private val start = Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()

    @Test
    fun `the moon is its own kind of object that rises and sets`() {
        assertTrue(moon is MoonObject)
        assertFalse(MOON_ORBITAL_DATA.isDeepSpace)
        assertTrue(moon.willBeSeen(observer))
    }

    @Test
    fun `position matches the topocentric ephemeris`() {
        for (hour in 0 until 48) {
            val time = start + hour * 3_600_000L
            // The ephemeris applies the parallax (0.9-1.0 deg, shrinking with cos(elevation)) as a
            // closed-form elevation correction; the object gets it from the observer vector maths.
            val ephemeris = CelestialComputer.getMoonPosition(observer, time)
            if (ephemeris.elevation !in 5.0..60.0) continue
            val pos = moon.getPosition(observer, time)
            assertEquals("elevation at hour $hour", ephemeris.elevation, Math.toDegrees(pos.elevation), 0.05)
            assertEquals(ephemeris.azimuth, Math.toDegrees(pos.azimuth), 0.5)
            assertTrue(pos.distance in 350_000.0..410_000.0)
        }
    }

    @Test
    fun `range rate is the derivative of the range`() {
        for (hour in 0 until 24 step 3) {
            val time = start + hour * 3_600_000L
            val rate = moon.getPosition(observer, time).distanceRate
            val before = moon.getPosition(observer, time - 30_000L).distance
            val after = moon.getPosition(observer, time + 30_000L).distance
            // km/s; a 1 m/s error is a 1.3 Hz Doppler error on 2 m, well inside any EME filter.
            assertEquals((after - before) / 60.0, rate, 0.001)
        }
    }
}
