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

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Records HT audio (tapped from the SBC decoder) and/or phone mic to OGG Opus or WAV.
 */
class N76AudioRecorder(private val context: Context) {

    var onLog: ((String) -> Unit)? = null
    var onSaved: ((String) -> Unit)? = null

    @Volatile private var running = false
    @Volatile private var audioRec: AudioRecord? = null
    private var outputFile: File? = null
    private var recordHt = false
    private var recordInput = false
    private var pendingSafUri: Uri? = null

    // HT decoded PCM blocks queued from HmLinkAudioReceiver's receive thread
    private val htQueue = LinkedBlockingQueue<ShortArray>(512)

    companion object {
        const val SAMPLE_RATE = 32_000
        private const val BLOCK = 640          // 20 ms at 32 kHz — standard Opus frame
        private const val BIT_RATE = 64_000
        private const val OPUS_MIME = "audio/opus"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(recordHt: Boolean, recordInput: Boolean, inputDeviceId: Int,
              satName: String = "", outputDir: File? = null, safDirUri: Uri? = null,
              fileSuffix: String = "") {
        if (running) { log("rec: already running"); return }
        if (!recordHt && !recordInput) { log("rec: nothing selected"); return }
        this.recordHt = recordHt
        this.recordInput = recordInput
        this.pendingSafUri = safDirUri
        htQueue.clear()

        val channels = if (recordHt && recordInput) 2 else 1
        val opusEncoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) findOpusEncoder(channels) else null
        val ext = if (opusEncoder != null) "ogg" else "wav"
        val ts = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val satPart = if (satName.isNotEmpty()) "-${satName.replace(Regex("[^A-Za-z0-9._-]"), "_")}" else ""
        val sfx = if (fileSuffix.isNotEmpty()) "-$fileSuffix" else ""
        // SAF URIs require ContentResolver — write to cache first, then copy via SAF after encoding
        val dir = when {
            safDirUri != null -> context.cacheDir
            outputDir != null -> outputDir
            else -> context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        }
        dir.mkdirs()
        outputFile = File(dir, "$ts$satPart$sfx.$ext")

        if (recordInput) initAudioRecord(inputDeviceId)
        running = true

        if (opusEncoder != null) {
            thread(name = "n76-rec", isDaemon = true) { encodeOpus(opusEncoder, channels) }
        } else {
            log("rec: Opus unavailable — writing WAV")
            thread(name = "n76-rec", isDaemon = true) { encodeWav(channels) }
        }
        log("rec: writing ${outputFile!!.name}")
    }

    /** Stop recording. Returns the absolute path of the saved file, or null if never started. */
    fun stop(): String? {
        if (!running) return outputFile?.absolutePath
        running = false
        audioRec?.stop()
        audioRec?.release()
        audioRec = null
        return outputFile?.absolutePath
    }

    /** Feed a decoded PCM block (32 kHz, mono, little-endian) from [N76AudioReceiver]. */
    fun feedHtShorts(data: ShortArray, count: Int) {
        if (running && recordHt) htQueue.offer(data.copyOf(count))
    }

