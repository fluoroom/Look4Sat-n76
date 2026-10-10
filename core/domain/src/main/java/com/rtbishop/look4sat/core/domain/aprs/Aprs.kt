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
package com.rtbishop.look4sat.core.domain.aprs

import com.rtbishop.look4sat.core.domain.model.AprsSettings
import com.rtbishop.look4sat.core.domain.utility.qthToPosition
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** AX.25 UI frames, the only kind APRS uses. Frames are built without flags or FCS, as KISS wants them. */
object Ax25 {

    private const val CONTROL_UI = 0x03
    private const val PID_NO_LAYER3 = 0xF0
    private const val MAX_DIGIPEATERS = 8

    /** Splits "LU1AQY-7" into callsign and SSID; null unless it is 1-6 letters/digits and SSID 0-15. */
    fun parseAddress(text: String): Pair<String, Int>? {
        val parts = text.trim().uppercase(Locale.US).split('-')
        if (parts.size > 2) return null
        val call = parts[0]
        if (call.length !in 1..6 || !call.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        val ssid = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 0
        return if (ssid in 0..15) call to ssid else null
    }

    /** Parses "ARISS,SGATE,WIDE2-2"; null when an entry is not an address or there are too many. */
    fun parsePath(text: String): List<Pair<String, Int>>? {
        val entries = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.size > MAX_DIGIPEATERS) return null
        return entries.map { parseAddress(it) ?: return null }
    }

    fun uiFrame(
        source: Pair<String, Int>,
        destination: Pair<String, Int>,
        path: List<Pair<String, Int>>,
        info: ByteArray
    ): ByteArray {
        val addresses = listOf(destination, source) + path
        val frame = ByteArray(addresses.size * 7 + 2 + info.size)
        addresses.forEachIndexed { index, (call, ssid) ->
            val padded = call.padEnd(6)
            for (i in 0 until 6) frame[index * 7 + i] = (padded[i].code shl 1).toByte()
            // The destination carries the command bit; the last address closes the field.
            val high = if (index == 0) 0xE0 else 0x60
            val last = if (index == addresses.lastIndex) 1 else 0
            frame[index * 7 + 6] = (high or (ssid shl 1) or last).toByte()
        }
        frame[addresses.size * 7] = CONTROL_UI.toByte()
        frame[addresses.size * 7 + 1] = PID_NO_LAYER3.toByte()
        info.copyInto(frame, addresses.size * 7 + 2)
        return frame
    }

    /**
     * The frame in TNC2 monitor form, "SRC-7>DEST,DIGI*:info", the usual way to show a raw
     * packet. A star marks digipeaters that already repeated it. Null when it is not a UI frame.
     */
    fun toTnc2(frame: ByteArray): String? {
        val addresses = ArrayList<String>()
        var offset = 0
        while (true) {
            if (offset + 7 > frame.size || addresses.size > MAX_DIGIPEATERS + 2) return null
            val call = String(CharArray(6) { ((frame[offset + it].toInt() and 0xFF) ushr 1).toChar() }).trim()
            val last = frame[offset + 6].toInt() and 0xFF
            val ssid = (last ushr 1) and 0x0F
            val repeated = addresses.size >= 2 && last and 0x80 != 0
            addresses += call + (if (ssid != 0) "-$ssid" else "") + (if (repeated) "*" else "")
            offset += 7
            if (last and 1 != 0) break
        }
        if (addresses.size < 2 || offset + 2 > frame.size) return null
        val info = String(CharArray(frame.size - offset - 2) { index ->
            val code = frame[offset + 2 + index].toInt() and 0xFF
            if (code in 0x20..0x7E) code.toChar() else '.'
        })
        val route = (listOf(addresses[0]) + addresses.drop(2)).joinToString(",")
        return "${addresses[1]}>$route:$info"
    }

    /** CRC-16/X.25, sent low byte first after the frame. */
    fun fcs(frame: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in frame) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc.inv() and 0xFFFF
    }
}

object Kiss {
    private const val FEND = 0xC0
    private const val FESC = 0xDB
    private const val TFEND = 0xDC
    private const val TFESC = 0xDD

    /** Wraps an AX.25 frame as a KISS data frame on port 0. */
    fun dataFrame(ax25: ByteArray): ByteArray {
        val out = ArrayList<Byte>(ax25.size + 4)
        out += FEND.toByte()
        out += 0x00
        for (byte in ax25) {
            when (byte.toInt() and 0xFF) {
                FEND -> { out += FESC.toByte(); out += TFEND.toByte() }
                FESC -> { out += FESC.toByte(); out += TFESC.toByte() }
                else -> out += byte
            }
        }
        out += FEND.toByte()
        return out.toByteArray()
    }
}

