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
package com.rtbishop.look4sat.core.domain.model

object BluetoothAddress {
    private val macRegex = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

    fun normalize(raw: String): String {
        val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return raw.trim().uppercase()
        return hex.chunked(2).joinToString(":")
    }

    fun isValid(raw: String): Boolean = macRegex.matches(normalize(raw))
}
