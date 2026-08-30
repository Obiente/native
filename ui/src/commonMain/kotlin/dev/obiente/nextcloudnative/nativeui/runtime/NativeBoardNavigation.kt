package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal fun nativeBoardLaneWidth(availableWidth: Float, laneCount: Int): Float =
    if (availableWidth < 720f) (availableWidth - 64f).coerceIn(240f, 340f)
    else ((availableWidth - 48f - 16f * (laneCount - 1).coerceAtLeast(0)) /
        laneCount.coerceAtLeast(1)).coerceIn(284f, 440f)

@Composable
internal fun NativeBoardLaneJump(lanes: List<NativeBoardLane>, onSelectLane: (Int) -> Unit) {
    var expanded by remember(lanes.map { it.key }) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().zIndex(1f).padding(horizontal = NextcloudSpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { expanded = true }) { Text("Lists (${lanes.size})") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                lanes.forEachIndexed { index, lane ->
                    DropdownMenuItem(
                        text = { Text("${lane.title} (${lane.records.size})") },
                        onClick = {
                            expanded = false
                            onSelectLane(index)
                        },
                    )
                }
            }
        }
    }
}
