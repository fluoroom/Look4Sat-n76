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

/**
 * Catalogue number of the Moon. It has no NORAD number, so it borrows its NAIF id (301), negated
 * so that no catalogue, present or future, can ever hand the same number to a real satellite.
 */
const val MOON_CATNUM = -301

/** Mean time between two moonrises, the Moon's "orbit" as an observer on the ground sees it. */
private const val LUNAR_DAY_MIN = 1490.47

/**
 * The Moon as an orbital entry, so it can be selected, predicted and tracked like any satellite.
 * Only [OrbitalData.meanmo] matters: it gives the ground track and pass search a period of one
 * lunar day. The rest are the Moon's mean elements, for display only: [MoonObject] ignores them.
 */
val MOON_ORBITAL_DATA = OrbitalData(
    name = "Moon",
    epoch = 26001.0,
    meanmo = MIN_PER_DAY / LUNAR_DAY_MIN,
    eccn = 0.0549,
    incl = 5.145,
    raan = 0.0,
    argper = 0.0,
    meanan = 0.0,
    catnum = MOON_CATNUM,
    bstar = 0.0
)

/** Positions the Moon from the lunar ephemeris instead of from orbital elements. */
class MoonObject(data: OrbitalData) : OrbitalObject(data) {

    override fun propagate(julianUtc: Double, tsince: Double) {
        val now = CelestialComputer.moonPositionEci(julianUtc)
        // The ephemeris gives positions only: take the velocity from a central difference, over a
        // span short enough that the curvature of a 27-day orbit cannot bias the range rate.
        val before = CelestialComputer.moonPositionEci(julianUtc - HALF_SPAN_DAYS)
        val after = CelestialComputer.moonPositionEci(julianUtc + HALF_SPAN_DAYS)
        val span = 2 * HALF_SPAN_DAYS * SEC_PER_DAY
        setEciState(
            now[0], now[1], now[2],
            (after[0] - before[0]) / span, (after[1] - before[1]) / span, (after[2] - before[2]) / span
        )
    }

    private companion object {
        const val HALF_SPAN_DAYS = 30.0 / SEC_PER_DAY
    }
}
