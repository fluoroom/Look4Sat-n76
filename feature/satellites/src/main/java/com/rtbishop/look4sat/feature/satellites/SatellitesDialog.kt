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
package com.rtbishop.look4sat.feature.satellites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.presentation.ConfirmDialog
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.domain.utility.SatStatusCategory
import com.rtbishop.look4sat.core.presentation.R

@Preview(showBackground = true)
@Composable
private fun SatellitesFilterDialogPreview() {
    val modes = listOf("FM", "APT", "SSTV", "BPSK")
    MainTheme { SatellitesFilterDialog(modes, emptyList(), emptySet(), {}) { _, _ -> } }
}

/**
 * Mode and AMSAT status filter. The AMSAT row is an "at least one" test: a satellite is listed
 * when it has at least one report of a selected kind, and selecting none applies no AMSAT filter
 * at all — which is also what happens when the reports cannot be fetched.
 */
@Composable
internal fun SatellitesFilterDialog(
    allModes: List<String>,
    modes: List<String>,
    amSatStatuses: Set<SatStatusCategory>,
    cancel: () -> Unit,
    accept: (List<String>, Set<SatStatusCategory>) -> Unit
) {
    val selected = remember { mutableStateOf(modes.toSet()) }
    val selectedStatuses = remember { mutableStateOf(amSatStatuses) }
    val toggle = { mode: String ->
        selected.value = if (mode in selected.value) selected.value - mode else selected.value + mode
    }
    val onAccept = { accept(selected.value.toList(), selectedStatuses.value) }
    ConfirmDialog(title = stringResource(R.string.sat_type_title), onCancel = cancel, onAccept = onAccept) {
        AmSatStatusRow(
            selected = selectedStatuses.value,
            onToggle = { category ->
                selectedStatuses.value = selectedStatuses.value.let {
                    if (category in it) it - category else it + category
                }
            }
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            modifier = Modifier
                .fillMaxHeight(0.62f)
                .background(MaterialTheme.colorScheme.background),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            itemsIndexed(allModes) { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { toggle(item) }
                ) {
                    Text(
                        text = "${index + 1}).",
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = item,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Checkbox(
                        checked = item in selected.value,
                        onCheckedChange = null,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** AMSAT status chips. No selection reads as "Any", matching the hint on the filter card. */
@Composable
private fun AmSatStatusRow(
    selected: Set<SatStatusCategory>,
    onToggle: (SatStatusCategory) -> Unit
) {
    val options = listOf(
        SatStatusCategory.Active to stringResource(R.string.amsat_active),
        SatStatusCategory.TelemetryOnly to stringResource(R.string.amsat_tlm),
        SatStatusCategory.NotHeard to stringResource(R.string.amsat_not_heard),
        SatStatusCategory.Conflicting to stringResource(R.string.amsat_conflict)
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.amsat_filter),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (category, label) ->
                FilterChip(
                    selected = category in selected,
                    onClick = { onToggle(category) },
                    label = { Text(text = label, fontSize = 13.sp) }
                )
            }
        }
    }
}
