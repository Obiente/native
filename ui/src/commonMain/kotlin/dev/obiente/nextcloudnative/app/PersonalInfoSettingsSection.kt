package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun rememberAccountProfileController(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
): AccountProfileController {
    val currentServices by rememberUpdatedState(services)
    val scope = rememberCoroutineScope()
    val controller = remember(session.accountId) {
        AccountProfileController(scope) { request -> currentServices.executeNextcloudApi(session, request) }
    }
    LaunchedEffect(controller) { controller.refresh() }
    return controller
}

/** Personal info: profile fields with their visibility, and account facts, read-only. */
@Composable
internal fun PersonalInfoSettingsSection(
    controller: AccountProfileController,
    onOpenStatus: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    val profile = state.profile
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
        onOpenStatus?.let { openStatus ->
            SettingsActionCard(
                title = "Online status",
                description = "Set your availability, status message, and when it clears",
                icon = NextcloudIcons.Clock,
                onClick = openStatus,
            )
        }
        when {
            profile == null && state.loading -> Text("Loading your profile...", style = MaterialTheme.typography.bodyMedium)
            profile == null -> {
                Text(state.loadFailure.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = controller::refresh) { Text("Try again") }
            }
            else -> {
                AccountFactsCard(profile)
                PasswordConfirmationHandoffNotice()
                ProfileValuesCard(profile)
                if (profile.additionalEmails.isNotEmpty()) {
                    ProfileRowsCard(
                        "Additional email addresses",
                        profile.additionalEmails.map { email -> email.address to (email.scope?.label ?: "") },
                    )
                }
                state.loadFailure?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = controller::refresh, enabled = !state.loading) {
                    Text(if (state.loading) "Refreshing..." else "Refresh")
                }
            }
        }
    }
}

@Composable
private fun AccountFactsCard(profile: AccountProfile) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.value(AccountProfileField.DisplayName)?.value?.ifBlank { null } ?: profile.userId,
                style = MaterialTheme.typography.titleMedium)
            Text("Account ID: ${profile.userId}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            profile.quota?.let { quota -> QuotaSummary(quota) }
            listOfNotNull(
                profile.language?.let { "Language" to it },
                profile.locale?.let { "Locale" to it },
                profile.timezone?.let { "Time zone" to it },
                profile.profileEnabled?.let { "Public profile" to if (it) "Enabled" else "Disabled" },
            ).forEach { (label, value) -> FactRow(label, value) }
            if (profile.groups.isNotEmpty()) {
                Text("Groups", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    profile.groups.forEach { group ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                            Text(group, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaSummary(quota: AccountQuota) {
    val used = quota.usedBytes ?: return
    val total = quota.totalBytes?.takeIf { it > 0L }
    Text(
        if (total != null) "${formatByteSize(used)} of ${formatByteSize(total)} used" else "${formatByteSize(used)} used",
        style = MaterialTheme.typography.bodySmall,
    )
    quota.relativePercent?.let { percent ->
        LinearProgressIndicator(progress = { (percent / 100.0).toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ProfileValuesCard(profile: AccountProfile) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleSmall)
            profile.values.forEach { value ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("personal-info-field-${value.field.key}")
                        .semantics(mergeDescendants = true) {
                            stateDescription = value.scope?.let { "Visibility: ${it.label}" } ?: "Visibility unknown"
                        },
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(value.field.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        value.scope?.let { scope ->
                            Text(scope.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(value.value.ifBlank { "Not set" }, style = MaterialTheme.typography.bodyMedium,
                        color = if (value.value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ProfileRowsCard(title: String, rows: List<Pair<String, String>>) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            rows.forEach { (label, value) -> FactRow(label, value) }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}
