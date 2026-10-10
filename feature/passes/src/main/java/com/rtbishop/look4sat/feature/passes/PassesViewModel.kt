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
package com.rtbishop.look4sat.feature.passes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.model.FilterCategory
import com.rtbishop.look4sat.core.domain.predict.CelestialComputer
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IAmSatRepository
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.AppClock
import com.rtbishop.look4sat.core.domain.utility.bestCategories
import com.rtbishop.look4sat.core.presentation.getDefaultPass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.milliseconds

/** Inputs that together determine the visible pass list; a change in any one regroups it. */
private data class PassesInput(
    val passes: List<OrbitalPass>,
    val isUtc: Boolean,
    val showDeepSpace: Boolean,
    val query: String
)

class PassesViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private val amSatRepo: IAmSatRepository
) : ViewModel() {

    private var amSatJob: Job? = null

    private val defaultPass = getDefaultPass()
    private val _uiState = MutableStateFlow(
        PassesState(
            isUtc = settingsRepo.otherSettings.value.stateOfUtc,
            nextPass = defaultPass,
            hours = settingsRepo.passesSettings.value.hoursAhead,
            elevation = settingsRepo.passesSettings.value.minElevation,
            lowElevation = settingsRepo.otherSettings.value.lowElevation,
            highElevation = settingsRepo.otherSettings.value.highElevation,
            aosStartMinute = settingsRepo.passesSettings.value.aosStartMinute,
            aosEndMinute = settingsRepo.passesSettings.value.aosEndMinute,
            invertAosTimeWindow = settingsRepo.passesSettings.value.invertAosTimeWindow,
            showDeepSpace = settingsRepo.passesSettings.value.showDeepSpace,
            categories = settingsRepo.passesSettings.value.categories,
            onlyAmSatHeard = settingsRepo.passesSettings.value.onlyAmSatHeard,
            shouldSeeWhatsNew = settingsRepo.otherSettings.value.shouldSeeWhatsNew
        )
    )
    val uiState: StateFlow<PassesState> = _uiState

    private val _timeNow = MutableStateFlow(AppClock.now())

    private val searchQuery = MutableStateFlow("")

    /**
     * Ticks once per second, kept separate from [uiState] so that a clock update only
     * recomposes the countdown chips and progress bars instead of the whole screen.
     */
    val timeNow: StateFlow<Long> = _timeNow

    init {
        // Refresh indicator: mirrors the repo's isCalculating state
        viewModelScope.launch {
            satelliteRepo.isCalculating.collect { calculating ->
                _uiState.update { it.copy(isRefreshing = calculating) }
            }
        }
        // Update available modes when the satellite selection changes
        viewModelScope.launch {
            satelliteRepo.availableModes.collectLatest { modes ->
                _uiState.update { it.copy(availableModes = modes) }
            }
        }
        // React to settings changes: update UTC flag and whatsNew
        viewModelScope.launch {
            settingsRepo.otherSettings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        isUtc = settings.stateOfUtc,
                        shouldSeeWhatsNew = settings.shouldSeeWhatsNew,
                        lowElevation = settings.lowElevation,
                        highElevation = settings.highElevation
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.passesSettings.map { it.categories to it.onlyAmSatHeard }.distinctUntilChanged()
                .collectLatest { (categories, onlyAmSatHeard) ->
                    _uiState.update { it.copy(categories = categories, onlyAmSatHeard = onlyAmSatHeard) }
                }
        }
        // Tick loop, gated on having an observer so it stops while the screen is in the
        // background. Restarts on passes, UTC or DeepSpace changes. Sun times and grouping are
        // computed only when the pass list itself changes; the clock alone never rebuilds them.
        viewModelScope.launch {
            _uiState.subscriptionCount
                .map { count -> count > 0 }
                .distinctUntilChanged()
                .collectLatest { isObserved ->
                    if (!isObserved) return@collectLatest
                    combine(
                        satelliteRepo.passes,
                        settingsRepo.otherSettings.map { it.stateOfUtc }.distinctUntilChanged(),
                        settingsRepo.passesSettings.map { it.showDeepSpace }.distinctUntilChanged(),
                        searchQuery
                    ) { passes, isUtc, showDeepSpace, query ->
                        PassesInput(passes, isUtc, showDeepSpace, query)
                    }
                        .collectLatest { (allPasses, isUtc, showDeepSpace, query) ->
                            val filtered = filterByQuery(
                                if (showDeepSpace) allPasses else allPasses.filter { !it.isDeepSpace },
                                query
                            )
                            refreshAmSatStatus(filtered)
                            val sunTimes = computeSunTimes(filtered, isUtc)
                            var live: List<OrbitalPass>? = null
                            while (isActive) {
                                val timeNow = AppClock.now()
                                _timeNow.value = timeNow
                                val current = filtered.filter { it.isDeepSpace || timeNow < it.losTime }
                                // Only a pass dropping off the list warrants a regroup
                                if (live == null || current.size != live.size) {
                                    live = current
                                    _uiState.update {
                                        it.copy(
                                            itemsList = current,
                                            groupedPasses = groupPasses(current, isUtc),
                                            sunTimes = sunTimes
                                        )
                                    }
                                }
                                val nextPass = resolveNextPass(current, timeNow)
                                _uiState.update { it.copy(nextPass = nextPass) }
                                delay(1000.milliseconds)
                            }
                        }
                }
        }
    }

    fun onAction(action: PassesAction) {
        when (action) {
            PassesAction.DismissWhatsNew -> settingsRepo.setWhatsNewDismissed()
            is PassesAction.FilterPasses ->
                applyFilter(
                    hoursAhead = action.hoursAhead,
                    minElevation = action.minElevation,
                    lowElevation = action.lowElevation,
                    highElevation = action.highElevation,
                    aosStartMinute = action.aosStartMinute,
                    aosEndMinute = action.aosEndMinute,
                    invertAosTimeWindow = action.invertAosTimeWindow,
                    showDeepSpace = action.showDeepSpace
                )
            is PassesAction.FilterTransponders -> setTransponderFilter(action.categories, action.onlyAmSatHeard)
            PassesAction.RefreshPasses -> refreshPasses()
            PassesAction.TogglePassesDialog ->
                _uiState.update { it.copy(isPassesDialogShown = !it.isPassesDialogShown) }
            PassesAction.ToggleTransponderDialog ->
                _uiState.update { it.copy(isTransponderDialogShown = !it.isTransponderDialogShown) }
            is PassesAction.SearchFor -> {
                searchQuery.value = action.query
                _uiState.update { it.copy(searchQuery = action.query) }
            }
        }
    }

    /**
     * Looks up the AMSAT status of the listed satellites on the side, so a slow or absent network
     * never holds the pass list back. Without a page the icons simply stay on "no data".
     */
    private fun refreshAmSatStatus(passes: List<OrbitalPass>) {
        amSatJob?.cancel()
        amSatJob = viewModelScope.launch {
            val page = amSatRepo.recentStatus() ?: return@launch
            val status = page.bestCategories(passes.map { it.name })
            _uiState.update { it.copy(amSatStatus = status) }
        }
    }

    private fun displayLocale(): Locale {
        val locale = Locale.getDefault()
        return if (locale.language == Locale.CHINESE.language) locale else Locale.ENGLISH
    }

    /** Returns the visible pass-date format. Keep non-Chinese locales identical to upstream. */
    private fun dateFormat(tz: TimeZone): SimpleDateFormat {
        val locale = displayLocale()
        val pattern = if (locale.language == Locale.CHINESE.language) {
            "yyyy'年'M'月'd'日' EEEE"
        } else {
            "EEE, dd MMM yyyy"
        }
        return SimpleDateFormat(pattern, locale).also { it.timeZone = tz }
    }

    // Computes sunrise/sunset strings for each unique calendar day in the pass list, plus today for DeepSpace
    private fun computeSunTimes(passes: List<OrbitalPass>, isUtc: Boolean): Map<String, Pair<String, String>> {
        val stationPos = settingsRepo.stationPosition.value
        val tz = if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val sdfDate = dateFormat(tz)
        val sdfTime = SimpleDateFormat("HH:mm", displayLocale()).also { it.timeZone = tz }
        val result = LinkedHashMap<String, Pair<String, String>>()
        // DeepSpace group always shows today's sun times
        if (passes.any { it.isDeepSpace }) {
            val riseSet = CelestialComputer.findSunRiseSet(stationPos, AppClock.now())
            val rise = if (riseSet.riseTimeMillis > 0) sdfTime.format(Date(riseSet.riseTimeMillis)) else "--:--"
            val set = if (riseSet.setTimeMillis > 0) sdfTime.format(Date(riseSet.setTimeMillis)) else "--:--"
            result["DeepSpace (period >225min)"] = rise to set
        }
        for (pass in passes) {
            if (pass.isDeepSpace) continue
            val label = sdfDate.format(Date(pass.aosTime))
            if (label in result) continue
            val riseSet = CelestialComputer.findSunRiseSet(stationPos, pass.aosTime)
            val rise = if (riseSet.riseTimeMillis > 0) sdfTime.format(Date(riseSet.riseTimeMillis)) else "--:--"
            val set = if (riseSet.setTimeMillis > 0) sdfTime.format(Date(riseSet.setTimeMillis)) else "--:--"
            result[label] = rise to set
        }
        return result
    }

    private fun groupPasses(passes: List<OrbitalPass>, isUtc: Boolean): Map<String, List<OrbitalPass>> {
        val tz = if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val sdfDate = dateFormat(tz)
        val ordered = LinkedHashMap<String, List<OrbitalPass>>()
        val deepSpace = passes.filter { it.isDeepSpace }
        if (deepSpace.isNotEmpty()) ordered["DeepSpace (period >225min)"] = deepSpace
        passes.filter { !it.isDeepSpace }
            .groupByTo(LinkedHashMap()) { sdfDate.format(Date(it.aosTime)) }
            .forEach { (k, v) -> ordered[k] = v }
        return ordered
    }

    /** Resolves the next upcoming pass, falling back to the one currently in progress. */
    private fun resolveNextPass(passes: List<OrbitalPass>, timeNow: Long): OrbitalPass {
        val upcoming = passes.firstOrNull { it.aosTime > timeNow }
        if (upcoming != null) return upcoming
        return passes.lastOrNull() ?: defaultPass
    }

    /**
     * Filters passes by query, mirroring the satellite search: a numeric query matches the
     * catalog number, otherwise every space-separated token must appear in the name once both
     * sides are normalized, so "ao7" matches "AO-7 (AMSAT-OSCAR 7)".
     */
    private fun filterByQuery(passes: List<OrbitalPass>, query: String): List<OrbitalPass> {
        if (query.isBlank()) return passes
        val catNum = query.trim().toIntOrNull()
        if (catNum != null) return passes.filter { it.catNum == catNum }
        val tokens = query.split(' ').map { normalizeForSearch(it) }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return passes
        return passes.filter { pass ->
            val normalizedName = normalizeForSearch(pass.name)
            tokens.all { normalizedName.contains(it) }
        }
    }

    /** Lowercases and strips all non-alphanumeric chars for fuzzy matching. */
    private fun normalizeForSearch(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun applyFilter(
        hoursAhead: Int,
        minElevation: Double,
        lowElevation: Double,
        highElevation: Double,
        aosStartMinute: Int,
        aosEndMinute: Int,
        invertAosTimeWindow: Boolean,
        showDeepSpace: Boolean
    ) = viewModelScope.launch {
        settingsRepo.setPassesSettings(
            settingsRepo.passesSettings.value.copy(
                showDeepSpace = showDeepSpace,
                hoursAhead = hoursAhead,
                minElevation = minElevation,
                aosStartMinute = aosStartMinute,
                aosEndMinute = aosEndMinute,
                invertAosTimeWindow = invertAosTimeWindow
            )
        )
        settingsRepo.updateOtherSettings { it.copy(lowElevation = lowElevation, highElevation = highElevation) }
        _uiState.update {
            it.copy(
                hours = hoursAhead,
                elevation = minElevation,
                lowElevation = lowElevation,
                highElevation = highElevation,
                aosStartMinute = aosStartMinute,
                aosEndMinute = aosEndMinute,
                invertAosTimeWindow = invertAosTimeWindow,
                showDeepSpace = showDeepSpace
            )
        }
    }

    private fun setTransponderFilter(categories: List<FilterCategory>, onlyAmSatHeard: Boolean) =
        viewModelScope.launch {
            settingsRepo.setPassesSettings(
                settingsRepo.passesSettings.value.copy(categories = categories, onlyAmSatHeard = onlyAmSatHeard)
            )
            _uiState.update { it.copy(categories = categories, onlyAmSatHeard = onlyAmSatHeard) }
        }

    private fun refreshPasses() = viewModelScope.launch {
        // The fix lands later and on its own: a new position recalculates the passes by itself,
        // and a clock correction is picked up by the next tick.
        settingsRepo.syncWithGps()
        val settings = settingsRepo.passesSettings.value
        satelliteRepo.calculatePasses(
            time = AppClock.now(),
            hoursAhead = settings.hoursAhead,
            minElevation = settings.minElevation,
            aosStartMinute = settings.aosStartMinute,
            aosEndMinute = settings.aosEndMinute,
            invertAosTimeWindow = settings.invertAosTimeWindow,
            categories = settings.categories,
            onlyAmSatHeard = settings.onlyAmSatHeard
        )
    }

    companion object {
        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                PassesViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo,
                    amSatRepo = container.amSatRepo
                )
            }
        }
    }
}
