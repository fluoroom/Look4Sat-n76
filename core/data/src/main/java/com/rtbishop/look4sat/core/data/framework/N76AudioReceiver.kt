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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.dw.sbc.SbcDecoder
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Second RFCOMM channel: HDLC-framed SBC from the N76, decoded to 32 kHz mono PCM.
 * Always publishes to [N76AudioBus] for SSTV/digimodes. Speaker output is optional.
 */
class N76AudioReceiver(private val mac: String, private val adapter: BluetoothAdapter?) {

    var onLog: ((String) -> Unit)? = null
    var onPcmShorts: ((ShortArray, Int) -> Unit)? = null
    var onActiveChanged: ((Boolean) -> Unit)? = null

    @Volatile private var running = false
    private var socket: BluetoothSocket? = null
    @Volatile private var currentTrack: AudioTrack? = null

    @Volatile var monitorEnabled: Boolean = false
        set(value) {
            field = value
            currentTrack?.setVolume(if (value) 1f else 0f)
        }

    val isRunning get() = running

    fun start() {
        if (running) return
        running = true
        thread(name = "n76-audio", isDaemon = true) { runLoop() }
    }

    fun stop() {
        running = false
        closeSocket()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun runLoop() {
        val adapter = adapter ?: run {
            log("audio: no BT adapter")
            return
        }
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (_: Exception) {
            log("audio: bad MAC $mac")
            return
        }

        while (running) {
            try {
                val sock = device.createRfcommSocketToServiceRecord(AUDIO_UUID)
                socket = sock
                adapter.cancelDiscovery()
                sock.connect()
                log("audio: HT audio connected OK")
                onActiveChanged?.invoke(true)
                val track = buildAudioTrack()
                currentTrack = track
                track.play()
                track.setVolume(if (monitorEnabled) 1f else 0f)
                try {
                    receiveLoop(sock.inputStream, track)
                } finally {
                    currentTrack = null
                    track.stop()
                    track.release()
                    onActiveChanged?.invoke(false)
                }
            } catch (e: UnsatisfiedLinkError) {
                log("audio: libsbc missing on this ABI — HT decode disabled")
                running = false
            } catch (e: Exception) {
                if (running) {
                    log("audio: HT disconnected — retrying in 3s… (${e.message})")
                    Thread.sleep(3000)
                }
            } finally {
                closeSocket()
            }
        }
        log("audio: HT audio stopped")
    }

    private fun buildAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    it.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                }
            }
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun receiveLoop(stream: InputStream, track: AudioTrack) {
        val decoder = SbcDecoder(true)
        val readBuf = ByteArray(4096)
        var accum = ByteArray(0)
        val pcmBytes = ByteArray(2048)
        val pcmShorts = ShortArray(1024)
        var totalBytesRead = 0
        var framesReceived = 0
        var audioFrames = 0
        var pcmFrames = 0
        var nextDiagAt = 500

        try {
            while (running) {
                val n = try { stream.read(readBuf) } catch (_: IOException) { -1 }
                if (n <= 0) break
                totalBytesRead += n
                accum += readBuf.copyOf(n)

                var pos = 0
                while (true) {
                    val result = parseFrame(accum, pos) ?: break
                    pos = result.nextPos
                    framesReceived++
                    if (framesReceived <= 8) {
                        val preview = result.data.take(4).joinToString(" ") { "%02X".format(it) }
                        log("audio: frame#$framesReceived type=${result.type} len=${result.data.size} data=[$preview]")
                    }
                    if ((result.type == TYPE_SBC_AUDIO || result.type == 0) && result.data.isNotEmpty()) {
                        audioFrames++
                        val pcmLen = decoder.b(result.data, 0, result.data.size, pcmBytes)
                        if (pcmLen > 0) {
                            pcmFrames++
                            val samples = pcmLen / 2
                            for (i in 0 until samples) {
                                val hi = pcmBytes[i * 2].toInt() and 0xFF
                                val lo = pcmBytes[i * 2 + 1].toInt() and 0xFF
                                pcmShorts[i] = ((hi shl 8) or lo).toShort()
                            }
                            track.write(pcmShorts, 0, samples)
                            N76AudioBus.publishHtShorts(pcmShorts, samples)
                            onPcmShorts?.invoke(pcmShorts, samples)
                        }
                    }
                }
                accum = if (pos < accum.size) accum.copyOfRange(pos, accum.size) else ByteArray(0)

                if (totalBytesRead >= nextDiagAt) {
                    log("audio: rx=${totalBytesRead}B frames=$framesReceived audio=$audioFrames pcm=$pcmFrames")
                    nextDiagAt = totalBytesRead + 5000
                }
            }
        } finally {
            log("audio: stream ended — rx=${totalBytesRead}B frames=$framesReceived audio=$audioFrames pcm=$pcmFrames")
            decoder.e()
        }
    }

    private data class FrameResult(val type: Int, val data: ByteArray, val nextPos: Int)

    private fun parseFrame(buf: ByteArray, startPos: Int): FrameResult? {
        var i = startPos
        while (i < buf.size && buf[i] != 0x7E.toByte()) i++
        if (i >= buf.size) return null
        i++

        val payload = ByteArray(buf.size)
        var pLen = 0
        var escaped = false
        var found = false
        var endPos = i

        while (endPos < buf.size) {
            val b = buf[endPos++]
            when {
                b == 0x7E.toByte() -> { found = true; break }
                escaped -> {
                    payload[pLen++] = (b.toInt() xor 0x20).toByte()
                    escaped = false
                }
                b == 0x7D.toByte() -> escaped = true
                else -> payload[pLen++] = b
            }
        }

        if (!found || pLen == 0) return null

        val first = payload[0].toInt() and 0xFF
        val (type, dataOffset, dataLen) = when {
            first == 0xFF && pLen >= 6 -> {
                val t = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                Triple(t, 4, pLen - 6)
            }
            (first and 0x80) != 0 && pLen >= 2 -> {
                val t = ((first and 0x3F) shl 8) or (payload[1].toInt() and 0xFF)
                val overhead = if ((first and 0xC0) == 0xC0) 4 else 2
                Triple(t, overhead, pLen - overhead)
            }
            else -> Triple(first and 0x7F, 1, pLen - 1)
        }

        if (dataLen < 0) return null
        val data = payload.copyOfRange(dataOffset, dataOffset + dataLen)
        return FrameResult(type, data, endPos)
    }

    private fun log(msg: String) { onLog?.invoke(msg) }

    companion object {
        private val AUDIO_UUID = UUID.fromString("39144315-32fa-40db-85ed-fbfeba2d86e6")
        private const val SAMPLE_RATE = 32000
        private const val TYPE_SBC_AUDIO = 9
    }
}
