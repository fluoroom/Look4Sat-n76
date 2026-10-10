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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.rtbishop.look4sat.core.domain.aprs.Afsk1200
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The audio "TNC": plays a frame as Bell 202 tones on the phone's current audio output, which is
 * the radio once a sound interface such as a Digirig is plugged in. It only makes sound: keying
 * the transmitter is left to the radio's VOX, since a serial PTT line would need a USB driver.
 */
class AprsAudioPlayer(private val context: Context) {

    /**
     * Plays [ax25] and returns once the last tone is out. [outputId] picks the device; 0, or a
     * device that has since been unplugged, leaves the routing to the system.
     */
    suspend fun play(ax25: ByteArray, outputId: Int = 0) = withContext(Dispatchers.IO) {
        val samples = Afsk1200.modulate(ax25, SAMPLE_RATE)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            if (outputId != 0) {
                context.getSystemService(AudioManager::class.java)
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.id == outputId }
                    ?.let { track.preferredDevice = it }
            }
            track.write(samples, 0, samples.size)
            track.play()
            delay(samples.size * 1000L / SAMPLE_RATE + TAIL_MS)
        } finally {
            track.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val TAIL_MS = 100L
    }
}
