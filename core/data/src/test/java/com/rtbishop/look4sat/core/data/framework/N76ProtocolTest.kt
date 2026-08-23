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

import org.junit.Assert.assertEquals
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
