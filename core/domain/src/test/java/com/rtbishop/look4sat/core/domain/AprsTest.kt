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
package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.aprs.Afsk1200
import com.rtbishop.look4sat.core.domain.aprs.Afsk1200Demodulator
import com.rtbishop.look4sat.core.domain.aprs.Aprs
import com.rtbishop.look4sat.core.domain.aprs.Ax25
import com.rtbishop.look4sat.core.domain.aprs.Kiss
import com.rtbishop.look4sat.core.domain.aprs.KissDecoder
import com.rtbishop.look4sat.core.domain.model.AprsSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AprsTest {

    private val settings = AprsSettings(
        callsign = "LU1ABC-7",
        message = "hi",
        path = "ARISS,WIDE2-2",
        location = "-34.5, -58.5"
    )

    @Test
    fun `addresses parse with and without an SSID`() {
        assertEquals("LU1ABC" to 7, Ax25.parseAddress(" lu1abc-7 "))
        assertEquals("ARISS" to 0, Ax25.parseAddress("ARISS"))
        assertNull(Ax25.parseAddress("TOOLONG1"))
        assertNull(Ax25.parseAddress("LU1ABC-16"))
        assertNull(Ax25.parseAddress("LU1 ABC"))
        assertNull(Ax25.parseAddress(""))
    }

    @Test
    fun `a path is a comma list of addresses and an empty one is allowed`() {
        assertEquals(listOf("ARISS" to 0, "SGATE" to 0, "WIDE2" to 2), Ax25.parsePath("ARISS, SGATE,WIDE2-2"))
        assertEquals(emptyList<Pair<String, Int>>(), Ax25.parsePath(""))
        assertNull(Ax25.parsePath("ARISS,NOT A CALL"))
    }

    @Test
    fun `fcs is CRC-16 X25`() {
        assertEquals(0x906E, Ax25.fcs("123456789".toByteArray()))
    }

    @Test
    fun `a UI frame lays out destination, source, path, control and PID`() {
        val frame = Aprs.beaconFrame(settings)
        // Destination APZL4S with the command bit, not the last address.
        assertArrayEquals("APZL4S".map { (it.code shl 1).toByte() }.toByteArray(), frame.copyOfRange(0, 6))
        assertEquals(0xE0.toByte(), frame[6])
        // Source LU1ABC-7.
        assertArrayEquals("LU1ABC".map { (it.code shl 1).toByte() }.toByteArray(), frame.copyOfRange(7, 13))
        assertEquals((0x60 or (7 shl 1)).toByte(), frame[13])
        // "ARISS " is space padded; WIDE2-2 closes the address field.
        assertEquals((' '.code shl 1).toByte(), frame[19])
        assertEquals((0x60 or (2 shl 1) or 1).toByte(), frame[27])
        assertEquals(0x03.toByte(), frame[28])
        assertEquals(0xF0.toByte(), frame[29])
        assertEquals("!3430.00S/05830.00Wyhi", String(frame.copyOfRange(30, frame.size), Charsets.US_ASCII))
    }

    @Test
    fun `positions round into the next degree instead of printing 60 minutes`() {
        assertEquals("!1000.00N/02000.00Ey", Aprs.positionInfo(9.99999, 19.99999, ""))
        assertEquals("!0000.60S/00000.60Wyx", Aprs.positionInfo(-0.01, -0.01, "x"))
    }

    @Test
    fun `the chosen icon sets the table and code characters`() {
        assertEquals("!3430.00S/05830.00W>hi", Aprs.positionInfo(-34.5, -58.5, "hi", "/>"))
        // Alternate-table symbols put their backslash where the slash was.
        assertEquals("!3430.00S\\05830.00WShi", Aprs.positionInfo(-34.5, -58.5, "hi", "\\S"))
        // Every offered icon is a two-character code, and the default is one of them.
        assertTrue(AprsSettings.SYMBOLS.all { it.first.length == 2 })
        assertTrue(AprsSettings.SYMBOLS.any { it.first == AprsSettings.DEFAULT_SYMBOL })
    }

    @Test
    fun `location text is coordinates or a locator`() {
        assertEquals(-34.5 to -58.5, Aprs.parseLocation(" -34.5,-58.5 "))
        assertEquals(-34.5 to -58.5, Aprs.parseLocation(Aprs.formatLocation(-34.5, -58.5)))
        assertTrue(Aprs.parseLocation("GF05tj") != null)
        assertNull(Aprs.parseLocation("95, 10"))
        assertNull(Aprs.parseLocation("somewhere"))
    }

    @Test
    fun `no location makes a status report and bad fields are refused by name`() {
        val status = Aprs.beaconFrame(settings.copy(location = ""))
        assertEquals(">hi", String(status.copyOfRange(30, status.size), Charsets.US_ASCII))
        assertThrows(IllegalArgumentException::class.java) { Aprs.beaconFrame(settings.copy(callsign = "")) }
        assertThrows(IllegalArgumentException::class.java) { Aprs.beaconFrame(settings.copy(path = "A B")) }
        assertThrows(IllegalArgumentException::class.java) { Aprs.beaconFrame(settings.copy(location = "nowhere")) }
        assertThrows(IllegalArgumentException::class.java) {
            Aprs.beaconFrame(settings.copy(location = "", message = " "))
        }
    }

    @Test
    fun `kiss framing escapes the delimiter and the escape byte`() {
        val kiss = Kiss.dataFrame(byteArrayOf(0x01, 0xC0.toByte(), 0xDB.toByte(), 0x02))
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 0x00, 0x01, 0xDB.toByte(), 0xDC.toByte(), 0xDB.toByte(), 0xDD.toByte(), 0x02, 0xC0.toByte()),
            kiss
        )
    }

    @Test
    fun `the audio decodes back to the frame at awkward sample rates and through noise`() {
        // 0xFF bytes in the payload force bit stuffing, the part most easily got wrong.
        val frame = Aprs.beaconFrame(settings) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7E)
        val random = Random(7)
        // 44100 Hz is 36.75 samples per bit, so the receiver's clock has to keep itself aligned.
        for (sampleRate in listOf(48_000, 44_100, 32_000)) {
            val heard = ArrayList<ByteArray>()
            val demodulator = Afsk1200Demodulator(sampleRate) { heard += it }
            val audio = Afsk1200.modulate(frame, sampleRate, preambleMs = 200)
            val noisy = FloatArray(audio.size) { audio[it] / 32768f + (random.nextFloat() - 0.5f) * 0.3f }
            // Silence first, then the same packet twice, fed in uneven chunks like a live capture.
            demodulator.feed(FloatArray(1000) { (random.nextFloat() - 0.5f) * 0.3f })
            (noisy + noisy).toList().chunked(777).forEach { demodulator.feed(it.toFloatArray()) }
            assertEquals("rate $sampleRate", 2, heard.size)
            heard.forEach { assertArrayEquals(frame, it) }
        }
    }

    @Test
    fun `a corrupted frame fails its checksum and is not reported`() {
        val heard = ArrayList<ByteArray>()
        val demodulator = Afsk1200Demodulator(48_000) { heard += it }
        val audio = Afsk1200.modulate(Aprs.beaconFrame(settings), 48_000, preambleMs = 200)
        val broken = FloatArray(audio.size) { audio[it] / 32768f }
        // Wipe out a few bits in the middle of the data.
        broken.fill(0f, broken.size / 2, broken.size / 2 + 400)
        demodulator.feed(broken)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `kiss frames are rebuilt across split reads and other commands are ignored`() {
        val ax25 = byteArrayOf(0x01, 0xC0.toByte(), 0xDB.toByte(), 0x02)
        val stream = Kiss.dataFrame(ax25) + byteArrayOf(0xC0.toByte(), 0x01, 0x32, 0xC0.toByte()) + Kiss.dataFrame(ax25)
        val decoder = KissDecoder()
        val frames = stream.toList().chunked(3).flatMap { decoder.feed(it.toByteArray()) }
        assertEquals(2, frames.size)
        frames.forEach { assertArrayEquals(ax25, it) }
    }

    @Test
    fun `frames print in TNC2 form with repeated digipeaters starred`() {
        val frame = Aprs.beaconFrame(settings)
        assertEquals("LU1ABC-7>APZL4S,ARISS,WIDE2-2:!3430.00S/05830.00Wyhi", Ax25.toTnc2(frame))
        // Set the has-been-repeated bit on ARISS, as the ISS digipeater does.
        frame[20] = (frame[20].toInt() or 0x80).toByte()
        assertEquals("LU1ABC-7>APZL4S,ARISS*,WIDE2-2:!3430.00S/05830.00Wyhi", Ax25.toTnc2(frame))
        assertNull(Ax25.toTnc2(byteArrayOf(1, 2, 3)))
    }
}
