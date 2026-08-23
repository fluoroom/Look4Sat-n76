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
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.rtbishop.look4sat.core.domain.model.N76Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Owns the N76 GAIA command channel, optional HT audio RFCOMM, recording, and playback.
 */
class N76Link(private val context: Context) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val bt = N76Bluetooth(adapter)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audio: N76AudioReceiver? = null
    private var recorder: N76AudioRecorder? = null
    private var player: MediaPlayer? = null
    private var originalBss: ByteArray? = null
    private var bssWritten = false
    private var mac = ""
    private var audioRfcomm = false
    private var monitorOn = false

    var onLog: ((String) -> Unit)? = null
    var onAudioActive: ((Boolean) -> Unit)? = null
    var onRecordingChanged: ((Boolean) -> Unit)? = null
    var onPlayingChanged: ((Boolean) -> Unit)? = null
    var onSaved: ((String) -> Unit)? = null

    val isConnected get() = bt.isConnected
    val isRecording get() = recorder?.isRunning == true
    val isPlaying get() = player != null

    init {
        bt.onLog = { onLog?.invoke("bt: $it") }
        bt.onPacket = { handleGaiaPacket(it) }
    }

    suspend fun connect(mac: String, settings: N76Settings): Boolean {
        this.mac = mac
        this.audioRfcomm = settings.audioRfcomm
        this.monitorOn = settings.speakerMonitor
        val done = CompletableDeferred<Boolean>()
        bt.onConnected = {
            onBtConnected()
            if (!done.isCompleted) done.complete(true)
        }
        bt.connect(mac)
        val ok = withTimeoutOrNull(12_000) { done.await() } ?: bt.isConnected
        if (!ok) onLog?.invoke("bt: connect timed out")
        return ok
    }

    fun disconnect() {
        stopPlayback()
        teardownAudio()
        bt.disconnect()
    }

    fun send(bytes: ByteArray): Boolean = bt.send(bytes)

    fun setPtt(on: Boolean) {
        if (!bt.isConnected) {
            onLog?.invoke("ptt: not connected")
            return
        }
        bt.send(if (on) N76Protocol.pttAssert() else N76Protocol.pttRelease())
        onLog?.invoke(if (on) "ptt: ON" else "ptt: OFF")
    }

    fun setMonitor(on: Boolean) {
        monitorOn = on
        audio?.monitorEnabled = on
        onLog?.invoke("audio: speaker monitor ${if (on) "ON" else "OFF"}")
    }

    fun exitSatMode() {
        if (bt.isConnected) {
            bt.send(N76Protocol.exitSatModePacket())
            onLog?.invoke("sat: mode OFF")
        }
    }

    fun startRecording(settings: N76Settings, satName: String) {
        if (!settings.recordHt && !settings.recordMic) {
            onLog?.invoke("rec: nothing selected")
            return
        }
        if (settings.recordHt && audio == null) {
            onLog?.invoke("rec: WARNING — HT audio channel not active (enable Receive RX audio first)")
        }
        val saf = settings.outputFolderUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        stopRecording()
        val rec = N76AudioRecorder(context).also { bindRecorder(it) }
        recorder = rec
        audio?.onPcmShorts = { shorts, n -> rec.feedHtShorts(shorts, n) }
        rec.start(
            recordHt = settings.recordHt,
            recordInput = settings.recordMic,
            inputDeviceId = settings.inputDeviceId,
            satName = satName,
            safDirUri = saf
        )
        onLog?.invoke(
            when {
                settings.recordHt && settings.recordMic -> "rec: stereo — L phone mic, R HT"
                settings.recordHt -> "rec: HT audio track"
                else -> "rec: phone mic track"
            }
        )
        onRecordingChanged?.invoke(true)
    }

    fun stopRecording() {
        val was = isRecording
        recorder?.stop(); recorder = null
        audio?.onPcmShorts = { shorts, n -> recorder?.feedHtShorts(shorts, n) }
        if (was) {
            onLog?.invoke("rec: stopped")
            onRecordingChanged?.invoke(false)
        }
    }

    fun playLast(path: String) {
        stopPlayback()
        try {
            val mp = MediaPlayer()
            if (path.startsWith("content:")) {
                mp.setDataSource(context, Uri.parse(path))
            } else {
                mp.setDataSource(path)
            }
            mp.setOnCompletionListener { stopPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                onLog?.invoke("play: error")
                stopPlayback()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            onPlayingChanged?.invoke(true)
            onLog?.invoke("play: ${File(path).name}")
        } catch (e: Exception) {
            onLog?.invoke("play: ${e.message}")
            stopPlayback()
        }
    }

    fun stopPlayback() {
        val mp = player ?: return
        player = null
        try { mp.stop() } catch (_: Exception) {}
        try { mp.release() } catch (_: Exception) {}
        onPlayingChanged?.invoke(false)
    }

    private fun bindRecorder(rec: N76AudioRecorder) {
        rec.onLog = { onLog?.invoke(it) }
        rec.onSaved = { onSaved?.invoke(it) }
    }

    private fun onBtConnected() {
        if (!audioRfcomm) return
        bt.send(N76Protocol.buildPacket(N76Protocol.CMD_READ_BSS_SETTINGS, byteArrayOf()))
        onLog?.invoke("audio: requesting BSS to enable audio_relay_en…")
        startHmLinkAudio()
        mainHandler.postDelayed({
            if (!bssWritten) onLog?.invoke("audio: BSS ACK not received within 5 s")
        }, 5000L)
    }

    private fun handleGaiaPacket(pkt: ByteArray) {
        if (pkt.size < 9) return
        val cmdWord = ((pkt[6].toInt() and 0xFF) shl 8) or (pkt[7].toInt() and 0xFF)
        val isAck = (cmdWord and 0x8000) != 0
        val baseCmd = cmdWord and 0x7FFF
        val plen = pkt[3].toInt() and 0xFF
        if (isAck && baseCmd == N76Protocol.CMD_READ_BSS_SETTINGS && !bssWritten) {
            if (plen < 2 || pkt.size < 9 + plen - 1) return
            val blob = pkt.copyOfRange(9, 8 + plen)
            mainHandler.removeCallbacksAndMessages(null)
            enableAudioRelay(blob)
        }
    }

    private fun enableAudioRelay(blob: ByteArray) {
        if (blob.isEmpty()) {
            onLog?.invoke("audio: BSS blob empty, cannot set audio_relay_en")
            return
        }
        val relayAlreadyOn = (blob[2].toInt() ushr 1) and 1
        if (relayAlreadyOn == 1) {
            onLog?.invoke("audio: audio_relay_en already set — no BSS write needed")
            bssWritten = true
            return
        }
        originalBss = blob.copyOf()
        val mod = blob.copyOf()
        mod[2] = (mod[2].toInt() or 0x02).toByte()
        bt.send(N76Protocol.buildPacket(N76Protocol.CMD_WRITE_BSS_SETTINGS, mod))
        bssWritten = true
        onLog?.invoke("audio: BSS written — audio_relay_en=1 (was 0)")
    }

    private fun startHmLinkAudio() {
        audio?.stop()
        audio = N76AudioReceiver(mac, adapter).apply {
            onLog = { this@N76Link.onLog?.invoke(it) }
            monitorEnabled = monitorOn
            onActiveChanged = { this@N76Link.onAudioActive?.invoke(it) }
            onPcmShorts = { shorts, n -> recorder?.feedHtShorts(shorts, n) }
            start()
        }
    }

    private fun teardownAudio() {
        mainHandler.removeCallbacksAndMessages(null)
        stopRecording()
        audio?.stop()
        audio = null
        val orig = originalBss
        if (orig != null && bt.isConnected) {
            onLog?.invoke("audio: restoring original BSS audio_relay_en")
            bt.send(N76Protocol.buildPacket(N76Protocol.CMD_WRITE_BSS_SETTINGS, orig))
        }
        originalBss = null
        bssWritten = false
        onAudioActive?.invoke(false)
    }
}
