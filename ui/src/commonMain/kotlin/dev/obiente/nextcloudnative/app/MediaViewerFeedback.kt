package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ViewerNavigationButton(
    previous: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(52.dp)
            .background(Color.Black.copy(alpha = 0.58f), CircleShape),
        colors = viewerIconButtonColors(),
    ) {
        Icon(
            imageVector = if (previous) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
            contentDescription = if (previous) "Previous media" else "Next media",
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
internal fun NativeVideoFailureOverlay(
    failure: NativeVideoPlaybackFailure,
    motionOnly: Boolean,
    showCompatibilityAction: Boolean,
    showExternalAction: Boolean,
    externalActionEnabled: Boolean,
    externalOpening: Boolean,
    onCompatibilityPlayback: () -> Unit,
    onOpenExternal: () -> Unit,
    onShowStillPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                color = Color(0xFF101014).copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.large,
            )
            .padding(
                horizontal = if (motionOnly) 16.dp else 24.dp,
                vertical = if (motionOnly) 12.dp else 20.dp,
            ),
        horizontalAlignment = if (motionOnly) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (motionOnly) 4.dp else 8.dp),
    ) {
        Text(
            text = if (motionOnly) "Live motion unavailable" else failure.userTitle(),
            color = Color.White,
            style = if (motionOnly) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
        )
        Text(
            text = if (motionOnly) {
                "The motion video could not play. The still photo remains available."
            } else {
                failure.userDetail()
            },
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (motionOnly) 2 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
        if (motionOnly) {
            TextButton(onClick = onShowStillPhoto, colors = viewerTextButtonColors()) { Text("Show still photo") }
        }
        if (motionOnly && (showCompatibilityAction || showExternalAction)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showCompatibilityAction) {
                    TextButton(onClick = onCompatibilityPlayback, colors = viewerTextButtonColors()) {
                        Text("Try compatibility mode")
                    }
                }
                if (showExternalAction) {
                    TextButton(
                        onClick = onOpenExternal,
                        colors = viewerTextButtonColors(),
                        enabled = externalActionEnabled,
                    ) {
                        Text(if (externalOpening) "Preparing..." else "Open motion externally")
                    }
                }
            }
        } else {
            if (showCompatibilityAction) {
                Button(onClick = onCompatibilityPlayback) {
                    Text("Try compatibility playback")
                }
            }
            if (showExternalAction) {
                TextButton(
                    onClick = onOpenExternal,
                    enabled = externalActionEnabled,
                    colors = viewerTextButtonColors(),
                ) {
                    Text(if (externalOpening) "Preparing..." else "Open in another app")
                }
            }
        }
    }
}

@Composable
internal fun PreviewError(
    detail: String,
    onRetry: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    openingExternal: Boolean,
    externalError: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Couldn't open this preview",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = detail,
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRetry) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Retry",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        onOpenExternal?.let { open ->
            TextButton(onClick = open, enabled = !openingExternal, colors = viewerTextButtonColors()) {
                if (openingExternal) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                }
                Text(
                    if (openingExternal) "Preparing..." else "Open in another app",
                    modifier = Modifier.padding(start = if (openingExternal) 8.dp else 0.dp),
                )
            }
        }
        externalError?.let { message ->
            Text(
                text = message,
                color = Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun viewerIconButtonColors() = IconButtonDefaults.iconButtonColors(
    contentColor = Color.White,
    disabledContentColor = Color.White.copy(alpha = 0.24f),
)


@Composable
private fun viewerTextButtonColors() = androidx.compose.material3.ButtonDefaults.textButtonColors(
    contentColor = Color(0xFFD2BAFF),
    disabledContentColor = Color.White.copy(alpha = 0.45f),
)
