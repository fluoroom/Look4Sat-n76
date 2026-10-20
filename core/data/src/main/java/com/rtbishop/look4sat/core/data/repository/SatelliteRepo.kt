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

import com.rtbishop.look4sat.core.domain.model.FilterCategory
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IAmSatRepository
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.utility.AmSatNameMatch
import com.rtbishop.look4sat.core.domain.utility.AppClock
import com.rtbishop.look4sat.core.domain.utility.HEARD_CATEGORIES
import com.rtbishop.look4sat.core.domain.utility.amSatKeysReportedAs
import com.rtbishop.look4sat.core.domain.utility.referencedModes
import com.rtbishop.look4sat.core.domain.utility.round
import com.rtbishop.look4sat.core.domain.utility.toDegrees
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds

/** How many Moon passes are always listed. */
private const val MOON_PASSES = 3

class SatelliteRepo(
    private val dispatcher: CoroutineDispatcher,
    private val localStorage: ILocalSource,
    private val settingsRepo: ISettingsRepo,
    private val amSatRepo: IAmSatRepository
) : ISatelliteRepo {

    private val _passes = MutableStateFlow<List<OrbitalPass>>(emptyList())
    override val passes: StateFlow<List<OrbitalPass>> = _passes

    private val _isCalculating = MutableStateFlow(false)
    override val isCalculating: StateFlow<Boolean> = _isCalculating

    private val _satellites = MutableStateFlow<List<OrbitalObject>>(emptyList())
    override val satellites: StateFlow<List<OrbitalObject>> = _satellites

    private val _availableModes = MutableStateFlow<List<String>>(emptyList())
    override val availableModes: StateFlow<List<String>> = _availableModes

    private val _selectedPass = MutableStateFlow(0 to 0L)
    override val selectedPass: StateFlow<Pair<Int, Long>> = _selectedPass

    override fun selectPass(catNum: Int, aosTime: Long) {
        _selectedPass.value = catNum to aosTime
    }

    override suspend fun getRadiosWithId(id: Int) = localStorage.getRadiosWithId(id)

    override suspend fun initRepository() = withContext(dispatcher) {
        combine(
            settingsRepo.selectedIds,
            settingsRepo.stationPosition,
            settingsRepo.passesSettings,
            settingsRepo.otherSettings.map { it.stateOfUtc }.distinctUntilChanged()
        ) { selectedIds, _, settings, _ ->
            selectedIds to settings
        }
            .debounce(50.milliseconds)
            .collect { (selectedIds, settings) ->
                _satellites.update { localStorage.getEntriesWithIds(selectedIds) }
                calculatePasses(
                    time = AppClock.now(),
                    hoursAhead = settings.hoursAhead,
                    minElevation = settings.minElevation,
                    aosStartMinute = settings.aosStartMinute,
                    aosEndMinute = settings.aosEndMinute,
                    invertAosTimeWindow = settings.invertAosTimeWindow,
                    categories = settings.categories,
                    onlyAmSatHeard = settings.onlyAmSatHeard
                )
                try {
                    val listed = localStorage.getAvailableModes(selectedIds)
                    val referenced = settings.categories.referencedModes()
                    _availableModes.update { (listed + referenced).distinct().sorted() }
                } catch (_: Exception) {}
            }
    }

    override suspend fun getPosition(sat: OrbitalObject, pos: GeoPos, time: Long): OrbitalPos {
        return withContext(dispatcher) { sat.getPosition(pos, time) }
    }

    override suspend fun getTrack(sat: OrbitalObject, pos: GeoPos, start: Long, end: Long): List<OrbitalPos> {
        return withContext(dispatcher) {
            val estimatedSize = ((end - start) / 15000).toInt() + 1
            val positions = ArrayList<OrbitalPos>(estimatedSize)
            var currentTime = start
            while (currentTime < end) {
                positions.add(sat.getPosition(pos, currentTime))
                currentTime += 15000
            }
            positions
        }
    }

    override suspend fun getRadios(
        sat: OrbitalObject,
        pos: GeoPos,
        radios: List<SatRadio>,
        time: Long
    ): List<SatRadio> {
        return withContext(dispatcher) {
            val satPos = sat.getPosition(pos, time)
            radios.map { transmitter ->
                transmitter.copy(
                    downlinkLow = transmitter.downlinkLow?.let { satPos.getDownlinkFreq(it) },
                    downlinkHigh = transmitter.downlinkHigh?.let { satPos.getDownlinkFreq(it) },
                    uplinkLow = transmitter.uplinkLow?.let { satPos.getUplinkFreq(it) },
                    uplinkHigh = transmitter.uplinkHigh?.let { satPos.getUplinkFreq(it) }
                )
            }
        }
    }

    override suspend fun calculatePasses(
        time: Long,
        hoursAhead: Int,
        minElevation: Double,
        aosStartMinute: Int,
        aosEndMinute: Int,
        invertAosTimeWindow: Boolean,
        categories: List<FilterCategory>,
        onlyAmSatHeard: Boolean
    ) {
        _isCalculating.value = true
        // Normalize to the start of the current minute so that coarse 60-second stepping
        // in getLeoPass always begins from the same phase, producing stable AOS/LOS times
        val normalizedTime = time / 60_000L * 60_000L
        val currentSatellites = _satellites.value
        withContext(dispatcher) {
            val stationPos = settingsRepo.stationPosition.value
            // Emptiness of the *filter* decides, not emptiness of its result: an active filter
            // that matches nothing must yield no passes rather than falling back to every pass.
            val matchingSatellites = if (categories.none { it.isActive }) {
                currentSatellites
            } else {
                val matchingIds = localStorage.getIdsMatchingCategories(categories).toHashSet()
                currentSatellites.filter { it.data.catnum in matchingIds }
            }
            // No AMSAT page (offline, or AMSAT down) means no AMSAT filter, the same call the
            // satellite list makes: better every pass than an empty list on a working device.
            val heardKeys = if (onlyAmSatHeard) {
                amSatRepo.recentStatus()?.amSatKeysReportedAs(HEARD_CATEGORIES)
            } else null
            val filteredSatellites = if (heardKeys == null) {
                matchingSatellites
            } else {
                matchingSatellites.filter { AmSatNameMatch.matches(it.data.name, heardKeys) }
            }
            // Compute passes for each satellite in parallel
            val passLists = coroutineScope {
                filteredSatellites.filterNot { it.data.isMoon }.map { satellite ->
                    async { satellite.getPasses(stationPos, normalizedTime, hoursAhead) }
                }.awaitAll()
            }
            // Flatten and filter in a single pass
            val timeFuture = normalizedTime + (hoursAhead * 60L * 60L * 1000L)
            val newPasses = ArrayList<OrbitalPass>()
            for (list in passLists) {
                for (pass in list) {
                    if (
                        pass.losTime > time
                        && pass.aosTime < timeFuture
                        && pass.maxElevation > minElevation
                        && (pass.isDeepSpace || isAosInRange(
                            pass.aosTime, aosStartMinute, aosEndMinute, invertAosTimeWindow
                        ))
                    ) {
                        newPasses.add(pass)
                    }
                }
            }
            // The Moon always shows its next passes, whatever the window and the filters say.
            currentSatellites.firstOrNull { it.data.isMoon }?.let { moon ->
                moon.getPasses(stationPos, normalizedTime, 0, MOON_PASSES)
                    .filter { it.losTime > time }.take(MOON_PASSES).let(newPasses::addAll)
            }
            newPasses.sortBy { it.aosTime }
            delay(1000.milliseconds) // Simulate loading time for better UX
            _passes.update { newPasses }
        }
        _isCalculating.value = false
    }

    private fun isAosInRange(
        aosTime: Long,
        aosStartMinute: Int,
        aosEndMinute: Int,
        invertAosTimeWindow: Boolean
    ): Boolean {
        // Must match the timezone used to display pass times (UTC toggle vs device local)
        val tz = if (settingsRepo.otherSettings.value.stateOfUtc) {
            TimeZone.getTimeZone("UTC")
        } else {
            TimeZone.getDefault()
        }
        val offsetMillis = tz.getOffset(aosTime).toLong()
        val localMillis = Math.floorMod(aosTime + offsetMillis, 24L * 60L * 60L * 1000L)
        val aosMinute = (localMillis / 60_000L).toInt()
        val inRange = if (aosStartMinute <= aosEndMinute) {
            aosMinute in aosStartMinute..aosEndMinute
        } else {
            aosMinute !in (aosEndMinute + 1)..<aosStartMinute
        }
        return if (invertAosTimeWindow) !inRange else inRange
    }

    /**
     * Passes from [time] until [hours] ahead, and past that until at least [minUpcoming] of them
     * are still to end. [time] is the start of a minute, so a pass ending within it is not counted.
     */
    private fun OrbitalObject.getPasses(pos: GeoPos, time: Long, hours: Int, minUpcoming: Int = 0): List<OrbitalPass> {
        val passes = mutableListOf<OrbitalPass>()
        val endDate = time + hours * 60L * 60L * 1000L
        val decayed = this.data.hasDecayed(time)
        var startDate = time
        var shouldRewind = true
        var lastAosDate: Long
        var count = 0
        if (this.willBeSeen(pos)) {
            if (this.data.isDeepSpace) {
                passes.add(getGeoPass(this, pos, time, decayed))
            } else {
                do {
                    if (count > 0) shouldRewind = false
                    val pass = getLeoPass(this, pos, startDate, shouldRewind, decayed)
                    lastAosDate = pass.aosTime
                    passes.add(pass)
                    startDate = pass.losTime + this.skipAfterLosMillis()
                    count++
                } while (lastAosDate < endDate || passes.count { it.losTime > time + 60_000L } < minUpcoming)
            }
        }
        return passes
    }

    private fun getGeoPass(sat: OrbitalObject, pos: GeoPos, time: Long, decayed: Boolean): OrbitalPass {
        val satPos = sat.getPosition(pos, time)
        val aos = time - 24 * 60L * 60L * 1000L
        val los = time + 24 * 60L * 60L * 1000L // val tca = (aos + los) / 2
        val az = satPos.azimuth.toDegrees().round(1)
        val elev = satPos.elevation.toDegrees().round(1)
        val alt = satPos.altitude
        return OrbitalPass(aos, az, los, az, alt.toInt(), elev, sat, hasDecayed = decayed)
    }

    /**
     * How far back the search starts, so that a pass already under way is found. A quarter orbit
     * covers any LEO pass, but the Moon can stay up for most of its lunar day: rewind all of it.
     */
    private fun OrbitalObject.rewindMillis(): Long =
        if (data.isMoon) (data.orbitalPeriod * 60_000L).toLong() else quarterOrbitMillis()

    /**
     * How far past a LOS the next AOS can safely be looked for. Three quarters of an orbit for a
     * satellite, but none for the Moon: at high latitudes it can be down for only a few hours of
     * its lunar day, and skipping ahead would jump over a whole moonrise.
     */
    private fun OrbitalObject.skipAfterLosMillis(): Long = if (data.isMoon) 0L else quarterOrbitMillis() * 3

    private fun OrbitalObject.quarterOrbitMillis(): Long = (data.orbitalPeriod / 4.0).toInt() * 60L * 1000L

    private fun getLeoPass(sat: OrbitalObject, pos: GeoPos, time: Long, rewind: Boolean, decayed: Boolean): OrbitalPass {
        var calendarTimeMillis = time
        var elevation: Double
        var maxElevation = 0.0
        if (rewind) calendarTimeMillis -= sat.rewindMillis()

        // Use lightweight elevation check for coarse searching
        if (sat.getElevation(pos, calendarTimeMillis) > 0.0) {
            // move forward in 30 second intervals until the sat goes below the horizon
            do {
                calendarTimeMillis += 30 * 1000L
            } while (sat.getElevation(pos, calendarTimeMillis) > 0.0)
            calendarTimeMillis += sat.skipAfterLosMillis()
        }

        // find the next time sat comes above the horizon (coarse: 60s steps)
        do {
            calendarTimeMillis += 60L * 1000L
            elevation = sat.getElevation(pos, calendarTimeMillis)
            if (elevation > maxElevation) maxElevation = elevation
        } while (elevation < 0.0)

        // refine AOS to ~500ms precision
        calendarTimeMillis -= 60L * 1000L
        do {
            calendarTimeMillis += 500L
            elevation = sat.getElevation(pos, calendarTimeMillis)
            if (elevation > maxElevation) maxElevation = elevation
        } while (elevation < 0.0)

        // Get full position for AOS data (azimuth, altitude)
        val aosPos = sat.getFullPosition(pos, calendarTimeMillis)
        val aos = 1000 * ((aosPos.time + 500) / 1000)
        val aosAz = aosPos.azimuth.toDegrees().round(1)

        // find when sat goes below (coarse: 30s steps)
        do {
            calendarTimeMillis += 30L * 1000L
            elevation = sat.getElevation(pos, calendarTimeMillis)
            if (elevation > maxElevation) maxElevation = elevation
        } while (elevation > 0.0)

        // refine LOS to ~500ms precision
        calendarTimeMillis -= 30L * 1000L
        do {
            calendarTimeMillis += 500L
            elevation = sat.getElevation(pos, calendarTimeMillis)
            if (elevation > maxElevation) maxElevation = elevation
        } while (elevation > 0.0)

        // Get full position for LOS data (azimuth, altitude)
        val losPos = sat.getFullPosition(pos, calendarTimeMillis)
        val los = 1000 * ((losPos.time + 500) / 1000)
        val losAz = losPos.azimuth.toDegrees().round(1)

        // Get altitude at approximate TCA (max elevation)
        val tcaTime = (aos + los) / 2
        val tcaPos = sat.getFullPosition(pos, tcaTime)
        val alt = tcaPos.altitude

        val elev = maxElevation.toDegrees().round(1)
        return OrbitalPass(aos, aosAz, los, losAz, alt.toInt(), elev, sat, hasDecayed = decayed)
    }
}
