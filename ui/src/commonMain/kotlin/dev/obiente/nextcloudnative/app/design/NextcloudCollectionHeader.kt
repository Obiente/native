package dev.obiente.nextcloudnative.app.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun NextcloudCollectionHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    leadingControl: NextcloudCollectionLeadingControl,
    onOpenNavigation: (() -> Unit)?,
    showHierarchyBack: Boolean,
    compact: Boolean,
    actions: @Composable () -> Unit,
    titleContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 54.dp else 64.dp)
            .padding(horizontal = NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (leadingControl) {
            NextcloudCollectionLeadingControl.Back -> IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(NextcloudIcons.Back, contentDescription = "Back")
            }

            NextcloudCollectionLeadingControl.Menu -> IconButton(
                onClick = requireNotNull(onOpenNavigation),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(NextcloudIcons.Menu, contentDescription = "Open sections")
            }
        }
        if (showHierarchyBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(NextcloudIcons.Back, contentDescription = "Back")
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = NextcloudSpacing.Small),
        ) {
            if (titleContent != null) titleContent() else Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let { supportingText ->
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
