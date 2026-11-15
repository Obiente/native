package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Where an app's settings live. Settings owned by this hub open a section; others open the app. */
internal sealed interface AppSettingsTarget {
    data class Section(val section: SettingsWorkspaceSection) : AppSettingsTarget
    data class OpenApp(val app: NextcloudAppEntry) : AppSettingsTarget
}

@Immutable
internal data class AppSettingsHubEntry(
    val appId: String,
    val title: String,
    val description: String,
    val target: AppSettingsTarget,
)

/**
 * One entry per installed app, in server navigation order. Apps with a native workspace keep their
 * settings inside that workspace, so the hub opens it. Apps without one say so instead of opening
 * the web interface.
 */
internal fun appSettingsHubEntries(
    apps: List<NextcloudAppEntry>,
    nativeWorkspaceAppIds: Set<String>,
    talkSectionVisible: Boolean,
): List<AppSettingsHubEntry> = apps.filterNot { it.id == "dashboard" }.distinctBy(NextcloudAppEntry::id).map { app ->
    when {
        app.id == "spreed" && talkSectionVisible ->
            AppSettingsHubEntry(app.id, app.name, "Read receipts and typing privacy", AppSettingsTarget.Section(SettingsWorkspaceSection.Talk))
        app.id == "user_status" ->
            AppSettingsHubEntry(app.id, app.name, "Availability and status message", AppSettingsTarget.Section(SettingsWorkspaceSection.PersonalInfo))
        app.id in nativeWorkspaceAppIds ->
            AppSettingsHubEntry(app.id, app.name, "Open ${app.name} to change its settings", AppSettingsTarget.OpenApp(app))
        else ->
            AppSettingsHubEntry(app.id, app.name, "Open the adaptive view; app settings are not available natively yet", AppSettingsTarget.OpenApp(app))
    }
}

@Composable
internal fun SettingsAppSettingsSectionContent(
    entries: List<AppSettingsHubEntry>,
    onOpen: (AppSettingsTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        if (entries.isEmpty()) {
            Text("No apps are installed for this account.", style = MaterialTheme.typography.bodyMedium)
        }
        entries.forEach { entry ->
            SettingsActionCard(
                title = entry.title,
                description = entry.description,
                icon = NextcloudIcons.app(entry.appId),
                onClick = { onOpen(entry.target) },
                modifier = Modifier.testTag("app-settings-${entry.appId}"),
            )
        }
    }
}

@Composable
internal fun SettingsTalkSectionContent(controller: TalkUserSettingsController, modifier: Modifier = Modifier) {
    LaunchedEffect(controller) { controller.refresh() }
    val settings = controller.settings
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(
            "Conversation settings such as name, notifications, lobby, and participants are in each conversation's settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            !controller.loaded -> Text("Loading Talk settings...", style = MaterialTheme.typography.bodyMedium)
            settings == null || settings.shareReadStatus == null && settings.shareTyping == null ->
                Text("This Talk version does not offer privacy settings to apps.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                settings.shareReadStatus?.let { shared ->
                    TalkPrivacyToggle("Share read status", "Others see when you have read their messages", shared,
                        !controller.saving, "talk-share-read-status") { controller.set(TalkUserSettingKey.ReadStatusPrivacy, it) }
                }
                settings.shareTyping?.let { shared ->
                    TalkPrivacyToggle("Share typing status", "Others see when you are typing", shared,
                        !controller.saving, "talk-share-typing") { controller.set(TalkUserSettingKey.TypingPrivacy, it) }
                }
            }
        }
        controller.message?.let { message ->
            Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TalkPrivacyToggle(title: String, description: String, checked: Boolean, enabled: Boolean, tag: String, onChanged: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChanged)
                .padding(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}
