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
package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.model.AprsSettings
import com.rtbishop.look4sat.core.domain.model.AprsTransport
import com.rtbishop.look4sat.core.domain.model.AudioSource
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.MainTheme

@Preview(showBackground = true)
@Composable
private fun AprsPagePreview() = MainTheme {
    AprsPage(
        aprs = AprsSubState(
            settings = AprsSettings(
                callsign = "LU1ABC-7",
                message = "Look4Sat via ISS",
                location = "-34.60370, -58.38160",
                transport = AprsTransport.BluetoothTnc
            ),
            status = "Sent via BT TNC",
            log = listOf("12:34:56 TX LU1ABC-7>APZL4S,ARISS:!3436.22S/05822.90W`Look4Sat via ISS")
        ),
        onAction = {},
        requestGpsFix = {}
    )
}

/**
 * Composes and transmits one APRS report. Every field is sent exactly as typed: the location
 * only changes when edited or when GPS is tapped, and the beacon timer just repeats Send.
 */
@Composable
internal fun AprsPage(
    aprs: AprsSubState,
    onAction: (RadarAction) -> Unit,
    requestGpsFix: () -> Unit
) {
    val settings = aprs.settings
    val update = { changed: AprsSettings -> onAction(RadarAction.AprsUpdate(changed)) }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = settings.callsign,
            onValueChange = { update(settings.copy(callsign = it.uppercase())) },
            label = { Text("Callsign-SSID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.message,
            onValueChange = { update(settings.copy(message = it)) },
            label = { Text("Message") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.path,
            onValueChange = { update(settings.copy(path = it.uppercase())) },
            label = { Text("Path") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = settings.location,
                onValueChange = { update(settings.copy(location = it)) },
                label = { Text("Location (lat, lon or locator)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            CardButton(onClick = requestGpsFix, text = if (aprs.isLocating) "…" else "GPS")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AprsTransport.entries.forEach { transport ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = transport == settings.transport,
                            onClick = { update(settings.copy(transport = transport)) }
                        )
                ) {
                    RadioButton(selected = transport == settings.transport, onClick = null)
                    Text(transport.label, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        ChoiceButton(
            label = "Icon",
            selected = settings.symbol,
            options = AprsSettings.SYMBOLS.map { it.first },
            optionLabel = { code -> AprsSettings.SYMBOLS.firstOrNull { it.first == code }?.second ?: code },
            onSelect = { update(settings.copy(symbol = it)) }
        )
        if (settings.transport == AprsTransport.Audio) {
            ChoiceButton(
                label = "Audio in",
                selected = settings.audioSource,
                options = AudioSource.entries.filter { it != AudioSource.Internal && it != AudioSource.BluetoothSco },
                optionLabel = { it.label },
                onSelect = { update(settings.copy(audioSource = it)) }
            )
            // 0 stands for "let Android decide"; a saved device that is gone reads the same way.
            ChoiceButton(
                label = "Audio out",
                selected = settings.audioOutputId.takeIf { id -> aprs.audioOutputs.any { it.first == id } } ?: 0,
                options = listOf(0) + aprs.audioOutputs.map { it.first },
                optionLabel = { id -> aprs.audioOutputs.firstOrNull { it.first == id }?.second ?: "System default" },
                onSelect = { update(settings.copy(audioOutputId = it)) },
                onOpen = { onAction(RadarAction.AprsRefreshAudioOutputs) }
            )
        }
        if (settings.transport == AprsTransport.N76) {
            Text(
                text = "The N76 beacons with the callsign, path, message and icon set in the radio; " +
                    "the fields above apply to BT TNC and Audio. Set its digital channel to Current.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (settings.transport == AprsTransport.BluetoothTnc) {
            OutlinedTextField(
                value = settings.tncAddress,
                onValueChange = { update(settings.copy(tncAddress = it.uppercase())) },
                label = { Text("TNC Bluetooth address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CardButton(
                onClick = { if (!aprs.isSending) onAction(RadarAction.AprsSend) },
                text = if (aprs.isSending) "Sending…" else "Send"
            )
            Text("Beacon every", fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
            OutlinedTextField(
                value = settings.beaconSeconds.takeIf { it > 0 }?.toString().orEmpty(),
                onValueChange = { text ->
                    update(settings.copy(beaconSeconds = text.filter(Char::isDigit).take(5).toIntOrNull() ?: 0))
                },
                label = { Text("s") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(84.dp)
            )
            Switch(checked = aprs.isBeaconing, onCheckedChange = { onAction(RadarAction.AprsSetBeaconing(it)) })
        }
        Text(
            text = aprs.status.ifEmpty { "Beacons no faster than every ${AprsSettings.MIN_BEACON_SECONDS} s" },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Packet log", fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("Listen", fontSize = 13.sp)
            Switch(checked = aprs.isListening, onCheckedChange = { onAction(RadarAction.AprsSetListening(it)) })
        }
        SelectionContainer {
            Text(
                text = if (aprs.log.isEmpty()) "No packets yet" else aprs.log.asReversed().joinToString("\n"),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A full-width button showing "label: value" that opens the list of choices. */
@Composable
private fun <T> ChoiceButton(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onOpen: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        CardButton(
            onClick = { onOpen(); expanded = true },
            text = "$label: ${optionLabel(selected)}",
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), fontSize = 13.sp) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
