package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Phone presentation for an item's details: a bottom sheet over the list it came from, so the
 * person keeps their place and can swipe, tap outside, press Back, or use Close to return.
 *
 * The caller owns whether the sheet is shown, usually from saveable selection state, so the
 * sheet reopens on the same item after rotation or process restoration. Back and outside taps
 * reach [onDismiss] through the sheet itself; Escape does the same with a hardware keyboard.
 *
 * Give [bodyHeightFraction] when the body needs a bounded height, such as a tabbed inspector
 * with its own scrolling region; otherwise the sheet wraps its content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudDetailSheet(
    title: String?,
    onDismiss: () -> Unit,
    closeLabel: String = "Close details",
    bodyHeightFraction: Float? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Focus moves into the sheet so keyboard and screen reader users land on the details.
        val focus = remember { FocusRequester() }
        LaunchedEffect(focus) { focus.requestFocus() }
        Column(
            Modifier.fillMaxWidth().focusRequester(focus).focusable().onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = NextcloudSpacing.Large, end = NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A body that already names the item, like a tabbed inspector, passes no title.
                if (title != null) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                headerActions()
                IconButton(onClick = onDismiss) {
                    Icon(NextcloudIcons.Close, contentDescription = closeLabel, modifier = Modifier.size(22.dp))
                }
            }
            val body = if (bodyHeightFraction != null) {
                Modifier.fillMaxWidth().fillMaxHeight(bodyHeightFraction)
            } else {
                Modifier.fillMaxWidth()
            }
            Column(body, content = content)
        }
    }
}
