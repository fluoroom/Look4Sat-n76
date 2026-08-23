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

import com.rtbishop.look4sat.core.domain.model.BluetoothAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAddressTest {

    @Test
    fun normalizeAcceptsColonDashAndBareHex() {
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.normalize("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.normalize("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.normalize("aabbccddeeff"))
    }

    @Test
    fun isValidRejectsShortOrJunk() {
        assertTrue(BluetoothAddress.isValid("AA:BB:CC:DD:EE:FF"))
        assertFalse(BluetoothAddress.isValid("AA:BB:CC:DD:EE"))
        assertFalse(BluetoothAddress.isValid("not-a-mac"))
    }
}
