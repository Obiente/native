package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.obiente.nextcloudnative.app.design.NextcloudContentEntrance
import dev.obiente.nextcloudnative.app.design.NextcloudEntranceAxis
import dev.obiente.nextcloudnative.app.design.NextcloudMotion
import dev.obiente.nextcloudnative.app.design.NextcloudSharedElementHost

/**
 * Transition between the app shell and a full-page detail such as file info, a chat, a note, or
 * an editor. Opening a detail slides it in from the end; going back slides the shell in from the
 * start. Navigation inside the shell keeps the shell still and uses [routeTransitionKey] through
 * the workspace instead.
 *
 * An [overlay], such as the media viewer, is shown over [content] instead of replacing it, so the
 * screen below keeps its place and scroll position and the overlay can zoom out of it. While an
 * overlay is shown, the screen below is hidden from accessibility services.
 */
@Composable
internal fun AppRouteTransition(
    screen: Screen,
    modifier: Modifier,
    overlay: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val zoomSources = remember { MediaZoomSources() }
    // Shared elements fly above every page and the shell, so the host wraps both.
    NextcloudSharedElementHost(modifier) {
        CompositionLocalProvider(LocalMediaZoomSources provides zoomSources) {
            NextcloudContentEntrance(
                key = screen.pageTransitionKey(),
                axis = NextcloudEntranceAxis.Horizontal,
                modifier = Modifier.fillMaxSize(),
                directional = true,
                distance = NextcloudMotion.RouteEntranceOffset,
            ) {
                Box(Modifier.fillMaxSize().then(if (overlay != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                    content()
                }
            }
            overlay?.invoke()
        }
    }
}

/** Identifies the page shown full screen; every screen inside the shell shares one page. */
internal fun Screen.pageTransitionKey(): Any =
    if (usesPersistentAppNavigation()) AppShellPage else routeTransitionKey()

/**
 * Identifies a route for transitions. It names what is open and ignores state that changes while
 * the same page stays open, such as the media viewer's current item, a dynamic app's inner
 * navigation, or whether file info shows versions. Keys are in memory only and never saved.
 */
internal fun Screen.routeTransitionKey(): Any = when (this) {
    is Screen.Files -> RouteKey("files", path)
    is Screen.PersonMedia -> RouteKey("person-media", person.id.toString())
    is Screen.ProjectNewsArticleView -> RouteKey("project-news-article", article.id)
    is Screen.Chat -> RouteKey("chat", room.token)
    is Screen.NoteEditor -> RouteKey("note-editor", note.id.toString())
    is Screen.AppInfo -> RouteKey("app-info", app.id)
    is Screen.MediaViewer -> RouteKey("media-viewer", navigationKey)
    is Screen.FileInfo -> RouteKey("file-info", file.path)
    is Screen.DocumentPreview -> RouteKey("document-preview", file.path)
    is Screen.TextEditor -> RouteKey("text-editor", file.path)
    else -> RouteKey(this::class.simpleName.orEmpty(), null)
}

internal data class RouteKey(val kind: String, val identity: String?)

private data object AppShellPage
