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

import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import com.rtbishop.look4sat.core.domain.model.AprsSettings
import com.rtbishop.look4sat.core.domain.model.AprsTransport
import com.rtbishop.look4sat.core.domain.model.AudioSource
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.FilterCategory
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.decodeFilterCategories
import com.rtbishop.look4sat.core.domain.model.encodeToString
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.N76Settings
import com.rtbishop.look4sat.core.domain.model.N76TxPower
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.Constants
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.AppClock
import com.rtbishop.look4sat.core.domain.utility.SatStatusCategory
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import com.rtbishop.look4sat.core.domain.utility.round
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.Locale

class SettingsRepo(
    private val locationManager: LocationManager,
    private val preferences: SharedPreferences,
    override val appVersionName: String,
    override val appVersionCode: Long,
) : ISettingsRepo, LocationListenerCompat {

    private val keyBluetoothRotatorAddress = "bluetoothAddress"
    private val keyBluetoothRotatorName = "bluetoothName"
    private val keyBluetoothRotatorFormat = "bluetoothFormat"
    private val keyBluetoothRotatorState = "bluetoothState"
    private val keyBluetoothFrequencyState = "bluetoothFrequencyState"
    private val keyBluetoothFrequencyAddress = "bluetoothFrequencyAddress"
    private val keyBluetoothFrequencyFormat = "bluetoothFrequencyFormat"
    private val keyFilterShowDeepSpace = "filterShowDeepSpace"
    private val keyFilterHoursAhead = "filterHoursAhead"
    private val keyFilterMinElevation = "filterMinElevation"
    private val keyFilterAosStartMinute = "filterAosStartMinute"
    private val keyFilterAosEndMinute = "filterAosEndMinute"
    private val keyFilterAosInvert = "filterAosInvert"
    private val keyFilterOnlyAmSatHeard = "filterOnlyAmSatHeard"
    private val keyNumberOfRadios = "numberOfRadios"
    private val keyNumberOfSatellites = "numberOfSatellites"
    private val keyRotatorAddress = "rotatorAddress"
    private val keyRotatorPort = "rotatorPort"
    private val keyRotatorState = "rotatorState"
    private val keyRotatorFormat = "rotatorFormat"
    private val keyFrequencyState = "frequencyState"
    private val keyFrequencyAddress = "frequencyAddress"
    private val keyFrequencyPort = "frequencyPort"
    private val keyFrequencyFormat = "frequencyFormat"
    private val keyFrequencyOffsetHz = "frequencyOffsetHz"
    private val keySelectedIds = "selectedIds"
    private val keySelectedSatModes = "selectedSatModes"
    private val keySelectedAmSatStatuses = "selectedAmSatStatuses"
    private val keyLegacySelectedModes = "selectedModes"
    private val keySelectedBands = "selectedBands"
    private val keyFilterCategories = "filterCategories"
    private val keyAudioSource = "audioSource"
    private val keyStateOfAutoUpdate = "stateOfAutoUpdate"
    private val keyAutoUpdateIntervalMin = "autoUpdateIntervalMin"
    private val keyStateOfAutoGps = "stateOfAutoGps"
    private val keyStateOfGpsTime = "stateOfGpsTime"
    private val keyStateOfSensors = "stateOfSensors"
    private val keyStateOfSweep = "stateOfSweep"
    private val keyStateOfUtc = "stateOfUtc"
    private val keyStateOfLightTheme = "stateOfLightTheme"
    private val keyStateOfNightMode = "stateOfNightMode"
    private val keyStationAltitude = "stationAltitude"
    private val keyStationLatitude = "stationLatitude"
    private val keyStationLongitude = "stationLongitude"
    private val keyStationQth = "stationQth"
    private val keyStationTimestamp = "stationTimestamp"
    private val keyUpdateTimestamp = "updateTimestamp"
    private val keyShouldSeeWarning = "shouldSeeWarning"
    private val keyShouldSeeWhatsNew = "shouldSeeWhatsNew"
    private val keySstvMode = "sstvMode"
    private val keyLowElevation = "lowElevation"
    private val keyHighElevation = "highElevation"
    private val keyRadarCompassOffset = "radarCompassOffset"
    private val keyRadarCompassOffsetElev = "radarCompassOffsetElev"
    private val keySatellitesUrls = "satellitesUrls"
    private val keyTransceiversUrls = "transceiversUrls"
    private val keySatellitesUrlsEnabled = "satellitesUrlsEnabled"
    private val keyTransceiversUrlsEnabled = "transceiversUrlsEnabled"
    private val keySettingsVersion = "settingsVersion"
    private val separatorComma = ","
    private val separatorUrl = "\n"

    init {
        // The meaning of the data source order changed, so the stored lists are dropped and the defaults
        // are applied again. The update timestamp is cleared as well, to refresh the migrated data
        // on the first launch instead of waiting up to 48 hours for the automatic update.
        if (preferences.getInt(keySettingsVersion, 0) < appVersionCode) {
            preferences.edit {
                listOf(keyShouldSeeWhatsNew, keySatellitesUrls, keyTransceiversUrls, keyUpdateTimestamp)
                    .forEach { key -> remove(key) }
                putInt(keySettingsVersion, appVersionCode.toInt())
            }
        }
    }

    //region # Satellites selection settings
    private val _satelliteSelection = MutableStateFlow(getSelectedIds())
    private val _satelliteModeSelection = MutableStateFlow(getSelectedSatModes())
    override val selectedIds: StateFlow<List<Int>> = _satelliteSelection
    override val selectedSatModes: StateFlow<List<String>> = _satelliteModeSelection
    private val _amSatStatusSelection = MutableStateFlow(getSelectedAmSatStatuses())
    override val selectedAmSatStatuses: StateFlow<Set<SatStatusCategory>> = _amSatStatusSelection

    override fun setSelectedIds(ids: List<Int>) {
        val selectionString = ids.joinToString(separatorComma)
        preferences.edit { putString(keySelectedIds, selectionString) }
        _satelliteSelection.value = ids
    }

    override fun setSelectedSatModes(modes: List<String>) {
        val cleaned = modes.filter { it.isNotBlank() }.distinct().sorted()
        val modesString = cleaned.joinToString(separatorComma)
        preferences.edit { putString(keySelectedSatModes, modesString) }
        _satelliteModeSelection.value = cleaned
    }

    override fun setSelectedAmSatStatuses(statuses: Set<SatStatusCategory>) {
        val statusString = statuses.joinToString(separatorComma) { it.name }
        preferences.edit { putString(keySelectedAmSatStatuses, statusString) }
        _amSatStatusSelection.value = statuses
    }

    /** Unknown names are dropped, so a renamed category degrades to "not selected". */
    private fun getSelectedAmSatStatuses(): Set<SatStatusCategory> {
        val statusString = preferences.getString(keySelectedAmSatStatuses, null)
        if (statusString.isNullOrEmpty()) return emptySet()
        return statusString.split(separatorComma)
            .mapNotNull { name -> SatStatusCategory.entries.firstOrNull { it.name == name } }
            .toSet()
    }

    private fun getSelectedIds(): List<Int> {
        val selectionString = preferences.getString(keySelectedIds, null)
        if (selectionString.isNullOrEmpty()) return emptyList()
        return selectionString.split(separatorComma).map { it.toInt() }
    }

    private fun getSelectedSatModes(): List<String> {
        val modesString = preferences.getString(keySelectedSatModes, null)
            ?: preferences.getString(keyLegacySelectedModes, null)
        if (modesString.isNullOrEmpty()) return emptyList()
        return modesString.split(separatorComma).filter { it.isNotBlank() }.sorted()
    }
    //endregion

    //region # Passes filter settings
    private val _passesSettings = MutableStateFlow(getPassesSettings())
    override val passesSettings: StateFlow<PassesSettings> = _passesSettings

    override fun setPassesSettings(settings: PassesSettings) = preferences.edit {
        putBoolean(keyFilterShowDeepSpace, settings.showDeepSpace)
        putInt(keyFilterHoursAhead, settings.hoursAhead)
        putLong(keyFilterMinElevation, settings.minElevation.toRawBits())
        putInt(keyFilterAosStartMinute, settings.aosStartMinute)
        putInt(keyFilterAosEndMinute, settings.aosEndMinute)
        putBoolean(keyFilterAosInvert, settings.invertAosTimeWindow)
        putString(keyFilterCategories, settings.categories.encodeToString())
        putBoolean(keyFilterOnlyAmSatHeard, settings.onlyAmSatHeard)
        _passesSettings.value = settings
    }

    private fun getPassesSettings(): PassesSettings {
        val showDeepSpace = preferences.getBoolean(keyFilterShowDeepSpace, true)
        val hoursAhead = preferences.getInt(keyFilterHoursAhead, 24)
        val minElevation = Double.fromBits(preferences.getLong(keyFilterMinElevation, 16.0.toRawBits()))
        val aosStartMinute = preferences.getInt(keyFilterAosStartMinute, 0).coerceIn(0, 23 * 60 + 59)
        val aosEndMinute = preferences.getInt(keyFilterAosEndMinute, 23 * 60 + 59).coerceIn(0, 23 * 60 + 59)
        val invertAosTimeWindow = preferences.getBoolean(keyFilterAosInvert, false)
        val categories = decodeFilterCategories(preferences.getString(keyFilterCategories, null))
            ?: migrateLegacyFilter()
        return PassesSettings(
            showDeepSpace,
            hoursAhead,
            minElevation,
            aosStartMinute,
            aosEndMinute,
            invertAosTimeWindow,
            categories,
            preferences.getBoolean(keyFilterOnlyAmSatHeard, false)
        )
    }

    /**
     * Folds the pre-category flat mode/band filter into a single include category. Only reached
     * while [keyFilterCategories] is absent, so clearing every category stays cleared.
     */
    private fun migrateLegacyFilter(): List<FilterCategory> {
        val bandsString = preferences.getString(keySelectedBands, null)
        val bands = bandsString?.split(separatorComma)?.filter { it.isNotBlank() } ?: emptyList()
        val modes = getSelectedSatModes()
        if (modes.isEmpty() && bands.isEmpty()) return emptyList()
        return listOf(FilterCategory(name = "Filter 1", modes = modes, bands = bands))
    }
    //endregion

    //region # Station position settings
    private val _stationPosition = MutableStateFlow(getStationPosition())
    private val providerDef = LocationManager.PASSIVE_PROVIDER
    private val providerGps = LocationManager.GPS_PROVIDER
    private val providerNet = LocationManager.NETWORK_PROVIDER
    override val stationPosition: StateFlow<GeoPos> = _stationPosition

    override fun onLocationChanged(location: Location) {
        setStationPosition(location.latitude, location.longitude, location.altitude)
    }

    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double): Boolean {
        val newLongitude = if (longitude > 180.0) longitude - 180 else longitude
        val locator = positionToQth(latitude, newLongitude) ?: return false
        setStationPosition(latitude, newLongitude, altitude, locator)
        return true
    }

    override fun setStationPosition(): Boolean {
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) return false
        try {
            val hasGps = LocationManagerCompat.hasProvider(locationManager, providerGps)
            val hasNet = LocationManagerCompat.hasProvider(locationManager, providerNet)
            val provider = if (hasGps) providerGps else if (hasNet) providerNet else providerDef
            val location = locationManager.getLastKnownLocation(providerDef)
            if (location == null || System.currentTimeMillis() - location.time > 600_000L) {
                println("Requesting location for $provider provider")
                locationManager.requestLocationUpdates(provider, 0L, 0f, this)
            } else {
                setStationPosition(location.latitude, location.longitude, location.altitude)
            }
        } catch (exception: SecurityException) {
            println("No permissions were given - $exception")
        }
        return true
    }

    override fun syncWithGps() {
        val settings = _otherSettings.value
        if (!settings.stateOfAutoGps && !settings.stateOfGpsTime) return
        requestGpsLocation(::applyGpsFix)
    }

    override fun requestGpsFix(onFix: (latitude: Double, longitude: Double) -> Unit): Boolean =
        requestGpsLocation { fix -> onFix(fix.latitude, fix.longitude) }

    /** One fix from the GPS provider; false when location is off, absent or not permitted. */
    private fun requestGpsLocation(onFix: (Location) -> Unit): Boolean {
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) return false
        try {
            // Only the GPS provider will do: the others stamp their fixes with the phone's own
            // clock, which is the very thing the time sync corrects.
            if (!LocationManagerCompat.hasProvider(locationManager, providerGps)) return false
            val noCancel: CancellationSignal? = null
            LocationManagerCompat.getCurrentLocation(locationManager, providerGps, noCancel, { it.run() }) { fix ->
                if (fix != null) onFix(fix)
            }
            return true
        } catch (exception: SecurityException) {
            println("No permissions were given - $exception")
            return false
        }
    }

    /** Settings are read again here: the fix can land seconds after it was asked for. */
    private fun applyGpsFix(fix: Location) {
        val settings = _otherSettings.value
        if (settings.stateOfGpsTime) {
            // The fix carries GPS time as of when it was taken; age it to now on the monotonic clock.
            val ageMillis = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000L
            AppClock.offsetMillis = fix.time + ageMillis - System.currentTimeMillis()
            println("GPS time offset is ${AppClock.offsetMillis} ms")
        }
        if (settings.stateOfAutoGps) setStationPosition(fix.latitude, fix.longitude, fix.altitude)
    }

    override fun setStationPosition(locator: String): Boolean {
        val position = qthToPosition(locator) ?: return false
        setStationPosition(position.latitude, position.longitude, 0.0, locator)
        return true
    }

    private fun getStationPosition(): GeoPos {
        val latitude = (preferences.getString(keyStationLatitude, null) ?: "0.0").toDouble()
        val longitude = (preferences.getString(keyStationLongitude, null) ?: "0.0").toDouble()
        val altitude = (preferences.getString(keyStationAltitude, null) ?: "0.0").toDouble()
        val qthLocator = preferences.getString(keyStationQth, null) ?: "JJ00aa"
        val timestamp = preferences.getLong(keyStationTimestamp, 0L)
        return GeoPos(latitude, longitude, altitude, qthLocator, timestamp)
    }

    private fun setStationPosition(latitude: Double, longitude: Double, altitude: Double, locator: String) {
        val newLat = latitude.round(4)
        val newLon = longitude.round(4)
        val newAlt = altitude.round(1)
        val timestamp = System.currentTimeMillis()
        println("Received new Position($newLat, $newLon, $newAlt) & Locator $locator")
        setStationPosition(GeoPos(newLat, newLon, newAlt, locator, timestamp))
    }

    private fun setStationPosition(stationPos: GeoPos) = preferences.edit {
        putString(keyStationLatitude, stationPos.latitude.toString())
        putString(keyStationLongitude, stationPos.longitude.toString())
        putString(keyStationAltitude, stationPos.altitude.toString())
        putString(keyStationQth, stationPos.qthLocator)
        putLong(keyStationTimestamp, stationPos.timestamp)
        _stationPosition.value = stationPos
    }
    //endregion

    //region # Database update settings
    private val _databaseState = MutableStateFlow(getDatabaseState())
    override val databaseState: StateFlow<DatabaseState> = _databaseState


    override fun updateDatabaseState(state: DatabaseState) = preferences.edit {
        putInt(keyNumberOfSatellites, state.numberOfSatellites)
        putInt(keyNumberOfRadios, state.numberOfRadios)
        putLong(keyUpdateTimestamp, state.updateTimestamp)
        _databaseState.value = state
    }

    private fun getDatabaseState(): DatabaseState {
        val numberOfRadios = preferences.getInt(keyNumberOfRadios, 0)
        val numberOfSatellites = preferences.getInt(keyNumberOfSatellites, 0)
        val updateTimestamp = preferences.getLong(keyUpdateTimestamp, 0L)
        return DatabaseState(numberOfRadios, numberOfSatellites, updateTimestamp)
    }
    //endregion

    //region # RC settings
    init {
        migrateRCFormats()
    }

    // TODO: Remove after a few releases (added in v4.2.0)
    private val keyRCFormatsMigrated = "rcFormatsMigrated"

    private fun migrateRCFormats() {
        if (preferences.getBoolean(keyRCFormatsMigrated, false)) return
        val formatKeys = listOf(
            keyRotatorFormat, keyFrequencyFormat, keyBluetoothRotatorFormat, keyBluetoothFrequencyFormat
        )
        preferences.edit {
            for (key in formatKeys) {
                val value = preferences.getString(key, null) ?: continue
                if (value.contains("_") && !value.startsWith("\\")) {
                    putString(key, "\\$value")
                }
            }
            putBoolean(keyRCFormatsMigrated, true)
        }
    }

    private val _rcSettings = MutableStateFlow(getRCSettings())
    override val rcSettings: StateFlow<RCSettings> = _rcSettings

    override fun updateRCSettings(settings: RCSettings) {
        val clampedFreqOffsetHz = settings.frequencyOffsetHz.coerceIn(
            Constants.FREQ_OFFSET_MIN_HZ,
            Constants.FREQ_OFFSET_MAX_HZ
        )
        preferences.edit {
            putBoolean(keyRotatorState, settings.rotatorState)
            putString(keyRotatorAddress, settings.rotatorAddress)
            putString(keyRotatorPort, settings.rotatorPort)
            putString(keyRotatorFormat, settings.rotatorFormat)
            putBoolean(keyFrequencyState, settings.frequencyState)
            putString(keyFrequencyAddress, settings.frequencyAddress)
            putString(keyFrequencyPort, settings.frequencyPort)
            putString(keyFrequencyFormat, settings.frequencyFormat)
            putLong(keyFrequencyOffsetHz, clampedFreqOffsetHz)
            putBoolean(keyBluetoothRotatorState, settings.bluetoothRotatorState)
            putString(keyBluetoothRotatorFormat, settings.bluetoothRotatorFormat)
            putString(keyBluetoothRotatorName, settings.bluetoothRotatorName)
            putString(keyBluetoothRotatorAddress, settings.bluetoothRotatorAddress)
            putBoolean(keyBluetoothFrequencyState, settings.bluetoothFrequencyState)
            putString(keyBluetoothFrequencyFormat, settings.bluetoothFrequencyFormat)
            putString(keyBluetoothFrequencyAddress, settings.bluetoothFrequencyAddress)
        }
        _rcSettings.value = settings.copy(frequencyOffsetHz = clampedFreqOffsetHz)
    }

    private fun getRCSettings(): RCSettings = RCSettings(
        rotatorState = preferences.getBoolean(keyRotatorState, false),
        rotatorAddress = preferences.getString(keyRotatorAddress, null) ?: "127.0.0.1",
        rotatorPort = preferences.getString(keyRotatorPort, null) ?: "4533",
        rotatorFormat = preferences.getString(keyRotatorFormat, null) ?: $$"P $AZ $EL",
        frequencyState = preferences.getBoolean(keyFrequencyState, false),
        frequencyAddress = preferences.getString(keyFrequencyAddress, null) ?: "127.0.0.1",
        frequencyPort = preferences.getString(keyFrequencyPort, null) ?: "4532",
        frequencyFormat = preferences.getString(keyFrequencyFormat, null) ?: $$"F $FREQ",
        frequencyOffsetHz = preferences.getLong(keyFrequencyOffsetHz, 0L)
            .coerceIn(Constants.FREQ_OFFSET_MIN_HZ, Constants.FREQ_OFFSET_MAX_HZ),
        bluetoothRotatorState = preferences.getBoolean(keyBluetoothRotatorState, false),
        bluetoothRotatorFormat = preferences.getString(keyBluetoothRotatorFormat, null) ?: $$"P $AZ $EL",
        bluetoothRotatorName = preferences.getString(keyBluetoothRotatorName, null) ?: "Default",
        bluetoothRotatorAddress = preferences.getString(keyBluetoothRotatorAddress, null) ?: "00:0C:BF:13:80:5D",
        bluetoothFrequencyState = preferences.getBoolean(keyBluetoothFrequencyState, false),
        bluetoothFrequencyAddress = preferences.getString(keyBluetoothFrequencyAddress, null) ?: "00:0C:BF:13:80:5D",
        bluetoothFrequencyFormat = preferences.getString(keyBluetoothFrequencyFormat, null) ?: $$"F $FREQ"
    )
    //endregion

    //region # Other settings
    private val _otherSettings = MutableStateFlow(getOtherSettings())
    override val otherSettings: StateFlow<OtherSettings> = _otherSettings

    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) {
        _otherSettings.update { current ->
            val new = transform(current)
            preferences.edit {
                putBoolean(keyStateOfAutoUpdate, new.stateOfAutoUpdate)
                putInt(keyAutoUpdateIntervalMin, new.autoUpdateIntervalMin)
                putBoolean(keyStateOfAutoGps, new.stateOfAutoGps)
                putBoolean(keyStateOfGpsTime, new.stateOfGpsTime)
                putBoolean(keyStateOfSensors, new.stateOfSensors)
                putBoolean(keyStateOfSweep, new.stateOfSweep)
                putBoolean(keyStateOfUtc, new.stateOfUtc)
                putBoolean(keyStateOfLightTheme, new.stateOfLightTheme)
                putBoolean(keyStateOfNightMode, new.stateOfNightMode)
                putBoolean(keyShouldSeeWarning, new.shouldSeeWarning)
                putBoolean(keyShouldSeeWhatsNew, new.shouldSeeWhatsNew)
                putString(keySstvMode, new.sstvMode)
                putString(keyAudioSource, new.audioSource.name)
                putLong(keyLowElevation, new.lowElevation.toRawBits())
                putLong(keyHighElevation, new.highElevation.toRawBits())
                putFloat(keyRadarCompassOffset, new.radarCompassOffset)
                putFloat(keyRadarCompassOffsetElev, new.radarCompassOffsetElev)
            }
            new
        }
        // Switching GPS time off must take the correction with it, not leave it until restart.
        if (!_otherSettings.value.stateOfGpsTime) AppClock.offsetMillis = 0L
    }

    private fun getOtherSettings(): OtherSettings = OtherSettings(
        stateOfAutoUpdate = preferences.getBoolean(keyStateOfAutoUpdate, true),
        stateOfSensors = preferences.getBoolean(keyStateOfSensors, true),
        stateOfSweep = preferences.getBoolean(keyStateOfSweep, true),
        stateOfUtc = preferences.getBoolean(keyStateOfUtc, false),
        stateOfLightTheme = preferences.getBoolean(keyStateOfLightTheme, false),
        stateOfNightMode = preferences.getBoolean(keyStateOfNightMode, false),
        shouldSeeWarning = preferences.getBoolean(keyShouldSeeWarning, true),
        shouldSeeWhatsNew = preferences.getBoolean(keyShouldSeeWhatsNew, true),
        sstvMode = preferences.getString(keySstvMode, null) ?: "Auto",
        audioSource = AudioSource.entries.find { it.name == preferences.getString(keyAudioSource, null) } ?: AudioSource.Mic,
        lowElevation = Double.fromBits(preferences.getLong(keyLowElevation, 15.0.toRawBits())),
        highElevation = Double.fromBits(preferences.getLong(keyHighElevation, 45.0.toRawBits())),
        radarCompassOffset = preferences.getFloat(keyRadarCompassOffset, 0f),
        radarCompassOffsetElev = preferences.getFloat(keyRadarCompassOffsetElev, 0f),
        autoUpdateIntervalMin = preferences.getInt(keyAutoUpdateIntervalMin, OtherSettings.DEFAULT_AUTO_UPDATE_INTERVAL_MIN),
        stateOfAutoGps = preferences.getBoolean(keyStateOfAutoGps, false),
        stateOfGpsTime = preferences.getBoolean(keyStateOfGpsTime, false)
    )
    //endregion

    //region # Data sources settings
    private val _dataSourcesSettings = MutableStateFlow(getDataSourcesSettings())
    override val dataSourcesSettings: StateFlow<DataSourcesSettings> = _dataSourcesSettings

    override fun updateDataSourcesSettings(settings: DataSourcesSettings) {
        // Normalize the enabled lists so they are positionally aligned with the URL lists.
        // Missing entries default to enabled (true), keeping the persisted "one flag per URL"
        // invariant intact even when a default empty list is used to construct the model.
        val normalized = settings.copy(
            satelliteEnabled = alignFlags(settings.satelliteUrls, settings.satelliteEnabled),
            transceiversEnabled = alignFlags(settings.transceiversUrls, settings.transceiversEnabled)
        )
        preferences.edit {
            putString(keySatellitesUrls, normalized.satelliteUrls.joinToString(separatorUrl))
            putString(keyTransceiversUrls, normalized.transceiversUrls.joinToString(separatorUrl))
            putString(keySatellitesUrlsEnabled, normalized.satelliteEnabled.joinToString(separatorComma))
            putString(keyTransceiversUrlsEnabled, normalized.transceiversEnabled.joinToString(separatorComma))
        }
        _dataSourcesSettings.value = normalized
    }

    private fun getDataSourcesSettings(): DataSourcesSettings {
        val (satUrls, satEnabled) = parseSources(
            preferences.getString(keySatellitesUrls, null),
            preferences.getString(keySatellitesUrlsEnabled, null),
            Sources.satelliteDataUrls
        )
        val (txUrls, txEnabled) = parseSources(
            preferences.getString(keyTransceiversUrls, null),
            preferences.getString(keyTransceiversUrlsEnabled, null),
            Sources.transceiversDataUrls
        )
        return DataSourcesSettings(
            satelliteUrls = satUrls,
            transceiversUrls = txUrls,
            satelliteEnabled = satEnabled,
            transceiversEnabled = txEnabled
        )
    }

    private fun parseSources(
        storedUrls: String?,
        storedEnabled: String?,
        defaults: List<String>
    ): Pair<List<String>, List<Boolean>> {
        if (storedUrls == null) return defaults to defaults.map { true }
        val urls = storedUrls.split(separatorUrl)
        val flags = if (storedEnabled.isNullOrEmpty()) emptyList() else storedEnabled.split(separatorComma)
        val filteredUrls = mutableListOf<String>()
        val filteredFlags = mutableListOf<Boolean>()
        urls.forEachIndexed { index, url ->
            if (url.isNotBlank()) {
                filteredUrls.add(url)
                filteredFlags.add(flags.getOrNull(index)?.toBoolean() ?: true)
            }
        }
        return filteredUrls to filteredFlags
    }

    private fun alignFlags(urls: List<String>, flags: List<Boolean>): List<Boolean> {
        if (flags.size >= urls.size) return flags.take(urls.size)
        return flags + List(urls.size - flags.size) { true }
    }
    //endregion

    //region # Data sources status
    private val _dataSourcesStatus = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val dataSourcesStatus: StateFlow<Map<String, Int>> = _dataSourcesStatus

    override fun updateDataSourcesStatus(status: Map<String, Int>) {
        _dataSourcesStatus.value = status
    }
    //endregion

    //region # Radio control settings
    private val keyRadioControlEnabled = "radioControlEnabled"
    private val keyRadioModel = "radioModel"
    private val keyTxRadioAddress = "txRadioAddress"
    private val keyRxRadioAddress = "rxRadioAddress"
    private val keyTxRadioName = "txRadioName"
    private val keyRxRadioName = "rxRadioName"
    private val keyRadioBaudRate = "radioBaudRate"
    private val keyRadioSplitMode = "radioSplitMode"

    private val _radioControlSettings = MutableStateFlow(getRadioControlSettings())
    override val radioControlSettings: StateFlow<RadioControlSettings> = _radioControlSettings

    override fun updateRadioControlSettings(settings: RadioControlSettings) {
        preferences.edit {
            putBoolean(keyRadioControlEnabled, settings.enabled)
            putString(keyRadioModel, settings.radioModel)
            putString(keyTxRadioAddress, settings.txRadioAddress)
            putString(keyRxRadioAddress, settings.rxRadioAddress)
            putString(keyTxRadioName, settings.txRadioName)
            putString(keyRxRadioName, settings.rxRadioName)
            putInt(keyRadioBaudRate, settings.baudRate)
            putBoolean(keyRadioSplitMode, settings.splitMode)
        }
        _radioControlSettings.value = settings
    }

    private fun getRadioControlSettings(): RadioControlSettings = RadioControlSettings(
        enabled = preferences.getBoolean(keyRadioControlEnabled, false),
        radioModel = RadioControlSettings.normalizeModel(preferences.getString(keyRadioModel, null)),
        txRadioAddress = preferences.getString(keyTxRadioAddress, null) ?: "",
        rxRadioAddress = preferences.getString(keyRxRadioAddress, null) ?: "",
        txRadioName = preferences.getString(keyTxRadioName, null) ?: "TX Radio",
        rxRadioName = preferences.getString(keyRxRadioName, null) ?: "RX Radio",
        baudRate = preferences.getInt(keyRadioBaudRate, 4800),
        splitMode = preferences.getBoolean(keyRadioSplitMode, false)
    )

    private val keyN76Enabled = "n76Enabled"
    private val keyN76DeviceAddress = "n76DeviceAddress"
    private val keyN76DeviceName = "n76DeviceName"
    private val keyN76SendSatInfo = "n76SendSatInfo"
    private val keyN76SatFirmware = "n76SatFirmware"
    private val keyN76PollMs = "n76PollMs"
    private val keyN76SendPosition = "n76SendPosition"
    private val keyN76SendTxPower = "n76SendTxPower"
    private val keyN76TxPower = "n76TxPower"
    private val keyN76OpenSquelch = "n76OpenSquelch"
    private val keyN76MicGainDb = "n76MicGainDb"
    private val keyN76ForceRx = "n76ForceRx"
    private val keyN76ForceRxTone = "n76ForceRxTone"
    private val keyN76ForceTx = "n76ForceTx"
    private val keyN76ForceTxTone = "n76ForceTxTone"
    private val keyN76AudioRfcomm = "n76AudioRfcomm"
    private val keyN76Monitor = "n76Monitor"
    private val keyN76RecordHt = "n76RecordHt"
    private val keyN76RecordMic = "n76RecordMic"
    private val keyN76RecordSatOnly = "n76RecordSatOnly"
    private val keyN76OutputFolder = "n76OutputFolder"
    private val keyN76InputDeviceId = "n76InputDeviceId"

    private val _n76Settings = MutableStateFlow(getN76Settings())
    override val n76Settings: StateFlow<N76Settings> = _n76Settings

    override fun updateN76Settings(settings: N76Settings) {
        preferences.edit {
            putBoolean(keyN76Enabled, settings.enabled)
            putString(keyN76DeviceAddress, settings.deviceAddress)
            putString(keyN76DeviceName, settings.deviceName)
            putBoolean(keyN76SendSatInfo, settings.sendSatInfo)
            putBoolean(keyN76SatFirmware, settings.satFirmware)
            putLong(keyN76PollMs, settings.pollIntervalMs)
            putBoolean(keyN76SendPosition, settings.sendPosition)
            putBoolean(keyN76SendTxPower, settings.sendTxPower)
            putString(keyN76TxPower, settings.txPower.name)
            putBoolean(keyN76OpenSquelch, settings.openSquelchOnTrack)
            putInt(keyN76MicGainDb, settings.micGainDb)
            putBoolean(keyN76ForceRx, settings.forceRxCtcss)
            putInt(keyN76ForceRxTone, settings.forceRxCtcssHzx100)
            putBoolean(keyN76ForceTx, settings.forceTxCtcss)
            putInt(keyN76ForceTxTone, settings.forceTxCtcssHzx100)
            putBoolean(keyN76AudioRfcomm, settings.audioRfcomm)
            putBoolean(keyN76Monitor, settings.speakerMonitor)
            putBoolean(keyN76RecordHt, settings.recordHt)
            putBoolean(keyN76RecordMic, settings.recordMic)
            putBoolean(keyN76RecordSatOnly, settings.recordSatOnly)
            putString(keyN76OutputFolder, settings.outputFolderUri)
            putInt(keyN76InputDeviceId, settings.inputDeviceId)
        }
        _n76Settings.value = settings
    }

    /**
     * Reads the N76 options, migrating installs that configured it as a CAT radio model. Those
     * have no `n76Enabled` key but do have "VGC N76"/"HYS N76" stored as the radio model, with
     * the handheld's MAC in the CAT TX address — so the flag and the device are taken from there
     * once, and written back on the next save.
     */
    private fun getN76Settings(): N76Settings {
        val storedAsCatModel = RadioControlSettings.isN76Model(preferences.getString(keyRadioModel, null))
        return N76Settings(
            enabled = preferences.getBoolean(keyN76Enabled, storedAsCatModel),
            deviceAddress = preferences.getString(keyN76DeviceAddress, null)
                ?: if (storedAsCatModel) preferences.getString(keyTxRadioAddress, null).orEmpty() else "",
            deviceName = preferences.getString(keyN76DeviceName, null)
                ?: if (storedAsCatModel) preferences.getString(keyTxRadioName, null).orEmpty() else "",
            sendSatInfo = preferences.getBoolean(keyN76SendSatInfo, true),
            satFirmware = preferences.getBoolean(keyN76SatFirmware, true),
            pollIntervalMs = preferences.getLong(keyN76PollMs, N76Settings.POLL_DEFAULT_MS)
                .coerceIn(N76Settings.POLL_MIN_MS, N76Settings.POLL_MAX_MS),
            sendPosition = preferences.getBoolean(keyN76SendPosition, true),
            sendTxPower = preferences.getBoolean(keyN76SendTxPower, false),
            txPower = N76TxPower.entries.find { it.name == preferences.getString(keyN76TxPower, null) }
                ?: N76TxPower.High,
            openSquelchOnTrack = preferences.getBoolean(keyN76OpenSquelch, false),
            micGainDb = preferences.getInt(keyN76MicGainDb, 0),
            forceRxCtcss = preferences.getBoolean(keyN76ForceRx, false),
            forceRxCtcssHzx100 = preferences.getInt(keyN76ForceRxTone, 0),
            forceTxCtcss = preferences.getBoolean(keyN76ForceTx, false),
            forceTxCtcssHzx100 = preferences.getInt(keyN76ForceTxTone, 0),
            audioRfcomm = preferences.getBoolean(keyN76AudioRfcomm, false),
            speakerMonitor = preferences.getBoolean(keyN76Monitor, false),
            recordHt = preferences.getBoolean(keyN76RecordHt, false),
            recordMic = preferences.getBoolean(keyN76RecordMic, false),
            recordSatOnly = preferences.getBoolean(keyN76RecordSatOnly, false),
            outputFolderUri = preferences.getString(keyN76OutputFolder, null) ?: "",
            inputDeviceId = preferences.getInt(keyN76InputDeviceId, 0)
        )
    }

    private val keySatelliteOffsets = "satelliteOffsets"

    override fun getSatelliteOffset(catnum: Int): String {
        val json = preferences.getString(keySatelliteOffsets, "{}") ?: "{}"
        return try {
            JSONObject(json).optString(catnum.toString(), "")
        } catch (_: Exception) {
            ""
        }
    }

    override fun setSatelliteOffset(catnum: Int, offset: String) {
        val json = preferences.getString(keySatelliteOffsets, "{}") ?: "{}"
        val updated = try {
            val obj = JSONObject(json)
            if (offset.isEmpty()) obj.remove(catnum.toString()) else obj.put(catnum.toString(), offset)
            obj.toString()
        } catch (_: Exception) {
            """{"$catnum": "$offset"}"""
        }
        preferences.edit { putString(keySatelliteOffsets, updated) }
    }

    //region # AMSAT status report settings
    private val keyAmSatCallsign = "amSatCallsign"

    override fun getAmSatCallsign(): String {
        return preferences.getString(keyAmSatCallsign, "").orEmpty()
    }

    override fun setAmSatCallsign(callsign: String) {
        preferences.edit { putString(keyAmSatCallsign, callsign.trim().uppercase(Locale.US)) }
    }
    //endregion

    //region # APRS settings
    private val keyAprsCallsign = "aprsCallsign"
    private val keyAprsMessage = "aprsMessage"
    private val keyAprsPath = "aprsPath"
    private val keyAprsLocation = "aprsLocation"
    private val keyAprsTransport = "aprsTransport"
    private val keyAprsTncAddress = "aprsTncAddress"
    private val keyAprsAudioSource = "aprsAudioSource"
    private val keyAprsAudioOutput = "aprsAudioOutput"
    private val keyAprsSymbol = "aprsSymbol"
    private val keyAprsBeaconSeconds = "aprsBeaconSeconds"

    private val _aprsSettings = MutableStateFlow(getAprsSettings())
    override val aprsSettings: StateFlow<AprsSettings> = _aprsSettings

    override fun updateAprsSettings(settings: AprsSettings) {
        preferences.edit {
            putString(keyAprsCallsign, settings.callsign)
            putString(keyAprsMessage, settings.message)
            putString(keyAprsPath, settings.path)
            putString(keyAprsLocation, settings.location)
            putString(keyAprsTransport, settings.transport.name)
            putString(keyAprsTncAddress, settings.tncAddress)
            putString(keyAprsAudioSource, settings.audioSource.name)
            putInt(keyAprsAudioOutput, settings.audioOutputId)
            putString(keyAprsSymbol, settings.symbol)
            putInt(keyAprsBeaconSeconds, settings.beaconSeconds)
        }
        _aprsSettings.value = settings
    }

    /** The callsign starts out as the one already given for AMSAT reports. */
    private fun getAprsSettings(): AprsSettings = AprsSettings(
        callsign = preferences.getString(keyAprsCallsign, null) ?: getAmSatCallsign(),
        message = preferences.getString(keyAprsMessage, null) ?: "",
        path = preferences.getString(keyAprsPath, null) ?: AprsSettings.DEFAULT_PATH,
        location = preferences.getString(keyAprsLocation, null) ?: "",
        transport = AprsTransport.entries.find { it.name == preferences.getString(keyAprsTransport, null) }
            ?: AprsTransport.N76,
        tncAddress = preferences.getString(keyAprsTncAddress, null) ?: "",
        audioSource = AudioSource.entries.find { it.name == preferences.getString(keyAprsAudioSource, null) }
            ?: AudioSource.Mic,
        audioOutputId = preferences.getInt(keyAprsAudioOutput, 0),
        symbol = preferences.getString(keyAprsSymbol, null) ?: AprsSettings.DEFAULT_SYMBOL,
        beaconSeconds = preferences.getInt(keyAprsBeaconSeconds, 60)
    )
    //endregion
}