/** Reassembles KISS data frames from a byte stream that may split or join them anywhere. */
class KissDecoder {
    private val buffer = ArrayList<Byte>()
    private var escaped = false

    /** Feeds received bytes; returns the AX.25 frames completed by them. */
    fun feed(bytes: ByteArray, count: Int = bytes.size): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xFF
            when {
                value == 0xC0 -> {
                    // First byte is the port/command; only command 0 carries a frame.
                    if (buffer.size > 1 && buffer[0].toInt() and 0x0F == 0) frames += buffer.drop(1).toByteArray()
                    buffer.clear()
                    escaped = false
                }
                escaped -> {
                    buffer += (if (value == 0xDC) 0xC0 else if (value == 0xDD) 0xDB else value).toByte()
                    escaped = false
                }
                value == 0xDB -> escaped = true
                else -> buffer += value.toByte()
            }
        }
        return frames
    }
}

/**
 * Bell 202 receiver for audio from a radio's speaker. Each sample updates a one-bit-long
 * correlation against both tones; a clock nudged by every tone change picks the middle of each
 * bit; flags, bit stuffing and the checksum then decide what is a frame.
 *
 * Ceiling: it compares raw tone energy, with no compensation for a receiver that favours one
 * tone, so weak or heavily de-emphasised signals are missed. Upgrade path is a band-pass per
 * tone with a gain balance, as full soft modems do.
 */
class Afsk1200Demodulator(private val sampleRate: Int, private val onFrame: (ByteArray) -> Unit) {
    private val samplesPerBit = sampleRate / 1200.0
    private val window = samplesPerBit.roundToInt()
    private val history = Array(4) { DoubleArray(window) }
    private val sums = DoubleArray(4)
    private var position = 0
    private var sampleIndex = 0L
    private var clock = 0.0
    private var lastSign = false
    private var lastTone = false
    private var ones = 0
    private val bits = ArrayList<Boolean>()

    fun feed(samples: FloatArray) {
        for (sample in samples) {
            val time = sampleIndex++.toDouble() / sampleRate
            val products = doubleArrayOf(
                sample * cos(2 * PI * 1200.0 * time), sample * sin(2 * PI * 1200.0 * time),
                sample * cos(2 * PI * 2200.0 * time), sample * sin(2 * PI * 2200.0 * time)
            )
            for (i in 0 until 4) {
                sums[i] += products[i] - history[i][position]
                history[i][position] = products[i]
            }
            position = (position + 1) % window
            val isMark = sums[0] * sums[0] + sums[1] * sums[1] > sums[2] * sums[2] + sums[3] * sums[3]
            // A tone change marks a bit edge: pull the clock toward sampling half a bit later.
            if (isMark != lastSign) {
                lastSign = isMark
                clock += (samplesPerBit / 2 - clock) * 0.5
            }
            clock += 1.0
            if (clock >= samplesPerBit) {
                clock -= samplesPerBit
                onBit(isMark == lastTone)
                lastTone = isMark
            }
        }
    }

    private fun onBit(bit: Boolean) {
        if (bit) {
            ones++
            // Seven ones in a row is no data and no flag: noise, or the end of a transmission.
            if (ones > 6) bits.clear() else bits += true
            return
        }
        when (ones) {
            6 -> {
                // A flag. The zero and six ones before this bit were collected as data; drop them.
                repeat(7) { if (bits.isNotEmpty()) bits.removeAt(bits.lastIndex) }
                finishFrame()
            }
            5 -> Unit // a stuffed zero, not data
            else -> bits += false
        }
        ones = 0
    }

    private fun finishFrame() {
        val size = bits.size
        if (size % 8 == 0 && size >= MIN_FRAME_BITS) {
            val bytes = ByteArray(size / 8) { index ->
                (0 until 8).sumOf { if (bits[index * 8 + it]) 1 shl it else 0 }.toByte()
            }
            val frame = bytes.copyOf(bytes.size - 2)
            val fcs = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
            if (Ax25.fcs(frame) == fcs) onFrame(frame)
        }
        bits.clear()
    }

    private companion object {
        /** Two addresses, control, PID and the checksum. */
        const val MIN_FRAME_BITS = (14 + 2 + 2) * 8
    }
}

/** Bell 202 modulator: 1200 baud, mark 1200 Hz, space 2200 Hz, NRZI with bit stuffing. Transmit only. */
object Afsk1200 {
    private const val BAUD = 1200
    private const val MARK_HZ = 1200.0
    private const val SPACE_HZ = 2200.0
    private const val FLAG = 0x7E
    private const val AMPLITUDE = 0.6 * Short.MAX_VALUE

