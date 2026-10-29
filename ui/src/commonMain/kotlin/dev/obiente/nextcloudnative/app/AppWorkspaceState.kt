package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudMotion

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
    return {
        // Routes inside one app slide forward and back; switching apps or destinations starts a fresh
        // entrance here, so the shell's own transition is never doubled.
        key(appId) {
            NextcloudContentEntrance(
                key = if (screen.usesPersistentAppNavigation()) screen.routeTransitionKey() else null,
                axis = NextcloudEntranceAxis.Horizontal,
                modifier = Modifier.fillMaxSize(),
                directional = true,
                distance = NextcloudMotion.RouteEntranceOffset,
            ) { movable(content) }
        }
    }
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
    Screen.News -> "news"
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
