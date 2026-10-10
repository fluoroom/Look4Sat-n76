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

import com.rtbishop.look4sat.core.domain.model.FilterCategory
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.utility.SatStatusCategory

data class PassesState(
    val isPassesDialogShown: Boolean = false,
    val isTransponderDialogShown: Boolean = false,
    val isRefreshing: Boolean = true,
    val isUtc: Boolean = false,
    val nextPass: OrbitalPass,
    val searchQuery: String = "",
    val hours: Int = 24,
    val elevation: Double = 16.0,
    val lowElevation: Double = 16.0,
    val highElevation: Double = 65.0,
    val aosStartMinute: Int = 0,
    val aosEndMinute: Int = 23 * 60 + 59,
    val invertAosTimeWindow: Boolean = false,
    val showDeepSpace: Boolean = true,
    val categories: List<FilterCategory> = emptyList(),
    val onlyAmSatHeard: Boolean = false,
    /** What AMSAT observers last made of each satellite, by name; absent means no data. */
    val amSatStatus: Map<String, SatStatusCategory> = emptyMap(),
    val availableModes: List<String> = emptyList(),
    val itemsList: List<OrbitalPass> = emptyList(),
    val groupedPasses: Map<String, List<OrbitalPass>> = emptyMap(),
    val shouldSeeWhatsNew: Boolean = false,
    val sunTimes: Map<String, Pair<String, String>> = emptyMap()
)

sealed interface PassesAction {
    data object DismissWhatsNew : PassesAction
    data class FilterPasses(
        val hoursAhead: Int,
        val minElevation: Double,
        val lowElevation: Double,
        val highElevation: Double,
        val aosStartMinute: Int,
        val aosEndMinute: Int,
        val invertAosTimeWindow: Boolean,
        val showDeepSpace: Boolean
    ) : PassesAction
    data class FilterTransponders(val categories: List<FilterCategory>, val onlyAmSatHeard: Boolean) : PassesAction
    data object RefreshPasses : PassesAction
    data object TogglePassesDialog : PassesAction
    data object ToggleTransponderDialog : PassesAction
    data class SearchFor(val query: String) : PassesAction
}
