package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

@Composable
internal fun GenericResourceIcon(
    resource: ResourceSpec,
    recordIconKey: String? = null,
    recordColorArgb: Int? = null,
    large: Boolean = false,
) {
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    val icon = recordIconKey?.let(NextcloudIcons::semanticOrFallback)
        ?: nativeResourceIconAppId(resource)?.let(NextcloudIcons::app)
        ?: when {
        resource.fields.any { it.kind == FieldKind.image } -> NextcloudIcons.Image
        resource.fields.any { it.kind == FieldKind.file } -> NextcloudIcons.File
        resource.fields.any { it.kind == FieldKind.userReference } -> NextcloudIcons.People
        else -> NextcloudIcons.Apps
    }
    Surface(color = NextcloudTheme.colors.appIconContainer, shape = MaterialTheme.shapes.small) {
        Icon(
            icon,
            contentDescription = null,
            tint = recordColorArgb?.let(::Color) ?: NextcloudTheme.colors.appIcon,
            modifier = Modifier.padding(
                when {
                    large -> NextcloudSpacing.Medium
                    dense -> 6.dp
                    else -> NextcloudSpacing.Small
                },
            ).size(
                when {
                    large -> 30.dp
                    dense -> 20.dp
                    else -> 24.dp
                },
            ),
        )
    }
}

/**
 * Picks an app-style icon from resource semantics, independent of which Nextcloud app exposed it.
 * Field-shape fallbacks remain useful for unfamiliar resources, but semantic nouns must win: a
 * recipe that happens to contain dates is still a recipe, and a message with attachments is still
 * mail. Exact token matching avoids accidental matches such as `card` inside `discarded`.
 */
internal fun nativeResourceIconAppId(resource: ResourceSpec): String? {
    val tokens = buildSet {
        addAll(resource.id.nativeSemanticTokens())
        addAll(resource.name.nativeSemanticTokens())
    }
    return when {
        tokens.any { it in setOf("recipe", "recipes", "cookbook") } -> "cookbook"
        tokens.any { it in setOf("message", "messages", "mail", "mailbox", "mailboxes", "email", "emails") } -> "mail"
        tokens.any { it in setOf("song", "songs", "track", "tracks", "artist", "artists", "playlist", "playlists", "music") } -> "music"
        tokens.any { it in setOf("board", "boards", "card", "cards", "stack", "stacks", "deck") } -> "deck"
        tokens.any { it in setOf("table", "tables", "row", "rows", "column", "columns") } -> "tables"
        tokens.any { it in setOf("expense", "expenses", "payment", "payments", "transaction", "transactions", "budget", "budgets", "bill", "bills") } -> "cospend"
        tokens.any { it in setOf("file", "files", "folder", "folders", "directory", "directories") } -> "files"
        tokens.any { it in setOf("photo", "photos", "image", "images", "album", "albums", "memory", "memories") } -> "photos"
        tokens.any { it in setOf("conversation", "conversations", "chat", "chats", "room", "rooms", "talk") } -> "talk"
        tokens.any { it in setOf("task", "tasks", "todo", "todos") } -> "tasks"
        tokens.any { it in setOf("note", "notes") } -> "notes"
        tokens.any { it in setOf("contact", "contacts", "addressbook", "addressbooks") } -> "contacts"
        tokens.any { it in setOf("event", "events", "calendar", "calendars") } -> "calendar"
        else -> null
    }
}

private fun String.nativeSemanticTokens(): Set<String> {
    val tokens = linkedSetOf<String>()
    val token = StringBuilder()
    fun flush() {
        if (token.isNotEmpty()) {
            tokens += token.toString().lowercase()
            token.clear()
        }
    }
    forEachIndexed { index, character ->
        val previous = getOrNull(index - 1)
        val startsCamelWord = character.isUpperCase() && previous?.isLowerCase() == true
        if (startsCamelWord || !character.isLetterOrDigit()) flush()
        if (character.isLetterOrDigit()) token.append(character)
    }
    flush()
    return tokens
}
