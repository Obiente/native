package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.saveable.Saver
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val NEXTCLOUD_NATIVE_GUIDES_URL = "https://nati.ve/guides/"

@Serializable
internal sealed interface Screen {
    @Serializable
    data object Root : Screen
    @Serializable
    data object Search : Screen
    @Serializable
    data class Files(val path: String) : Screen
    @Serializable
    data object Media : Screen
    @Serializable
    data class PersonMedia(val person: NextcloudPerson) : Screen
    @Serializable
    data object Talk : Screen
    @Serializable
    data object Dashboard : Screen
    @Serializable
    data object UserStatus : Screen
    @Serializable
    data object Calendar : Screen
    @Serializable
    data object Contacts : Screen
    @Serializable
    data object Tasks : Screen
    @Serializable
    data object AdminApps : Screen
    @Serializable
    data object OfflineCenter : Screen
    @Serializable
    data object Transfers : Screen
    @Serializable
    data object ProjectNews : Screen
    @Serializable
    data class ProjectNewsArticleView(val article: ProjectNewsArticle) : Screen
    @Serializable
    data class Chat(val room: TalkRoom) : Screen
    @Serializable
    data class AppInfo(
        val app: NextcloudAppEntry,
        val navigation: DynamicAppNavigationState = DynamicAppNavigationState(),
        val lastKnownServerVersion: String? = null,
        val lastKnownInstalledAppVersion: String? = null,
    ) : Screen
    @Serializable
    data class MediaViewer(
        val navigationKey: String,
        val selectedIndex: Int,
        val selectedSourceIndex: Int,
        val returnTo: Screen,
    ) : Screen
    @Serializable
    data class FileInfo(
        val file: NextcloudFile,
        val parentPath: String,
        val showVersions: Boolean = false,
    ) : Screen
    @Serializable
    data class DocumentPreview(val file: NextcloudFile, val parentPath: String) : Screen
    @Serializable
    data class TextEditor(val file: NextcloudFile, val parentPath: String) : Screen
}

/** Top-level destinations retain global navigation; focused editors and detail views stay immersive. */
internal fun Screen.usesPersistentAppNavigation(): Boolean = when (this) {
    Screen.Root,
    Screen.Search,
    is Screen.Files,
    Screen.Media,
    Screen.Talk,
    Screen.Dashboard,
    Screen.UserStatus,
    Screen.Calendar,
    Screen.Contacts,
    Screen.Tasks,
    Screen.AdminApps,
    Screen.OfflineCenter,
    Screen.Transfers,
    Screen.ProjectNews,
    is Screen.AppInfo,
    -> true

    is Screen.PersonMedia,
    is Screen.ProjectNewsArticleView,
    is Screen.Chat,
    is Screen.MediaViewer,
    is Screen.FileInfo,
    is Screen.DocumentPreview,
    is Screen.TextEditor,
    -> false
}

internal fun Screen.requiresPendingNavigationGuard(groupwareMutationInProgress: Boolean): Boolean =
    groupwareMutationInProgress ||
        this is Screen.TextEditor ||
        this is Screen.MediaViewer ||
        this is Screen.Calendar ||
        this is Screen.Contacts ||
        this is Screen.Tasks

internal fun mutationOrLinkCommitBlocksInteraction(
    mutationInProgress: Boolean,
    navigationCommitInProgress: Boolean,
): Boolean = mutationInProgress || navigationCommitInProgress

internal enum class RootDestinationContent {
    HomeWorkspace,
    FolderSync,
    Apps,
    Activity,
    Settings,
}

internal fun rootDestinationContent(
    destination: NextcloudDestination,
): RootDestinationContent = when (destination) {
    NextcloudDestination.Home -> RootDestinationContent.HomeWorkspace
    NextcloudDestination.FolderSync -> RootDestinationContent.FolderSync
    NextcloudDestination.Apps -> RootDestinationContent.Apps
    NextcloudDestination.Activity -> RootDestinationContent.Activity
    NextcloudDestination.Settings -> RootDestinationContent.Settings
}

private val navigationStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class SavedScreen(
    val kind: String,
    val path: String? = null,
    val appId: String? = null,
    val appName: String? = null,
    val appNavigation: SavedDynamicAppNavigationState? = null,
    val serverVersion: String? = null,
    val installedAppVersion: String? = null,
    val conversationToken: String? = null,
    val routeLabel: String? = null,
    val noteId: Long? = null,
)

