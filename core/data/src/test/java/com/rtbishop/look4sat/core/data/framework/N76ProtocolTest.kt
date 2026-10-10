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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class N76ProtocolTest {

    @Test
    fun packetHeaderIsGaia() {
        val pkt = N76Protocol.exitSatModePacket()
        assertEquals(0xFF.toByte(), pkt[0])
        assertEquals(0x01.toByte(), pkt[1])
        assertEquals(0x00.toByte(), pkt[2])
        assertEquals(14.toByte(), pkt[3])
        assertEquals(0x00.toByte(), pkt[4])
        assertEquals(0x02.toByte(), pkt[5])
        assertEquals(0x00.toByte(), pkt[6])
        assertEquals(0x23.toByte(), pkt[7])
        assertEquals(22, pkt.size)
    }

    @Test
    fun satFirmwarePayloadIs16Bytes() {
        val payload = N76Protocol.buildFreqModeParam(145_900_000, 435_100_000, 6700, 0, satFirmware = true)
        assertEquals(16, payload.size)
        val pkt = N76Protocol.freqModePacket(145_900_000, 435_100_000, 6700, 0, true)
        assertEquals(24, pkt.size)
    }

    @Test
    fun legacyFirmwarePayloadIs14Bytes() {
        val payload = N76Protocol.buildFreqModeParam(145_900_000, 435_100_000, 0, 0, satFirmware = false)
        assertEquals(14, payload.size)
    }

    @Test
    fun satelliteInfoIs30BytesAndKeepsName() {
        val payload = N76Protocol.buildSatelliteInfo("SO-50", 180, 45, 800, 650, 12)
        assertEquals(30, payload.size)
        assertTrue(payload.decodeToString().startsWith("SO-50"))
    }

    @Test
    fun txPowerDefaultsToNoChangeAndOnlyTouchesItsTwoBits() {
        val untouched = N76Protocol.buildFreqModeParam(145_900_000, 435_100_000, 6700, 0)
        assertEquals(0, untouched[13].toInt() and 0x03)
        val high = N76Protocol.buildFreqModeParam(
            145_900_000, 435_100_000, 6700, 0, txPowerLevel = N76Protocol.txPowerLevel(N76TxPower.High)
        )
        // Bits 110-111 are the two low bits of byte 13; every other byte must be identical.
        assertEquals(0x03, high[13].toInt() and 0x03)
        high[13] = (high[13].toInt() and 0x03.inv()).toByte()
        assertArrayEquals(untouched, high)
    }

    @Test
    fun monitorToggleIsProgFuncFifteen() {
        val packet = N76Protocol.toggleMonitor()
        assertEquals(0x42.toByte(), packet[7])
        assertEquals(0x0F.toByte(), packet[9])
    }

    @Test
    fun beaconRequestIsProgFuncNineteen() {
        val packet = N76Protocol.sendLocation()
        assertEquals(0x42.toByte(), packet[7])
        assertEquals(0x13.toByte(), packet[9])
    }

    @Test
    fun positionIsSigned24BitArcminutesTimes500() {
        // -34.5° and -58.5° scale to -1_035_000 and -1_755_000.
        val long = N76Protocol.buildPosition(-34.5, -58.5, 25.0, 1_760_000_000L)
        assertEquals(18, long.size)
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0x35, 0x08), long.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(0xE5.toByte(), 0x38, 0x88.toByte()), long.copyOfRange(3, 6))
        assertArrayEquals(byteArrayOf(0x00, 0x19), long.copyOfRange(6, 8))
        // Speed and bearing unknown, then the fix time.
        assertArrayEquals(ByteArray(4) { 0xFF.toByte() }, long.copyOfRange(8, 12))
        assertArrayEquals(byteArrayOf(0x68, 0xE7.toByte(), 0x78, 0x00), long.copyOfRange(12, 16))

        val short = N76Protocol.buildPosition(-34.5, -58.5, 25.0, 1_760_000_000L, longFormat = false)
        assertArrayEquals(long.copyOfRange(0, 6), short)
        assertEquals(0x20.toByte(), N76Protocol.positionPacket(0.0, 0.0, 0.0, 0L, true)[7])
    }

    @Test
    fun pttPacketsUseProgFunc() {
        val on = N76Protocol.pttAssert()
        val off = N76Protocol.pttRelease()
        assertEquals(0x42.toByte(), on[7])
        assertEquals(0x0D.toByte(), on[9])
        assertEquals(0x1A.toByte(), off[9])
    }
}

class N76AudioBusTest {

    @Test
    fun resampleProducesExpectedLength() {
        val input = ShortArray(320) { (it * 20).toShort() }
        val out = N76AudioBus.resampleTo44100(input, input.size)
        assertEquals(441, out.size)
    }
}
