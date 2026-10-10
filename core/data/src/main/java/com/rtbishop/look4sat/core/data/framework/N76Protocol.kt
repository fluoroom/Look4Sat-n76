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

import com.rtbishop.look4sat.core.domain.model.N76TxPower
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.math.roundToInt

object N76Protocol {
    private const val VENDOR = 0x0002
    const val CMD_SET_POSITION = 0x0020
    const val CMD_READ_BSS_SETTINGS = 0x0021
    const val CMD_WRITE_BSS_SETTINGS = 0x0022
    const val CMD_FREQ_MODE_SET_PAR = 0x0023
    const val CMD_SET_SATELLITE_INFO = 0x004D
    const val CMD_DO_PROG_FUNC = 0x0042

    /** txPowerLevel value that leaves the radio's own power setting alone. */
    const val TX_POWER_NO_CHANGE = 0

    /**
     * txPowerLevel for [power]. bt-docs only defines 0 (no change) for this 2-bit field; the
     * ascending order here is a reading of it that has yet to be confirmed on the radio. If the
     * radio's power indicator disagrees, this mapping is the one place to fix.
     */
    fun txPowerLevel(power: N76TxPower): Int = when (power) {
        N76TxPower.Low -> 1
        N76TxPower.Medium -> 2
        N76TxPower.High -> 3
    }

    fun buildPacket(cmd: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0xFF.toByte()
        out[1] = 0x01
        out[2] = 0x00
        out[3] = payload.size.toByte()
        out[4] = (VENDOR shr 8).toByte()
        out[5] = VENDOR.toByte()
        out[6] = (cmd shr 8).toByte()
        out[7] = cmd.toByte()
        payload.copyInto(out, 8)
        return out
    }

    /**
     * FREQ_MODE_SET_PAR payload (cmd 0x0023).
     * satFirmware=true → fw≥137, 16-byte payload, mode=SATELLITE (10)
     * satFirmware=false → fw<137, 14-byte payload, mode=EXACT (3) + enableFreqMode=true
     */
    fun buildFreqModeParam(
        rxFreqHz: Long,
        txFreqHz: Long,
        rxSubtone: Int = 0,
        txSubtone: Int = 0,
        satFirmware: Boolean = true,
        txPowerLevel: Int = TX_POWER_NO_CHANGE
    ): ByteArray {
        val w = BigEndianBitWriter(if (satFirmware) 16 else 14)
        w.write(0, 2, 0)
        w.write(rxFreqHz.toInt(), 30, 2)
        w.write(0, 2, 32)
        w.write(txFreqHz.toInt(), 30, 34)
        w.write(rxSubtone, 16, 64)
        w.write(txSubtone, 16, 80)
        if (satFirmware) {
            w.writeBool(false, 96)
            w.write(0, 3, 97)
            w.write(10, 4, 100)
        } else {
            w.writeBool(true, 96)
            w.write(0, 3, 97)
            w.write(3, 4, 100)
        }
        w.write(0, 6, 104)
        w.write(txPowerLevel, 2, 110)
        return w.bytes
    }

    fun buildSatelliteInfo(
        name: String,
        azimuthDeg: Int = 0,
        elevationDeg: Int = 0,
        distanceKm: Int = 0,
        altitudeKm: Int = 400,
        aosSeconds: Int = 65535
    ): ByteArray {
        val w = BigEndianBitWriter(30)
        val nameBytes = try {
            name.toByteArray(Charset.forName("GBK"))
        } catch (_: Exception) {
            name.toByteArray(Charsets.UTF_8)
        }
        nameBytes.copyInto(w.bytes, 0, 0, minOf(nameBytes.size, 20))
        w.write(azimuthDeg.coerceIn(0, 511), 9, 160)
        w.write(0, 7, 169)
        w.write(elevationDeg.coerceIn(0, 255), 8, 176)
        w.write(0, 8, 184)
        w.write(distanceKm.coerceIn(0, 65535), 16, 192)
        w.write(altitudeKm.coerceIn(0, 65535), 16, 208)
        w.write(aosSeconds.coerceIn(0, 65535), 16, 224)
        return w.bytes
    }

    fun freqModePacket(
        rxHz: Long,
        txHz: Long,
        rxSub: Int,
        txSub: Int,
        satFw: Boolean,
        txPowerLevel: Int = TX_POWER_NO_CHANGE
    ) = buildPacket(CMD_FREQ_MODE_SET_PAR, buildFreqModeParam(rxHz, txHz, rxSub, txSub, satFw, txPowerLevel))

    fun satInfoPacket(name: String, az: Int, el: Int, dist: Int, alt: Int, aos: Int) =
        buildPacket(CMD_SET_SATELLITE_INFO, buildSatelliteInfo(name, az, el, dist, alt, aos))

    fun exitSatModePacket() = buildPacket(CMD_FREQ_MODE_SET_PAR, ByteArray(14))

    fun pttAssert() = buildPacket(CMD_DO_PROG_FUNC, byteArrayOf(0x00, 0x0D))
    fun pttRelease() = buildPacket(CMD_DO_PROG_FUNC, byteArrayOf(0x00, 0x1A))

    /** Action 15, toggle_monitor: flips the radio's monitor, which forces the squelch open. */
    fun toggleMonitor() = buildPacket(CMD_DO_PROG_FUNC, byteArrayOf(0x00, 0x0F))

    /** Action 19, sendLocation: the radio transmits its own APRS position beacon. */
    fun sendLocation() = buildPacket(CMD_DO_PROG_FUNC, byteArrayOf(0x00, 0x13))

    /**
     * SET_POSITION payload (cmd 0x0020), all fields big-endian.
     * Latitude and longitude are signed 24-bit, in degrees × 60 × 500. The long form (fw ≥ 133)
     * adds altitude (m), speed and bearing (both sent as -1, unknown), the fix time in Unix
     * seconds and accuracy (0, unknown): 18 bytes against the short form's 6.
     */
    fun buildPosition(
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double,
        epochSeconds: Long,
        longFormat: Boolean = true
    ): ByteArray {
        val buf = ByteBuffer.allocate(if (longFormat) 18 else 6)
        for (degrees in doubleArrayOf(latitudeDeg, longitudeDeg)) {
            val scaled = (degrees * 60 * 500).roundToInt()
            buf.put((scaled shr 16).toByte()).put((scaled shr 8).toByte()).put(scaled.toByte())
        }
        if (longFormat) {
            buf.putShort(altitudeM.roundToInt().coerceIn(-32767, 32767).toShort())
            buf.putShort(-1)
            buf.putShort(-1)
            buf.putInt(epochSeconds.toInt())
            buf.putShort(0)
        }
        return buf.array()
    }

    fun positionPacket(latDeg: Double, lonDeg: Double, altM: Double, epochSeconds: Long, longFormat: Boolean) =
        buildPacket(CMD_SET_POSITION, buildPosition(latDeg, lonDeg, altM, epochSeconds, longFormat))
}

class BigEndianBitWriter(val size: Int) {
    val bytes = ByteArray(size)

    fun write(value: Int, numBits: Int, startPos: Int) {
        for (i in 0 until numBits) {
            val bit = (value ushr (numBits - 1 - i)) and 1
            val bitPos = startPos + i
            val byteIdx = bitPos ushr 3
            val bitShift = 7 - (bitPos and 7)
            if (bit == 1) bytes[byteIdx] = (bytes[byteIdx].toInt() or (1 shl bitShift)).toByte()
        }
    }

    fun writeBool(v: Boolean, pos: Int) = write(if (v) 1 else 0, 1, pos)
}
