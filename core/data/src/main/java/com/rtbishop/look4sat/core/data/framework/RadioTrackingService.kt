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
package com.rtbishop.look4sat.core.data.framework

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.rtbishop.look4sat.core.domain.model.BluetoothAddress
import com.rtbishop.look4sat.core.domain.model.N76Settings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.IRadioTrackingService
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.N76RuntimeState
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingState
import com.rtbishop.look4sat.core.domain.utility.TransponderMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class RadioTrackingService(
    private val appScope: CoroutineScope,
    private val bluetoothManager: BluetoothManager,
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    context: Context
) : IRadioTrackingService {

    private val tag = "RadioTracking"
    /** Delay between each step of the split-mode init sequence (ms). */
    private val INIT_STEP_DELAY_MS = 200L
    private val _state = MutableStateFlow(RadioTrackingState())
    override val state: StateFlow<RadioTrackingState> = _state

    private val _n76State = MutableStateFlow(
        N76RuntimeState(
            isN76 = settingsRepo.n76Settings.value.enabled,
            monitorOn = settingsRepo.n76Settings.value.speakerMonitor,
            autoRecord = settingsRepo.n76Settings.value.recordSatOnly
        )
    )
    override val n76State: StateFlow<N76RuntimeState> = _n76State

    private val n76Link = N76Link(context).apply {
        onLog = { line ->
            Log.d("N76", line)
            _n76State.update { it.copy(logs = (it.logs + line).takeLast(80)) }
        }
        onAudioActive = { active -> _n76State.update { it.copy(audioActive = active) } }
        onRecordingChanged = { rec -> _n76State.update { it.copy(recording = rec) } }
        onPlayingChanged = { play -> _n76State.update { it.copy(playing = play) } }
        onSaved = { path -> _n76State.update { it.copy(lastRecordPath = path) } }
    }

    private var txController: IRadioController? = null
    private var rxController: IRadioController? = null
    private var trackingJob: Job? = null
    private var n76SatActive = false

    // ── Connection ──────────────────────────────────────────────────────────

    override suspend fun connectRadios() {
        txController?.disconnect()
        rxController?.disconnect()
        n76Link.disconnect()
        n76SatActive = false

        val rcSettings = settingsRepo.radioControlSettings.value
        val n76Settings = settingsRepo.n76Settings.value
        val txAddr     = rcSettings.txRadioAddress
        val rxAddr     = rcSettings.rxRadioAddress
        // The N76 is its own Bluetooth link rather than a CAT model, and takes precedence when on.
        val isN76      = n76Settings.enabled
        val isIcom     = rcSettings.radioModel == RadioControlSettings.MODEL_ICOM_IC705
        val isSplit    = isIcom && rcSettings.splitMode
        _n76State.update {
            it.copy(
                isN76 = isN76,
                monitorOn = n76Settings.speakerMonitor,
                autoRecord = n76Settings.recordSatOnly,
                logs = emptyList()
            )
        }

        Log.i(tag, "connectRadios model=${rcSettings.radioModel} n76=$isN76 split=$isSplit TX=$txAddr RX=$rxAddr")

        if (isN76) {
            val n76Addr = n76Settings.deviceAddress
            if (n76Addr.isBlank() || !BluetoothAddress.isValid(n76Addr)) {
                _state.update { it.copy(errorMessage = "Set a valid N76 MAC in Settings (type or scan)") }
                return
            }
            _state.update { it.copy(errorMessage = null) }
            val ok = n76Link.connect(n76Addr, n76Settings)
            _state.update {
                it.copy(
                    txConnected = ok,
                    rxConnected = ok,
                    errorMessage = if (!ok) "Could not connect to N76 ($n76Addr)" else null
                )
            }
            return
        }

        if (isSplit) {
            // Single-radio split mode: only TX slot is used
            if (txAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio address configured in Settings") }
                return
            }
            val tx = makeController(isIcom, txAddr)
            txController = tx
            rxController = null
            _state.update { it.copy(errorMessage = null) }
            val txOk = tx.connect()
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = false,
                    errorMessage = if (!txOk) "Could not connect to radio ($txAddr)" else null
                )
            }
            Log.i(tag, "IC-705 split mode connected: txOk=$txOk")
        } else {
            if (txAddr.isBlank() && rxAddr.isBlank()) {
                _state.update { it.copy(errorMessage = "No radio addresses configured in Settings") }
                return
            }
            val tx = makeController(isIcom, txAddr)
            val rx = makeController(isIcom, rxAddr)
            txController = tx
            rxController = rx
            _state.update { it.copy(errorMessage = null) }
            val txOk = if (txAddr.isNotBlank()) tx.connect() else false
            val rxOk = if (rxAddr.isNotBlank()) rx.connect() else false
            _state.update {
                it.copy(
                    txConnected = txOk,
                    rxConnected = rxOk,
                    errorMessage = when {
                        !txOk && !rxOk -> "Could not connect to TX and RX radios"
                        !txOk          -> "Could not connect to TX radio ($txAddr)"
                        !rxOk          -> "Could not connect to RX radio ($rxAddr)"
                        else           -> null
                    }
                )
            }
            Log.i(tag, "Dual-radio connected: txOk=$txOk rxOk=$rxOk")
        }
    }

    private fun makeController(isIcom: Boolean, address: String): IRadioController =
        if (isIcom) Ic705Controller(bluetoothManager, address)
        else        Ft817Controller(bluetoothManager, address)

    override suspend fun disconnectRadios() {
        stopTracking()
        n76Link.disconnect()
        n76SatActive = false
        txController?.disconnect()
        rxController?.disconnect()
        txController = null
        rxController = null
        _state.update { it.copy(txConnected = false, rxConnected = false, isActive = false) }
        _n76State.update { it.copy(audioActive = false, recording = false, playing = false) }
    }

    // ── Tracking ────────────────────────────────────────────────────────────

    override fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?) {
        _state.update {
            it.copy(
                isActive             = true,
                currentPass          = pass,
                selectedTransponder  = transponder,
                txBaseFrequencyHz    = txBaseFreqHz
            )
        }
        trackingJob?.cancel()

        val rcSettings = settingsRepo.radioControlSettings.value
        val isN76      = settingsRepo.n76Settings.value.enabled
        val isIcom     = rcSettings.radioModel == RadioControlSettings.MODEL_ICOM_IC705
        val isSplit    = isIcom && rcSettings.splitMode

        if (isN76) {
            trackingJob = appScope.launch { runN76Tracking() }
        } else if (isSplit) {
            trackingJob = appScope.launch { runSplitTracking(transponder, txBaseFreqHz) }
        } else {
            trackingJob = appScope.launch { runDualRadioTracking(transponder, txBaseFreqHz) }
        }
    }

    // ── Dual-radio tracking (Yaesu or two IC-705s) ──────────────────────────

    private suspend fun runDualRadioTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val tx = txController
        val rx = rxController

        // Initial setup: set band/mode/CTCSS on both radios
        val txMode = transponder.uplinkMode
        val rxMode = transponder.downlinkMode
            ?: transponder.uplinkMode?.let {
                TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
            }

        Log.i(tag, "DualRadio start: txMode=$txMode rxMode=$rxMode")

        if (tx != null && tx.isConnected && txMode != null) {
            Log.d(tag, "Setting TX mode: $txMode")
            tx.setMode(txMode)
        }
        if (rx != null && rx.isConnected && rxMode != null) {
            Log.d(tag, "Setting RX mode: $rxMode")
            rx.setMode(rxMode)
        }
        if (txMode?.uppercase() == "FM") {
            _state.value.ctcssTone?.let { tone ->
                Log.d(tag, "Setting CTCSS: ${tone}Hz")
                tx?.setCtcssTone(tone)
                tx?.setCtcssMode(true)
            }
        }
        _state.update { it.copy(txMode = txMode, rxMode = rxMode) }

        var lastSetTxFreq = 0.0
        var lastSetRxFreq = 0.0
        var tuningRadio   = ""
        var lastReadFreq  = 0L
        var stableCount   = 0

        while (currentCoroutineContext().isActive) {
            val currentState = _state.value
            if (!currentState.isActive) break

            val satPass = currentState.currentPass ?: break
            val xpdr    = currentState.selectedTransponder ?: break
            var txBaseFreq = currentState.txBaseFrequencyHz
            val stationPos = settingsRepo.stationPosition.value
            val pos = satelliteRepo.getPosition(satPass.orbitalObject, stationPos, System.currentTimeMillis())
            val txNow = txController
            val rxNow = rxController
            val v = pos.distanceRate * 1000.0

            if (tuningRadio.isNotEmpty()) {
                val radio = if (tuningRadio == "tx") txNow else rxNow
                if (radio != null && radio.isConnected) {
                    val read = radio.readFrequencyAndMode()
                    if (read != null) {
                        val (freq, _) = read
                        if (kotlin.math.abs(freq - lastReadFreq) <= 20) stableCount++
                        else { stableCount = 0; lastReadFreq = freq }
                        if (stableCount >= 2) {
                            if (tuningRadio == "tx" && txBaseFreq != null) {
                                val newBase = (freq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT + v)).toLong()
                                if (newBase > 0) {
                                    txBaseFreq = newBase
                                    _state.update { it.copy(txBaseFrequencyHz = newBase) }
                                    Log.i(tag, "TX tuning done → base=$newBase")
                                }
                            } else if (tuningRadio == "rx") {
                                val rxNominal = (freq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT - v)).toLong()
                                val newTxBase = TransponderMapper.mapDownlinkToUplink(rxNominal, xpdr)
                                if (newTxBase != null && newTxBase > 0) {
                                    txBaseFreq = newTxBase
                                    _state.update { it.copy(txBaseFrequencyHz = newTxBase) }
                                    Log.i(tag, "RX tuning done → txBase=$newTxBase")
                                }
                            }
                            tuningRadio   = ""
                            stableCount   = 0
                            lastSetTxFreq = 0.0
                            lastSetRxFreq = 0.0
                        }
                    }
                }
            } else {
                // Detect manual dial changes
                if (txBaseFreq != null && txNow != null && txNow.isConnected && lastSetTxFreq > 0.0) {
                    val read = txNow.readFrequencyAndMode()
                    if (read != null && kotlin.math.abs(read.first - lastSetTxFreq) >= 20.0) {
                        tuningRadio  = "tx"
                        lastReadFreq = read.first
                        stableCount  = 0
                        Log.i(tag, "TX tuning detected (read=${read.first}, lastSet=$lastSetTxFreq)")
                    }
                }
                if (tuningRadio.isEmpty() && rxNow != null && rxNow.isConnected && lastSetRxFreq > 0.0) {
                    val read = rxNow.readFrequencyAndMode()
                    if (read != null && kotlin.math.abs(read.first - lastSetRxFreq) >= 20.0) {
                        tuningRadio  = "rx"
                        lastReadFreq = read.first
                        stableCount  = 0
                        Log.i(tag, "RX tuning detected (read=${read.first}, lastSet=$lastSetRxFreq)")
                    }
                }
            }

            val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
            val rxBaseFreq  = if (txBaseFreq != null) {
                TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
            } else xpdr.downlinkLow
            val rxRadioFreq = rxBaseFreq?.let { pos.getDownlinkFreq(it) }

            if (tuningRadio.isEmpty()) {
                if (txNow != null && txNow.isConnected && txRadioFreq != null) {
                    txNow.setFrequency(txRadioFreq)
                    lastSetTxFreq = txRadioFreq.toDouble()
                }
                if (rxNow != null && rxNow.isConnected && rxRadioFreq != null) {
                    rxNow.setFrequency(rxRadioFreq)
                    lastSetRxFreq = rxRadioFreq.toDouble()
                }
            }

            _state.update {
                it.copy(
                    txConnected  = txNow?.isConnected ?: false,
                    rxConnected  = rxNow?.isConnected ?: false,
                    txFrequencyHz = txRadioFreq,
                    rxFrequencyHz = rxRadioFreq,
                    azimuth      = Math.toDegrees(pos.azimuth),
                    elevation    = Math.toDegrees(pos.elevation),
                    distance     = pos.distance
                )
            }
            delay(1000)
        }
    }

    // ── IC-705 split-radio tracking ─────────────────────────────────────────

    private suspend fun runSplitTracking(transponder: SatRadio, initialTxBaseFreqHz: Long?) {
        val radio = txController ?: return
        if (!radio.isConnected) return

        val txMode = transponder.uplinkMode
        val rxMode = transponder.downlinkMode
            ?: transponder.uplinkMode?.let {
                TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
            }

        // Compute nominal base frequencies
        val txCenter = when {
            transponder.uplinkLow != null && transponder.uplinkHigh != null ->
                (transponder.uplinkLow!! + transponder.uplinkHigh!!) / 2
            transponder.uplinkLow != null -> transponder.uplinkLow!!
            else -> null
        }
        val rxNominal = if (txCenter != null) {
            TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        } else transponder.downlinkLow

        val txBase = initialTxBaseFreqHz ?: txCenter
        Log.i(tag, "IC-705 split setup: txBase=${txBase}Hz rxNominal=${rxNominal}Hz txMode=$txMode rxMode=$rxMode")

        // ── Initial setup sequence ──────────────────────────────────────────
        // Sequence per IC-705: explicitly select VFO, then band → freq → mode.
        // ACK from each command gates the next — no fixed delays needed.

        // VFO-A = RX (downlink)
        Log.d(tag, "Split init: selecting VFO-A for RX (downlink)")
        radio.setVfo(vfoA = true)
        if (rxNominal != null) {
            Log.d(tag, "Split init: VFO-A band for ${rxNominal}Hz")
            radio.setBand(rxNominal)
            Log.d(tag, "Split init: VFO-A freq=${rxNominal}Hz")
            radio.setFrequency(rxNominal)
        }
        if (rxMode != null) {
            Log.d(tag, "Split init: VFO-A mode=$rxMode")
            radio.setMode(rxMode)
        }

        // VFO-B = TX (uplink)
        Log.d(tag, "Split init: selecting VFO-B for TX (uplink)")
        radio.setVfo(vfoA = false)
        if (txBase != null) {
            Log.d(tag, "Split init: VFO-B band for ${txBase}Hz")
            radio.setBand(txBase)
            Log.d(tag, "Split init: VFO-B freq=${txBase}Hz")
            radio.setFrequency(txBase)
        }
        if (txMode != null) {
            Log.d(tag, "Split init: VFO-B mode=$txMode")
            radio.setMode(txMode)
        }
        if (txMode?.uppercase() == "FM") {
            val tone = _state.value.ctcssTone
            if (tone != null) {
                Log.d(tag, "Split init: CTCSS=${tone}Hz")
                radio.setCtcssTone(tone)
                radio.setCtcssMode(true)
            } else {
                radio.setCtcssMode(false)
            }
        }

        // Enable SPLIT on VFO-A (return display to RX VFO first)
        Log.d(tag, "Split init: returning to VFO-A, then enabling SPLIT mode")
        radio.setVfo(vfoA = true)
        radio.setSplitMode(enabled = true)

        _state.update { it.copy(txMode = txMode, rxMode = rxMode, txBaseFrequencyHz = txBase) }
        Log.i(tag, "IC-705 split init done — entering tracking loop")

        // ── Tracking loop with tuning detection ─────────────────────────────
        var lastSetTxFreq = 0.0
        var lastSetRxFreq = 0.0
        var tuningRadio   = ""  // "tx" or "rx" when manual tuning detected
        var lastReadFreq  = 0L
        var stableCount   = 0

        while (currentCoroutineContext().isActive) {
            val currentState = _state.value
            if (!currentState.isActive) break

            val satPass = currentState.currentPass ?: break
            val xpdr    = currentState.selectedTransponder ?: break
            var txBaseFreq = currentState.txBaseFrequencyHz
            val stationPos = settingsRepo.stationPosition.value
            val pos = satelliteRepo.getPosition(satPass.orbitalObject, stationPos, System.currentTimeMillis())
            val v = pos.distanceRate * 1000.0

            if (tuningRadio.isNotEmpty()) {
                // User is tuning — wait for frequency to stabilize
                val readFreq = if (tuningRadio == "tx") radio.readTxVfoFrequency() else radio.readWorkingFrequency()
                if (readFreq != null) {
                    if (kotlin.math.abs(readFreq - lastReadFreq) <= 20) stableCount++
                    else { stableCount = 0; lastReadFreq = readFreq }

                    if (stableCount >= 2) {
                        // Frequency stable — reverse-calculate base frequency
                        if (tuningRadio == "tx" && txBaseFreq != null) {
                            val newBase = (readFreq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT + v)).toLong()
                            if (newBase > 0) {
                                txBaseFreq = newBase
                                _state.update { it.copy(txBaseFrequencyHz = newBase) }
                                Log.i(tag, "Split TX tuning done → base=$newBase")
                            }
                        } else if (tuningRadio == "rx") {
                            val rxNominal = (readFreq.toDouble() * SPEED_OF_LIGHT / (SPEED_OF_LIGHT - v)).toLong()
                            val newTxBase = TransponderMapper.mapDownlinkToUplink(rxNominal, xpdr)
                            if (newTxBase != null && newTxBase > 0) {
                                txBaseFreq = newTxBase
                                _state.update { it.copy(txBaseFrequencyHz = newTxBase) }
                                Log.i(tag, "Split RX tuning done → txBase=$newTxBase")
                            }
                        }
                        tuningRadio   = ""
                        stableCount   = 0
                        lastSetTxFreq = 0.0
                        lastSetRxFreq = 0.0
                    }
                }
            } else {
                // Detect manual dial changes
                if (txBaseFreq != null && lastSetTxFreq > 0.0) {
                    val readTx = radio.readTxVfoFrequency()
                    if (readTx != null && kotlin.math.abs(readTx - lastSetTxFreq) >= 20.0) {
                        tuningRadio  = "tx"
                        lastReadFreq = readTx
                        stableCount  = 0
                        Log.i(tag, "Split TX tuning detected (read=${readTx}, lastSet=$lastSetTxFreq)")
                    }
                }
                if (tuningRadio.isEmpty() && lastSetRxFreq > 0.0) {
                    val readRx = radio.readWorkingFrequency()
                    if (readRx != null && kotlin.math.abs(readRx - lastSetRxFreq) >= 20.0) {
                        tuningRadio  = "rx"
                        lastReadFreq = readRx
                        stableCount  = 0
                        Log.i(tag, "Split RX tuning detected (read=${readRx}, lastSet=$lastSetRxFreq)")
                    }
                }
            }

            // Determine Doppler-corrected frequencies
            val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
            val rxBaseCalc  = if (txBaseFreq != null) {
                TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
            } else xpdr.downlinkLow
            val rxRadioFreq = rxBaseCalc?.let { pos.getDownlinkFreq(it) }

            if (radio.isConnected && tuningRadio.isEmpty()) {
                // Update both VFOs every cycle — no PTT polling needed.
                // 0x25/00 = active (RX) VFO, 0x25/01 = inactive (TX) VFO.
                if (rxRadioFreq != null) {
                    Log.d(tag, "Split loop RX (0x25/00): ${rxRadioFreq}Hz")
                    radio.setWorkingFrequency(rxRadioFreq)
                    lastSetRxFreq = rxRadioFreq.toDouble()
                }
                if (txRadioFreq != null) {
                    Log.d(tag, "Split loop TX (0x25/01): ${txRadioFreq}Hz")
                    radio.setTxVfoFrequency(txRadioFreq)
                    lastSetTxFreq = txRadioFreq.toDouble()
                }
            }

            _state.update {
                it.copy(
                    txConnected   = radio.isConnected,
                    rxConnected   = false,  // single radio
                    txFrequencyHz = txRadioFreq,
                    rxFrequencyHz = rxRadioFreq,
                    azimuth       = Math.toDegrees(pos.azimuth),
                    elevation     = Math.toDegrees(pos.elevation),
                    distance      = pos.distance
                )
            }
            delay(1000)
        }
    }

    // ── Other IRadioTrackingService methods ─────────────────────────────────

    override fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        if (n76SatActive) {
            n76Link.exitSatMode()
            n76SatActive = false
        }
        val n76 = settingsRepo.n76Settings.value
        if (n76.recordSatOnly && n76Link.isRecording) n76Link.stopRecording()
        _state.update { it.copy(isActive = false) }
    }

    override fun setTransponder(transponder: SatRadio) {
        appScope.launch {
            val tx = txController
            val rx = rxController
            transponder.uplinkMode?.let { tx?.setMode(it) }
            val rxMode = transponder.downlinkMode
                ?: transponder.uplinkMode?.let {
                    TransponderMapper.mapUplinkModeToDownlinkMode(it, transponder.isInverted)
                }
            rxMode?.let { rx?.setMode(it) }
            if (transponder.uplinkMode?.uppercase() == "FM") {
                _state.value.ctcssTone?.let { tone ->
                    tx?.setCtcssTone(tone)
                    tx?.setCtcssMode(true)
                }
            }
        }
        val txCenter = when {
            transponder.uplinkLow != null && transponder.uplinkHigh != null ->
                (transponder.uplinkLow!! + transponder.uplinkHigh!!) / 2
            transponder.uplinkLow != null -> transponder.uplinkLow!!
            else -> null
        }
        val rxNominal = if (txCenter != null) {
            TransponderMapper.mapUplinkToDownlink(txCenter, transponder)
        } else transponder.downlinkLow
        _state.update {
            it.copy(
                selectedTransponder = transponder,
                txBaseFrequencyHz   = txCenter,
                txFrequencyHz       = txCenter,
                rxFrequencyHz       = rxNominal,
                txMode              = transponder.uplinkMode,
                rxMode              = transponder.downlinkMode
                    ?: transponder.uplinkMode?.let { m ->
                        TransponderMapper.mapUplinkModeToDownlinkMode(m, transponder.isInverted)
                    }
            )
        }
    }

    override fun setTxBaseFrequency(frequencyHz: Long) {
        _state.update { it.copy(txBaseFrequencyHz = frequencyHz) }
    }

    override fun adjustTxBaseFrequency(deltaHz: Long) {
        val current = _state.value.txBaseFrequencyHz ?: return
        _state.update { it.copy(txBaseFrequencyHz = current + deltaHz) }
    }

    override fun setCtcssTone(toneHz: Double?) {
        _state.update { it.copy(ctcssTone = toneHz) }
        appScope.launch {
            val tx = txController
            if (toneHz != null) {
                tx?.setCtcssTone(toneHz)
                tx?.setCtcssMode(true)
            } else {
                tx?.setCtcssMode(false)
            }
        }
    }

    override fun setMode(txMode: String, rxMode: String) {
        appScope.launch {
            txController?.setMode(txMode)
            rxController?.setMode(rxMode)
        }
        _state.update { it.copy(txMode = txMode, rxMode = rxMode) }
    }

    override fun setPtt(on: Boolean) {
        if (settingsRepo.n76Settings.value.enabled) {
            n76Link.setPtt(on)
            return
        }
        appScope.launch {
            if (on) txController?.pttOn() else txController?.pttOff()
        }
    }

    override fun setN76Monitor(on: Boolean) {
        n76Link.setMonitor(on)
        _n76State.update { it.copy(monitorOn = on) }
        settingsRepo.updateN76Settings(settingsRepo.n76Settings.value.copy(speakerMonitor = on))
    }

    override fun startN76Recording() {
        val settings = settingsRepo.n76Settings.value
        val satName = _state.value.currentPass?.name.orEmpty()
        n76Link.startRecording(settings, satName)
    }

    override fun stopN76Recording() {
        n76Link.stopRecording()
    }

    override fun playLastN76Recording() {
        val path = _n76State.value.lastRecordPath ?: run {
            n76Link.onLog?.invoke("play: no recording yet")
            return
        }
        n76Link.playLast(path)
    }

    override fun stopN76Playback() {
        n76Link.stopPlayback()
    }

    private suspend fun runN76Tracking() {
        val n76 = settingsRepo.n76Settings.value
        val satName = _state.value.currentPass?.name.orEmpty()
        if (n76.recordSatOnly && (n76.recordHt || n76.recordMic)) {
            n76Link.startRecording(n76, satName)
        }
        try {
            while (currentCoroutineContext().isActive) {
                val currentState = _state.value
                if (!currentState.isActive) break
                val satPass = currentState.currentPass ?: break
                val xpdr = currentState.selectedTransponder ?: break
                val txBaseFreq = currentState.txBaseFrequencyHz
                val stationPos = settingsRepo.stationPosition.value
                val pos = satelliteRepo.getPosition(satPass.orbitalObject, stationPos, System.currentTimeMillis())
                val txRadioFreq = txBaseFreq?.let { pos.getUplinkFreq(it) }
                val rxBaseFreq = if (txBaseFreq != null) {
                    TransponderMapper.mapUplinkToDownlink(txBaseFreq, xpdr)
                } else xpdr.downlinkLow
                val rxRadioFreq = rxBaseFreq?.let { pos.getDownlinkFreq(it) }
                val live = settingsRepo.n76Settings.value

                if (live.sendSatInfo && n76Link.isConnected) {
                    sendN76Packets(satPass, pos.azimuth, pos.elevation, pos.distance, pos.altitude, txRadioFreq, rxRadioFreq)
                }

                _state.update {
                    it.copy(
                        txConnected = n76Link.isConnected,
                        rxConnected = n76Link.isConnected,
                        txFrequencyHz = txRadioFreq,
                        rxFrequencyHz = rxRadioFreq,
                        azimuth = Math.toDegrees(pos.azimuth),
                        elevation = Math.toDegrees(pos.elevation),
                        distance = pos.distance
                    )
                }
                delay(live.pollIntervalMs.coerceIn(N76Settings.POLL_MIN_MS, N76Settings.POLL_MAX_MS))
            }
        } finally {
            if (n76SatActive) {
                n76Link.exitSatMode()
                n76SatActive = false
            }
            if (n76.recordSatOnly) n76Link.stopRecording()
        }
    }

    private fun sendN76Packets(
        pass: OrbitalPass,
        azimuthRad: Double,
        elevationRad: Double,
        distanceKm: Double,
        altitudeKm: Double,
        txHz: Long?,
        rxHz: Long?
    ) {
        val n76 = settingsRepo.n76Settings.value
        val rx = rxHz ?: 0L
        val tx = txHz ?: 0L
        if (!isValidN76Freq(rx) || !isValidN76Freq(tx)) {
            if (n76SatActive) {
                n76Link.exitSatMode()
                n76SatActive = false
            }
            return
        }
        val tone = _state.value.ctcssTone
        val defaultSub = if (tone != null && tone > 0) (tone * 100).roundToInt() else 0
        val rxSub = if (n76.forceRxCtcss) n76.forceRxCtcssHzx100 else defaultSub
        val txSub = if (n76.forceTxCtcss) n76.forceTxCtcssHzx100 else defaultSub
        val nowMs = System.currentTimeMillis()
        val aosSec = if (pass.aosTime > 0) {
            ((pass.aosTime - nowMs) / 1000L).toInt().coerceIn(0, 65534)
        } else 65535
        n76Link.send(
            N76Protocol.satInfoPacket(
                name = pass.name,
                az = Math.toDegrees(azimuthRad).roundToInt(),
                el = Math.toDegrees(elevationRad).roundToInt().coerceAtLeast(0),
                dist = distanceKm.roundToInt(),
                alt = altitudeKm.roundToInt(),
                aos = aosSec
            )
        )
        n76Link.send(N76Protocol.freqModePacket(rx, tx, rxSub, txSub, n76.satFirmware))
        n76SatActive = true
    }

    private fun isValidN76Freq(hz: Long) = hz == 0L || hz in 136_000_000L..520_000_000L
}
