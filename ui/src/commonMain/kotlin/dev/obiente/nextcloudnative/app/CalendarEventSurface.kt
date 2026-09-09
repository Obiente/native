package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** Existing events use the workspace. Creation and short decisions can still use a dialog. */
@Composable
internal fun CalendarEventSurface(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    dismissEnabled: Boolean = true,
    embedded: Boolean = false,
    inPlace: Boolean = false,
    backLabel: String = "Back to calendar",
) {
    if (!inPlace) {
        CalendarDialogSurface(onDismissRequest, title, text, confirmButton, dismissButton, dismissEnabled, embedded)
        return
    }
    PlatformBackHandler(enabled = true) { if (dismissEnabled) onDismissRequest() }
    Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
            if (dismissEnabled) onDismissRequest()
            true
        } else false
    }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = dismissEnabled, onClick = onDismissRequest) {
                    Icon(NextcloudIcons.Back, contentDescription = backLabel)
                }
                Box(Modifier.weight(1f)) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                }
            }
            HorizontalDivider()
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f).align(Alignment.CenterHorizontally)) {
                Box(Modifier.weight(1f).padding(16.dp)) { text() }
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}
