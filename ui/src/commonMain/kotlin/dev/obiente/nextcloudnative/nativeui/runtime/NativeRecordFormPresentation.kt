package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal enum class NativeRecordFormPresentation { Inline, Dialog }

internal fun nativeRecordFormPresentation(kind: NativeRecordFormActionKind?): NativeRecordFormPresentation =
    if (kind == NativeRecordFormActionKind.Edit) NativeRecordFormPresentation.Inline
    else NativeRecordFormPresentation.Dialog

/** Both presentations receive the same stateful form fields and submission controls. */
@Composable
internal fun NativeRecordFormPresentationHost(
    presentation: NativeRecordFormPresentation,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
) {
    if (presentation == NativeRecordFormPresentation.Dialog) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            dismissButton = dismissButton,
            confirmButton = confirmButton,
        )
        return
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize().imePadding()
        .focusRequester(focusRequester).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                onDismissRequest()
                true
            } else false
        }.focusable()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                text()
            }
        }
        HorizontalDivider()
        FlowRow(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
            .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            dismissButton()
            confirmButton()
        }
    }
    }
}
