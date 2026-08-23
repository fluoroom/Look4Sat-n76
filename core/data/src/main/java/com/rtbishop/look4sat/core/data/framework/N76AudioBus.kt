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

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * In-process PCM tap from the N76 HT RFCOMM decoder.
 * Decoded 32 kHz shorts are resampled to 44.1 kHz floats for SSTV / digimodes
 * without routing through the speaker or microphone.
 */
object N76AudioBus {
    const val SOURCE_RATE = 32_000
    const val TARGET_RATE = 44_100

    private val _pcm = MutableSharedFlow<FloatArray>(extraBufferCapacity = 16)
    val pcm: SharedFlow<FloatArray> = _pcm

    fun publishHtShorts(data: ShortArray, count: Int) {
        if (count <= 0) return
        _pcm.tryEmit(resampleTo44100(data, count))
    }

    internal fun resampleTo44100(input: ShortArray, count: Int): FloatArray {
        val outCount = (count.toLong() * TARGET_RATE / SOURCE_RATE).toInt().coerceAtLeast(1)
        val last = (count - 1).coerceAtLeast(0)
        val out = FloatArray(outCount)
        for (i in 0 until outCount) {
            val src = i * SOURCE_RATE.toDouble() / TARGET_RATE
            val i0 = src.toInt().coerceIn(0, last)
            val i1 = (i0 + 1).coerceAtMost(last)
            val frac = (src - i0).toFloat()
            val sample = input[i0] * (1f - frac) + input[i1] * frac
            out[i] = sample / 32768f
        }
        return out
    }
}