    val isRunning: Boolean get() = running

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun findOpusEncoder(channels: Int): String? {
        val fmt = MediaFormat.createAudioFormat(OPUS_MIME, SAMPLE_RATE, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(fmt)
    }

    private fun initAudioRecord(deviceId: Int) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, BLOCK * 4)
        )
        if (deviceId != 0) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == deviceId }
                ?.let { rec.preferredDevice = it }
        }
        rec.startRecording()
        audioRec = rec
    }

    // ── OGG Opus encoder ──────────────────────────────────────────────────────

    private fun encodeOpus(encoderName: String, channels: Int) {
        val file = outputFile ?: return
        val fmt = MediaFormat.createAudioFormat(OPUS_MIME, SAMPLE_RATE, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK * channels * 2 * 4)
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxTrack = -1
        var muxStarted = false
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L

        // Per-stream sample accumulator (growable to absorb bursts)
        var htBuf = ShortArray(SAMPLE_RATE * 2)
        var htLen = 0
        val micBuf  = ShortArray(BLOCK)
        val htBlock = ShortArray(BLOCK)
        val stereo  = ShortArray(BLOCK * 2)

        fun htEnqueue(blk: ShortArray) {
            if (htLen + blk.size > htBuf.size)
                htBuf = htBuf.copyOf(htBuf.size + blk.size + SAMPLE_RATE)
            blk.copyInto(htBuf, htLen); htLen += blk.size
        }

        fun htConsume(n: Int): Int {
            val take = minOf(n, htLen)
            htBuf.copyInto(htBlock, 0, 0, take)
            htBuf.copyInto(htBuf, 0, take, htLen)
            htLen -= take
            return take
        }

        fun drain(eos: Boolean) {
            val c = codec ?: return
            while (true) {
                val idx = c.dequeueOutputBuffer(info, if (eos) 100_000L else 0L)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxStarted) {
                            muxTrack = muxer!!.addTrack(c.outputFormat)
                            muxer!!.start()
                            muxStarted = true
                        }
                    }
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null && muxStarted && info.size > 0
                            && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                            muxer!!.writeSampleData(muxTrack, buf, info)
                        c.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
            }
        }

        fun submit(buf: ShortArray, n: Int) {
            val c = codec ?: return
            val idx = c.dequeueInputBuffer(10_000L)
            if (idx < 0) return
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            for (i in 0 until n) {
                val s = buf[i].toInt()
                ib.put((s and 0xFF).toByte())
                ib.put(((s shr 8) and 0xFF).toByte())
            }
            c.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
            // Advance PTS by the duration of mono samples contained in this buffer
            ptsUs += (n.toLong() / channels) * 1_000_000L / SAMPLE_RATE
            drain(false)
        }

        try {
            codec = MediaCodec.createByCodecName(encoderName)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)

            while (running) {
                when {
                    recordInput && recordHt -> {
                        // Stereo: AudioRecord is the clock; HT fills the right channel
                        val rec = audioRec ?: break
                        val micN = rec.read(micBuf, 0, BLOCK)
                        if (micN <= 0) { Thread.sleep(5); continue }

                        while (htLen < micN) {
                            htQueue.poll()?.let { htEnqueue(it) } ?: break
                        }
                        val htN = htConsume(micN)

                        for (i in 0 until micN) {
                            stereo[i * 2]     = micBuf[i]
                            stereo[i * 2 + 1] = if (i < htN) htBlock[i] else 0
                        }
                        submit(stereo, micN * 2)
                    }
                    recordHt -> {
                        // Accumulate until a full BLOCK is available
                        while (htLen < BLOCK && running) {
                            htQueue.poll(20, TimeUnit.MILLISECONDS)?.let { htEnqueue(it) }
                        }
                        if (!running) break
                        val n = htConsume(minOf(htLen, BLOCK))
                        submit(htBlock, n)
                    }
                    recordInput -> {
                        val rec = audioRec ?: break
                        val n = rec.read(micBuf, 0, BLOCK)
                        if (n > 0) submit(micBuf, n) else Thread.sleep(5)
                    }
                }
            }
        } catch (e: Exception) {
            log("rec: opus error: ${e.message}")
        } finally {
            try {
                val c = codec
                if (c != null) {
                    val idx = c.dequeueInputBuffer(10_000L)
                    if (idx >= 0)
                        c.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drain(true)
                }
            } catch (_: Exception) {}
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            // Release muxer regardless of whether it was started; stop() only if started
            try { if (muxStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            finalizeFile(file)
        }
    }

    // ── WAV PCM fallback ──────────────────────────────────────────────────────

    private fun encodeWav(channels: Int) {
        val file = outputFile ?: return
        var htBuf = ShortArray(SAMPLE_RATE * 2)
        var htLen = 0
        val micBuf = ShortArray(BLOCK)
        var sampleCount = 0L

        fun htEnqueue(blk: ShortArray) {
            if (htLen + blk.size > htBuf.size)
                htBuf = htBuf.copyOf(htBuf.size + blk.size + SAMPLE_RATE)
            blk.copyInto(htBuf, htLen); htLen += blk.size
        }

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(ByteArray(44))  // placeholder header

                while (running) {
                    when {
                        recordInput && recordHt -> {
                            val rec = audioRec ?: break
                            val micN = rec.read(micBuf, 0, BLOCK)
                            if (micN <= 0) { Thread.sleep(5); continue }
                            while (htLen < micN) {
                                htQueue.poll()?.let { htEnqueue(it) } ?: break
                            }
                            val htN = minOf(htLen, micN)
                            val out = ByteArray(micN * 4)
                            for (i in 0 until micN) {
                                val m = micBuf[i].toInt()
                                val h = if (i < htN) htBuf[i].toInt() else 0
                                out[i * 4]     = (m and 0xFF).toByte()
                                out[i * 4 + 1] = ((m ushr 8) and 0xFF).toByte()
                                out[i * 4 + 2] = (h and 0xFF).toByte()
                                out[i * 4 + 3] = ((h ushr 8) and 0xFF).toByte()
                            }
                            htBuf.copyInto(htBuf, 0, htN, htLen); htLen -= htN
                            raf.write(out); sampleCount += micN
                        }
                        recordHt -> {
                            while (htLen < BLOCK && running) {
                                htQueue.poll(20, TimeUnit.MILLISECONDS)?.let { htEnqueue(it) }
                            }
                            if (!running) break
                            val n = minOf(htLen, BLOCK)
                            val out = ByteArray(n * 2)
                            for (i in 0 until n) {
                                val s = htBuf[i].toInt()
                                out[i * 2]     = (s and 0xFF).toByte()
                                out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
                            }
                            htBuf.copyInto(htBuf, 0, n, htLen); htLen -= n
                            raf.write(out); sampleCount += n
                        }
                        recordInput -> {
                            val rec = audioRec ?: break
                            val n = rec.read(micBuf, 0, BLOCK)
                            if (n > 0) {
                                val out = ByteArray(n * 2)
                                for (i in 0 until n) {
                                    val s = micBuf[i].toInt()
                                    out[i * 2]     = (s and 0xFF).toByte()
                                    out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
                                }
                                raf.write(out); sampleCount += n
                            } else Thread.sleep(5)
                        }
                    }
                }
                val dataBytes = sampleCount * channels * 2
                raf.seek(0)
                raf.write(wavHeader(SAMPLE_RATE, channels, dataBytes))
            }
        } catch (e: Exception) {
            log("rec: wav error: ${e.message}")
        } finally {
            finalizeFile(file)
        }
    }

    private fun finalizeFile(file: File) {
        val uri = pendingSafUri
        if (uri == null) {
            log("rec: saved → ${file.name}")
            onSaved?.invoke(file.absolutePath)
            return
        }
        try {
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            val mime = if (file.name.endsWith(".ogg")) "audio/ogg" else "audio/wav"
            val destUri = DocumentsContract.createDocument(
                context.contentResolver, parentUri, mime, file.name
            ) ?: run {
                log("rec: SAF error — could not create document, kept at ${file.name}")
                onSaved?.invoke(file.absolutePath)
                return
            }
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            file.delete()
            log("rec: saved → ${file.name}")
            onSaved?.invoke(destUri.toString())
        } catch (e: Exception) {
            log("rec: SAF copy failed (${e.message}) — kept at ${file.name}")
            onSaved?.invoke(file.absolutePath)
        }
    }

    private fun wavHeader(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val dataInt = dataBytes.coerceAtMost(0xFFFFFFFFL).toInt()
        buf.put("RIFF".toByteArray())
        buf.putInt((dataBytes + 36).coerceAtMost(0xFFFFFFFFL).toInt())
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)                           // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2)     // byte rate
        buf.putShort((channels * 2).toShort())    // block align
        buf.putShort(16)                          // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataInt)
        return buf.array()
    }

    private fun log(msg: String) = onLog?.invoke(msg)
}
