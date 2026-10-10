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
package com.rtbishop.look4sat

import android.app.Application
import com.rtbishop.look4sat.core.data.injection.MainContainer
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainApplication : Application(), IContainerProvider {

    private lateinit var container: IMainContainer

    override fun getMainContainer(): IMainContainer = container

    override fun onCreate() {
        super.onCreate()
        container = MainContainer(this)
        // trigger automatic update once the chosen interval has passed, for as long as the app lives
        container.appScope.launch {
            while (true) {
                checkAutoUpdate()
                delay(AUTO_UPDATE_CHECK_MS)
            }
        }
        // position and clock from GPS, for those who opted in
        container.settingsRepo.syncWithGps()
        // load satellite data on every app start
        container.appScope.launch { container.satelliteRepo.initRepository() }
    }

    private var lastUpdateAttempt = 0L

    private suspend fun checkAutoUpdate(timeNow: Long = System.currentTimeMillis()) {
        val settings = container.settingsRepo.otherSettings.value
        if (!settings.stateOfAutoUpdate) return
        val intervalMs = settings.autoUpdateIntervalMin * 60_000L
        // A failed update leaves the stored timestamp alone, so the last attempt counts as well:
        // offline, this retries once per interval instead of once per check.
        val lastUpdate = maxOf(container.settingsRepo.databaseState.value.updateTimestamp, lastUpdateAttempt)
        if (timeNow - lastUpdate > intervalMs) {
            lastUpdateAttempt = timeNow
            val sdf = SimpleDateFormat("d MMM yyyy - HH:mm:ss", Locale.getDefault())
            println("Started periodic data update on ${sdf.format(Date())}")
            container.databaseRepo.updateFromRemote()
        }
    }

    private companion object {
        const val AUTO_UPDATE_CHECK_MS = 60_000L
    }
}