@Serializable
internal data class SavedDynamicAppNavigationState(
    val selectedViewId: String? = null,
    val selectedRecordId: String? = null,
    val selectedRecordResourceId: String? = null,
    val pathParameterValues: Map<String, String> = emptyMap(),
    val history: List<SavedDynamicNavigationSnapshot> = emptyList(),
)

internal fun Screen.toSavedScreen(): SavedScreen = when (this) {
    Screen.Root -> SavedScreen("root")
    Screen.Search -> SavedScreen("search")
    is Screen.Files -> SavedScreen("files", path = path.take(MAX_SAVED_FILE_PATH_CHARS))
    Screen.Media, is Screen.PersonMedia, is Screen.MediaViewer -> SavedScreen("media")
    Screen.Talk -> SavedScreen("talk")
    is Screen.Chat -> SavedScreen(
        kind = "chat",
        conversationToken = room.token.takeIf { token ->
            token.isSafeSavedDynamicNavigationValue(MAX_SAVED_TALK_TOKEN_CHARS)
        },
        routeLabel = room.displayName.filterNot(Char::isISOControl)
            .take(MAX_SAVED_ROUTE_LABEL_CHARS)
            .takeIf(String::isNotBlank),
    )
    Screen.Dashboard -> SavedScreen("dashboard")
    Screen.UserStatus -> SavedScreen("user-status")
    Screen.Calendar -> SavedScreen("calendar")
    Screen.Contacts -> SavedScreen("contacts")
    Screen.Tasks -> SavedScreen("tasks")
    Screen.AdminApps -> SavedScreen("admin-apps")
    Screen.OfflineCenter -> SavedScreen("offline-center")
    Screen.Transfers -> SavedScreen("transfers")
    Screen.ProjectNews, is Screen.ProjectNewsArticleView -> SavedScreen("project-news")
    is Screen.AppInfo -> SavedScreen(
        kind = "app-info",
        appId = app.id.take(MAX_SAVED_APP_ID_CHARS),
        appName = app.name.take(MAX_SAVED_APP_NAME_CHARS),
        appNavigation = navigation.toSavedDynamicAppNavigationState(),
        serverVersion = lastKnownServerVersion?.take(MAX_SAVED_VERSION_CHARS),
        installedAppVersion = lastKnownInstalledAppVersion?.take(MAX_SAVED_VERSION_CHARS),
    )
    is Screen.FileInfo -> SavedScreen("files", path = parentPath.take(MAX_SAVED_FILE_PATH_CHARS))
    is Screen.DocumentPreview -> SavedScreen("files", path = parentPath.take(MAX_SAVED_FILE_PATH_CHARS))
    is Screen.TextEditor -> SavedScreen("files", path = parentPath.take(MAX_SAVED_FILE_PATH_CHARS))
}

