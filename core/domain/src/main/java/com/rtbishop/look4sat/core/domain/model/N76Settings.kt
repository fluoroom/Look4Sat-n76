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

/**
 * The VGC N76 handheld: its Bluetooth link plus the debug-configurable options it accepts.
 * Every feature is independently bypassable.
 *
 * The N76 talks its own Bluetooth protocol rather than CAT, so it carries its own [enabled] flag
 * and its own [deviceAddress] instead of borrowing the CAT radio model and TX address. That keeps
 * CAT to the radios that actually speak it, and lets the handheld be switched on and off without
 * disturbing a configured CAT rig.
 */
enum class N76TxPower { High, Medium, Low }

data class N76Settings(
    val enabled: Boolean = false,
    val deviceAddress: String = "",
    val deviceName: String = "",
    val sendSatInfo: Boolean = true,
    val satFirmware: Boolean = true,
    val pollIntervalMs: Long = POLL_DEFAULT_MS,
    val sendTxPower: Boolean = false,
    val txPower: N76TxPower = N76TxPower.High,
    /** Switch the radio's monitor (squelch forced open) on while tracking, and off again on stop. */
    val openSquelchOnTrack: Boolean = false,
    /** Hand the radio the station position on connect, so APRS need not wait for its own GPS. */
    val sendPosition: Boolean = true,
    val forceRxCtcss: Boolean = false,
    val forceRxCtcssHzx100: Int = 0,
    val forceTxCtcss: Boolean = false,
    val forceTxCtcssHzx100: Int = 0,
    val audioRfcomm: Boolean = false,
    val speakerMonitor: Boolean = false,
    val recordHt: Boolean = false,
    val recordMic: Boolean = false,
    val recordSatOnly: Boolean = false,
    val outputFolderUri: String = "",
    val inputDeviceId: Int = 0,
    /** Boost applied to the phone mic in recordings, to bring it level with the HT audio. */
    val micGainDb: Int = 0
) {
    companion object {
        const val POLL_MIN_MS = 250L
        const val POLL_MAX_MS = 15_000L
        const val POLL_DEFAULT_MS = 10_000L
        val MIC_GAINS_DB = listOf(0, 6, 12, 18, 24)
        val POLL_STEPS_MS = (1..(POLL_MAX_MS / POLL_MIN_MS).toInt()).map { it * POLL_MIN_MS }

        /** Standard CTCSS values as Hz × 100. Index 0 is none. */
        val CTCSS_HZ_X100 = intArrayOf(
            0,
            6700, 7190, 7440, 7700, 7970, 8250, 8540, 8850,
            9150, 9480, 9740, 10000, 10350, 10720, 11090, 11480,
            11880, 12300, 12730, 13180, 13650, 14130, 14620, 15140,
            15670, 16220, 16790, 17380, 17990, 18620, 19280,
            20350, 21070, 21810, 22570, 23360, 24180, 25030
        )
    }
}
