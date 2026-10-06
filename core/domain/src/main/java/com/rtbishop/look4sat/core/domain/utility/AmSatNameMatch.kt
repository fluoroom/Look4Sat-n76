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
package com.rtbishop.look4sat.core.domain.utility

/**
 * Matches AMSAT catalog names ("AO-91[FM]", "ISS-FM") against orbital-data names
 * ("AO-91 (FOX-1B)", "ISS (ZARYA)"), which share a designator but little else.
 *
 * Matching is on whole designators, never substrings: "AO-7" must not match "AO-73", which is
 * exactly what a `contains` or `startsWith` over the stripped names would do. Both sides are
 * reduced to a set of designator candidates — each token, plus each pair of adjacent tokens
 * joined — and a match is an exact hit between the two sets.
 *
 * Ceiling: a satellite AMSAT knows only under a name the orbital data never mentions stays
 * unmatched, and so is filtered out. Upgrade path is a NORAD id in the AMSAT catalog response
 * (it has none today), which would replace this heuristic outright.
 */
object AmSatNameMatch {

    /** Designators an AMSAT catalog name can be known by. */
    fun amSatKeys(amSatName: String): Set<String> {
        val tokens = tokenize(amSatName.substringBefore('['))
        if (tokens.isEmpty()) return emptySet()
        // A one-word name is its own designator, but only once it is long enough to be one:
        // a bare "AO" or "7" would match half the catalog.
        if (tokens.size == 1) return tokens.filter { it.length >= MIN_KEY_LENGTH }.toSet()
        val keys = mutableSetOf(tokens.joinToString(""))
        for (index in 0 until tokens.lastIndex) keys += tokens[index] + tokens[index + 1]
        // "ISS-FM" and "ISS-DATA" are payloads of the satellite the orbital data calls "ISS", so
        // the head alone is a key — but only when the tail is a word. "CAS-5A" is a designator in
        // its own right, and offering "CAS" would pull in CAS-4A along with it.
        val head = tokens.first()
        val tailIsWords = tokens.drop(1).all { token -> token.all(Char::isLetter) }
        if (head.length >= MIN_KEY_LENGTH && tailIsWords) keys += head
        return keys
    }

    /** Designators an orbital-data name can be known by, including the ones in parentheses. */
    fun satelliteKeys(satelliteName: String): Set<String> = designators(tokenize(satelliteName))

    /** True when the satellite carries a designator present in [amSatKeys]. */
    fun matches(satelliteName: String, amSatKeys: Set<String>): Boolean {
        if (amSatKeys.isEmpty()) return false
        return satelliteKeys(satelliteName).any { it in amSatKeys }
    }

    private fun tokenize(name: String): List<String> = name.uppercase()
        .split(*SEPARATORS)
        .filter { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }

    /** Each token, plus each adjacent pair joined: [AO, 91, FOX, 1B] also yields AO91 and FOX1B. */
    private fun designators(tokens: List<String>): Set<String> {
        val keys = tokens.toMutableSet()
        for (index in 0 until tokens.lastIndex) keys += tokens[index] + tokens[index + 1]
        return keys
    }

    private const val MIN_KEY_LENGTH = 3

    private val SEPARATORS = charArrayOf(' ', '-', '_', '(', ')', '[', ']', '.', '/', ',', '&', '+')
}
