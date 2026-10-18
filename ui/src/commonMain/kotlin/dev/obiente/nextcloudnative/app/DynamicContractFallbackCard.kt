package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/**
 * The callbacks a fallback card can invoke. A missing callback hides its action, so a screen that
 * cannot leave for account settings shows only the explanation for that state.
 */
internal class DynamicContractFallbackActions(
    val onCheckAgain: () -> Unit,
    val onOpenAccountSettings: (() -> Unit)? = null,
)

/** The callback for [notice]'s action, or null when this screen cannot perform it. */
internal fun DynamicContractFallbackActions.callbackFor(notice: DynamicContractFallbackNotice): (() -> Unit)? =
    when (notice.action) {
        DynamicContractFallbackAction.CheckAgain -> onCheckAgain
        DynamicContractFallbackAction.OpenAccountSettings -> onOpenAccountSettings
    }

/** The notice above a metadata fallback: what happened, and the action that can help, if any. */
@Composable
internal fun DynamicContractFallbackCard(
    reason: DynamicContractFallbackReason?,
    actions: DynamicContractFallbackActions,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Large,
            vertical = NextcloudSpacing.Small,
        ),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            val notice = reason.fallbackNotice()
            Text(notice.title, style = MaterialTheme.typography.titleSmall)
            Text(notice.message, style = MaterialTheme.typography.bodySmall)
            actions.callbackFor(notice)?.let { onClick ->
                TextButton(onClick = onClick) { Text(notice.actionLabel) }
            }
        }
    }
}