    /**
     * Audio for one frame. [preambleMs] of flags come first: it is what gives a VOX or a slow PTT
     * the time to key the transmitter before the data starts.
     */
    fun modulate(ax25: ByteArray, sampleRate: Int, preambleMs: Int = 500): ShortArray {
        val bits = ArrayList<Boolean>()
        fun addByte(value: Int, stuff: Boolean, ones: IntArray) {
            for (i in 0 until 8) {
                val bit = (value ushr i) and 1 == 1
                bits += bit
                if (!stuff) continue
                if (bit) ones[0]++ else ones[0] = 0
                if (ones[0] == 5) { bits += false; ones[0] = 0 }
            }
        }
        val ones = IntArray(1)
        repeat(preambleMs * BAUD / 8000 + 1) { addByte(FLAG, false, ones) }
        val fcs = Ax25.fcs(ax25)
        for (byte in ax25) addByte(byte.toInt() and 0xFF, true, ones)
        addByte(fcs and 0xFF, true, ones)
        addByte(fcs ushr 8, true, ones)
        repeat(3) { addByte(FLAG, false, ones) }

        val samples = ShortArray((bits.size.toLong() * sampleRate / BAUD).toInt())
        var phase = 0.0
        var mark = true
        var index = 0
        bits.forEachIndexed { bitIndex, bit ->
            // NRZI: a zero flips the tone, a one holds it. Phase runs on, so there are no clicks.
            if (!bit) mark = !mark
            val step = 2 * PI * (if (mark) MARK_HZ else SPACE_HZ) / sampleRate
            val end = ((bitIndex + 1).toLong() * sampleRate / BAUD).toInt()
            while (index < end) {
                samples[index++] = (sin(phase) * AMPLITUDE).roundToInt().toShort()
                phase += step
            }
        }
        return samples
    }
}

object Aprs {
    /** Experimental-software destination (APZ...), which is what an unregistered tocall must use. */
    private val TOCALL = "APZL4S" to 0

    /** "-34.6037, -58.3816" or a Maidenhead locator; null when it is neither. */
    fun parseLocation(text: String): Pair<Double, Double>? {
        val parts = text.trim().split(',', ' ', ';').filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null) return if (abs(lat) <= 90 && abs(lon) <= 180) lat to lon else null
        }
        return qthToPosition(text.trim())?.let { it.latitude to it.longitude }
    }

    fun formatLocation(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    /**
     * Uncompressed position without timestamp: "!DDMM.hhN" table "DDDMM.hhW" code, then the
     * comment. [symbol] is the table character followed by the code.
     */
    fun positionInfo(
        latitude: Double,
        longitude: Double,
        comment: String,
        symbol: String = AprsSettings.DEFAULT_SYMBOL
    ): String {
        val (table, code) = (symbol.takeIf { it.length == 2 } ?: AprsSettings.DEFAULT_SYMBOL).let { it[0] to it[1] }
        // Whole hundredths of a minute first, so 59.996' rolls into the next degree, not "60.00".
        fun split(degrees: Double) = (abs(degrees) * 6000).roundToLong().let { it / 6000 to (it % 6000) / 100.0 }
        val (latDeg, latMin) = split(latitude)
        val (lonDeg, lonMin) = split(longitude)
        val ns = if (latitude < 0) 'S' else 'N'
        val ew = if (longitude < 0) 'W' else 'E'
        return String.format(
            Locale.US, "!%02d%05.2f%c%c%03d%05.2f%c%c%s", latDeg, latMin, ns, table, lonDeg, lonMin, ew, code, comment
        )
    }

    /**
     * The frame for one beacon: a position report when a location is given, a status report
     * (">text") when it is left blank.
     * @throws IllegalArgumentException naming the field that cannot be sent as written.
     */
    fun beaconFrame(settings: AprsSettings): ByteArray {
        val source = requireNotNull(Ax25.parseAddress(settings.callsign)) { "Callsign must look like LU1ABC-7" }
        val path = requireNotNull(Ax25.parsePath(settings.path)) { "Path must look like ARISS,SGATE,WIDE2-2" }
        val info = if (settings.location.isBlank()) {
            require(settings.message.isNotBlank()) { "Nothing to send: set a location or a message" }
            ">" + settings.message
        } else {
            val (lat, lon) = requireNotNull(parseLocation(settings.location)) { "Location must be lat, lon or a locator" }
            positionInfo(lat, lon, settings.message, settings.symbol)
        }
        return Ax25.uiFrame(source, TOCALL, path, info.toByteArray(Charsets.US_ASCII))
    }
}