internal fun SavedScreen.toScreen(): Screen = when (kind) {
    "root" -> Screen.Root
    "search" -> Screen.Search
    "files" -> Screen.Files(path.orEmpty().take(MAX_SAVED_FILE_PATH_CHARS))
    "media" -> Screen.Media
    "talk" -> Screen.Talk
    "chat" -> conversationToken
        ?.takeIf { token -> token.isSafeSavedDynamicNavigationValue(MAX_SAVED_TALK_TOKEN_CHARS) }
        ?.let { token ->
            Screen.Chat(
                TalkRoom(
                    token = token,
                    displayName = routeLabel?.filterNot(Char::isISOControl)
                        ?.take(MAX_SAVED_ROUTE_LABEL_CHARS)
                        ?.takeIf(String::isNotBlank)
                        ?: "Conversation",
                    lastMessage = null,
                    unreadMessages = 0,
                ),
            )
        }
        ?: Screen.Talk
    // Screens saved before boards and notes became contract-selected surfaces reopen the same
    // installed app through discovery, which picks its native surface from the contract again.
    "notes" -> legacySemanticAppScreen("notes", "Notes")
    "note-editor" -> legacySemanticAppScreen("notes", "Notes", legacyOpenNoteNavigation(noteId))
    "dashboard" -> Screen.Dashboard
    "user-status" -> Screen.UserStatus
    "calendar" -> Screen.Calendar
    "contacts" -> Screen.Contacts
    "tasks" -> Screen.Tasks
    "deck" -> legacySemanticAppScreen("deck", "Deck")
    // Mail and News once had dedicated routes. Their saved routes now open the app's dynamic
    // workspace, which chooses the mailbox or feed reader from the installed app's contract.
    "news", "mail" -> Screen.AppInfo(app = NextcloudAppEntry(id = kind, name = kind.replaceFirstChar(Char::uppercase), href = null))
    "admin-apps" -> Screen.AdminApps
    "offline-center" -> Screen.OfflineCenter
    "transfers" -> Screen.Transfers
    "project-news" -> Screen.ProjectNews
    "app-info" -> {
        val restoredAppId = appId?.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_APP_ID_CHARS) }
        if (restoredAppId == null) {
            Screen.Root
        } else {
            Screen.AppInfo(
                app = NextcloudAppEntry(
                    id = restoredAppId,
                    name = appName?.filterNot(Char::isISOControl)?.take(MAX_SAVED_APP_NAME_CHARS)
                        ?.takeIf(String::isNotBlank) ?: restoredAppId,
                    href = null,
                ),
                navigation = appNavigation?.toDynamicAppNavigationState() ?: DynamicAppNavigationState(),
                lastKnownServerVersion = serverVersion?.take(MAX_SAVED_VERSION_CHARS),
                lastKnownInstalledAppVersion = installedAppVersion?.take(MAX_SAVED_VERSION_CHARS),
            )
        }
    }
    else -> Screen.Root
}

private fun legacySemanticAppScreen(
    appId: String,
    appName: String,
    navigation: DynamicAppNavigationState = DynamicAppNavigationState(),
): Screen = Screen.AppInfo(app = NextcloudAppEntry(id = appId, name = appName, href = null), navigation = navigation)

/**
 * A note editor saved before notes became a contract-selected surface reopens that note when its
 * id is valid and the app still opens as notes; otherwise the notes app opens at its root.
 */
private fun legacyOpenNoteNavigation(noteId: Long?): DynamicAppNavigationState =
    noteId?.takeIf { it >= 0L }?.let(::openNoteNavigation) ?: DynamicAppNavigationState()

internal val screenSaver = Saver<Screen, String>(
    save = { screen -> navigationStateJson.encodeToString(screen.toSavedScreen()) },
    restore = { encoded ->
        runCatching { navigationStateJson.decodeFromString<SavedScreen>(encoded).toScreen() }
            .getOrDefault(Screen.Root)
    },
)

@Serializable
private data class SavedAppWorkspaceNavigation(
    val activeAppId: String? = null,
    val lastScreenByApp: Map<String, SavedScreen> = emptyMap(),
)

internal val appWorkspaceNavigationSaver = Saver<AppWorkspaceNavigationMemory<Screen>, String>(
    save = { memory ->
        navigationStateJson.encodeToString(
            SavedAppWorkspaceNavigation(
                activeAppId = memory.activeAppId,
                lastScreenByApp = memory.lastStateByApp.mapValues { (_, screen) -> screen.toSavedScreen() },
            ),
        )
    },
    restore = { encoded ->
        runCatching {
            navigationStateJson.decodeFromString<SavedAppWorkspaceNavigation>(encoded)
        }.map { saved ->
            AppWorkspaceNavigationMemory(
                activeAppId = saved.activeAppId
                    ?.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_APP_ID_CHARS) },
                lastStateByApp = saved.lastScreenByApp.entries
                    .filter { (appId, _) ->
                        appId.isSafeSavedDynamicNavigationValue(MAX_SAVED_APP_ID_CHARS)
                    }
                    .toList()
                    .takeLast(MAX_REMEMBERED_APP_WORKSPACES)
                    .associate { (appId, savedScreen) -> appId to savedScreen.toScreen() },
            )
        }.getOrDefault(AppWorkspaceNavigationMemory())
    },
)

internal const val MAX_SAVED_FILE_PATH_CHARS = 2_048
internal const val MAX_SAVED_APP_ID_CHARS = 128
internal const val MAX_SAVED_APP_NAME_CHARS = 256
internal const val MAX_SAVED_VERSION_CHARS = 128
internal const val MAX_SAVED_TALK_TOKEN_CHARS = 256
internal const val MAX_SAVED_ROUTE_LABEL_CHARS = 256
