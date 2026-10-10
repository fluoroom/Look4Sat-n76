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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.AprsSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class N76RuntimeState(
    val isN76: Boolean = false,
    val logs: List<String> = emptyList(),
    val audioActive: Boolean = false,
    val recording: Boolean = false,
    val playing: Boolean = false,
    val monitorOn: Boolean = false,
    val autoRecord: Boolean = false,
    val lastRecordPath: String? = null
)

data class RadioTrackingState(
    val isActive: Boolean = false,
    val txConnected: Boolean = false,
    val rxConnected: Boolean = false,
    val txFrequencyHz: Long? = null,
    val rxFrequencyHz: Long? = null,
    val txMode: String? = null,
    val rxMode: String? = null,
    val ctcssTone: Double? = null,
    val txBaseFrequencyHz: Long? = null,
    val selectedTransponder: SatRadio? = null,
    val currentPass: OrbitalPass? = null,
    val azimuth: Double = 0.0,
    val elevation: Double = 0.0,
    val distance: Double = 0.0,
    val errorMessage: String? = null
)

interface IRadioTrackingService {
    val state: StateFlow<RadioTrackingState>
    val n76State: StateFlow<N76RuntimeState>

    suspend fun connectRadios()
    suspend fun disconnectRadios()
    fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?)
    fun stopTracking()
    fun setTransponder(transponder: SatRadio)
    fun setTxBaseFrequency(frequencyHz: Long)
    fun adjustTxBaseFrequency(deltaHz: Long)
    fun setCtcssTone(toneHz: Double?)
    fun setMode(txMode: String, rxMode: String)
    fun setPtt(on: Boolean)
    /** AX.25 frames heard by the Bluetooth TNC, as they arrive. */
    val aprsReceived: SharedFlow<ByteArray>

    /**
     * Transmits once. A TNC or the audio output is handed [frame] (AX.25, no flags, no FCS); the
     * N76 ignores it and is asked to send its own beacon, from the APRS settings stored in it.
     * Returns null once handed over, else why not.
     */
    suspend fun sendAprs(frame: ByteArray, settings: AprsSettings): String?

    /**
     * Starts passing on what [settings]' connection hears, which means connecting the Bluetooth
     * TNC. Null on success. Audio is decoded by the caller; the N76 has nothing to pass on.
     */
    suspend fun startAprsReceive(settings: AprsSettings): String?
    fun setN76Monitor(on: Boolean)
    fun startN76Recording()
    fun stopN76Recording()
    fun playLastN76Recording()
    fun stopN76Playback()
}
