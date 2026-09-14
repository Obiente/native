package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder

/** Moves only workspace content; application chrome keeps its independent state. */
@Composable
internal fun rememberAppWorkspaceContent(
    holder: SaveableStateHolder,
    appId: String?,
    screen: Screen,
    content: @Composable () -> Unit,
): @Composable () -> Unit {
    val routeKind = screen.workspaceStateKind()
    // Retain workspace roots only. Detail/editor bundles must not be restored into another record.
    val stateKey = appId?.takeIf { screen.usesPersistentAppNavigation() }?.let { "app:$it:$routeKind" }
    val movable = remember(holder, appId, routeKind) {
        movableContentOf<@Composable () -> Unit> { render ->
            if (stateKey == null) render() else holder.SaveableStateProvider(stateKey) { render() }
        }
    }
    return { movable(content) }
}

/** Keys contain route kinds, never private paths, selected records, or editor content. */
private fun Screen.workspaceStateKind(): String = when (this) {
    Screen.Root -> "root"
    Screen.Search -> "search"
    is Screen.Files -> "files"
    Screen.Media -> "media"
    is Screen.PersonMedia -> "person-media"
    Screen.Talk -> "talk"
    Screen.Notes -> "notes"
    Screen.Dashboard -> "dashboard"
    Screen.UserStatus -> "user-status"
    Screen.Calendar -> "calendar"
    Screen.Contacts -> "contacts"
    Screen.Tasks -> "tasks"
    Screen.Deck -> "deck"
    Screen.AdminApps -> "admin-apps"
    Screen.OfflineCenter -> "offline-center"
    Screen.Transfers -> "transfers"
    Screen.ProjectNews -> "project-news"
    is Screen.ProjectNewsArticleView -> "project-news-article"
    is Screen.Chat -> "chat"
    is Screen.NoteEditor -> "note-editor"
    is Screen.AppInfo -> "app-info"
    is Screen.MediaViewer -> "media-viewer"
    is Screen.FileInfo -> "file-info"
    is Screen.DocumentPreview -> "document-preview"
    is Screen.TextEditor -> "text-editor"
}
