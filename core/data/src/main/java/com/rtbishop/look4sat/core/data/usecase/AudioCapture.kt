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
package com.rtbishop.look4sat.core.data.usecase

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.rtbishop.look4sat.core.data.framework.N76AudioBus
import com.rtbishop.look4sat.core.domain.model.AudioSource
import com.rtbishop.look4sat.core.domain.usecase.IAudioCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

class AudioCapture(private val context: Context) : IAudioCapture {

    override val sampleRate: Int = 44100

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_FLOAT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(sampleRate)

    override fun prepareInternalCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.startForegroundService(Intent(context, MediaProjectionFgService::class.java))
        }
    }

    override fun cancelInternalCapture() {
        context.stopService(Intent(context, MediaProjectionFgService::class.java))
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun audioFlow(source: AudioSource, captureToken: Any?): Flow<FloatArray> = flow {
        if (source == AudioSource.N76Ht) {
            N76AudioBus.pcm.collect { emit(it) }
            return@flow
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        // Internal capture is stereo (the system mixed output is always 2 channels);
        // other sources record mono directly.
        val internalStereo = source == AudioSource.Internal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val recorder: AudioRecord = when (source) {
            AudioSource.Mic, AudioSource.Unprocessed, AudioSource.BluetoothSco -> {
                val androidSource = when (source) {
                    AudioSource.Mic -> MediaRecorder.AudioSource.MIC
                    AudioSource.Unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
                    AudioSource.BluetoothSco -> {
                        @Suppress("DEPRECATION")
                        audioManager.startBluetoothSco()
                        delay(1500L)
                        MediaRecorder.AudioSource.MIC
                    }
                    // AudioSource.Internal / N76Ht are handled by the outer when / early return.
                    AudioSource.Internal, AudioSource.N76Ht -> error("unreachable")
                }
                AudioRecord(androidSource, sampleRate, channelConfig, audioFormat, bufferSize * 4)
            }
            AudioSource.Internal -> buildInternalAudioRecord(captureToken)
            AudioSource.N76Ht -> error("unreachable")
        }
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize for source: ${source.label}"
        }
        try {
            recorder.startRecording()
            val chunkFrames = sampleRate / 10
            // Stereo buffer is 2 floats per frame; mono buffer is 1 float per frame.
            val captureBuffer = FloatArray(if (internalStereo) chunkFrames * 2 else chunkFrames)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(captureBuffer, 0, captureBuffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val out = if (internalStereo) {
                        // Mix stereo to mono: average L + R per frame.
                        val frames = read / 2
                        FloatArray(frames) { i -> (captureBuffer[i * 2] + captureBuffer[i * 2 + 1]) * 0.5f }
                    } else {
                        if (read == chunkFrames) captureBuffer.copyOf() else captureBuffer.copyOfRange(0, read)
                    }
                    emit(out)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            when (source) {
                AudioSource.BluetoothSco -> {
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                }
                AudioSource.Internal -> {
                    (captureToken as? MediaProjection)?.stop()
                    context.stopService(Intent(context, MediaProjectionFgService::class.java))
                }
                else -> {}
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildInternalAudioRecord(captureToken: Any?): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Internal audio capture requires Android 10 (API 29) or newer.")
        }
        val mp = captureToken as? MediaProjection
            ?: error("Internal audio capture requires screen recording permission.")
        return buildInternalAudioRecordQ(mp)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildInternalAudioRecordQ(mp: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        // Stereo is required: Android's mixed playback output is always 2 channels.
        // The recording loop mixes back down to mono for the SSTV decoder.
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 8)
            .build()
    }
}
