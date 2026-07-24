package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.itemsIndexed as indexedListItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudAppTile
import dev.obiente.nextcloudnative.app.design.NextcloudBottomNavigation
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudNavigationRail
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudNavigationStyle
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.isNextcloudDarkTheme
import dev.obiente.nextcloudnative.app.design.resolveNextcloudRootShellLayout
import dev.obiente.nextcloudnative.app.design.shouldUseNextcloudRootShell
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.model.isSecondaryTechnicalDestination
import dev.obiente.nextcloudnative.nativeui.model.planDynamicNavigation
import dev.obiente.nextcloudnative.nativeui.model.preferredSemanticContextualChild
import dev.obiente.nextcloudnative.nativeui.model.singleSafeContextualChild
import dev.obiente.nextcloudnative.nativeui.model.dynamicNavigationState
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.resolveDynamicRecordReadParameters
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeImageLoader
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioRecordPlayer
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.effectiveNativeResourceId
import dev.obiente.nextcloudnative.nativeui.runtime.actionBindingValues
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.settingsFormPrefillView
import dev.obiente.nextcloudnative.nativeui.runtime.editableNativeFields
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private sealed interface Screen {
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
    data object Notes : Screen
    @Serializable
    data object Dashboard : Screen
    @Serializable
    data object UserStatus : Screen
    @Serializable
    data object Calendar : Screen
    @Serializable
    data object Contacts : Screen
    @Serializable
    data object AdminApps : Screen
    @Serializable
    data object OfflineCenter : Screen
    @Serializable
    data class Chat(val room: TalkRoom) : Screen
    @Serializable
    data class NoteEditor(val note: NextcloudNote) : Screen
    @Serializable
    data class AppInfo(
        val app: NextcloudAppEntry,
        val navigation: DynamicAppNavigationState = DynamicAppNavigationState(),
    ) : Screen
    @Serializable
    data class MediaViewer(
        val media: List<NextcloudFile>,
        val selected: NextcloudFile,
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

internal enum class RootDestinationContent {
    HomeWorkspace,
    Apps,
    Activity,
    Settings,
}

internal fun rootDestinationContent(
    destination: NextcloudDestination,
): RootDestinationContent = when (destination) {
    NextcloudDestination.Home -> RootDestinationContent.HomeWorkspace
    NextcloudDestination.Apps -> RootDestinationContent.Apps
    NextcloudDestination.Activity -> RootDestinationContent.Activity
    NextcloudDestination.Settings -> RootDestinationContent.Settings
}

private val navigationStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val screenSaver = Saver<Screen, String>(
    save = { screen -> navigationStateJson.encodeToString(screen) },
    restore = { encoded ->
        runCatching { navigationStateJson.decodeFromString<Screen>(encoded) }.getOrDefault(Screen.Root)
    },
)

private inline fun <reified T : Enum<T>> enumSaver() = Saver<T, String>(
    save = { value -> value.name },
    restore = { saved -> enumValues<T>().firstOrNull { it.name == saved } ?: enumValues<T>().first() },
)

private enum class FileLayout { List, Grid }
private enum class MediaMode { Timeline, Collections, People }
private enum class PersonPhotoSelectionMode { Cover, RemoveFace }

private class MediaCollectionsUiState {
    var catalog by mutableStateOf<NativeMediaCollectionCatalog?>(null)
    var browserState by mutableStateOf(NativeMediaCollectionBrowserState())
    var selectedCollection by mutableStateOf<NativeMediaCollection?>(null)
    var dayIndex by mutableStateOf<NativeMediaDayIndex?>(null)
    var collectionItems by mutableStateOf<List<NativeMediaItem>>(emptyList())
    var resolvedFiles by mutableStateOf<Map<Long, NextcloudFile>>(emptyMap())
    var backupStatuses by mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())
    var cursor by mutableStateOf<NativeMediaDayCursor?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pendingAction by mutableStateOf<NativeMediaCollectionActionPlan?>(null)
    var createAlbumVisible by mutableStateOf(false)
    var createAlbumName by mutableStateOf("")
    var mediaToAdd by mutableStateOf<NextcloudFile?>(null)
    var mutationRunning by mutableStateOf(false)
    var mutationError by mutableStateOf<String?>(null)
    var loadAttempt by mutableStateOf(0)
    var requestGeneration: Long = 0L
}

private val nativeAppIds = setOf(
    "files",
    "photos",
    "memories",
    "spreed",
    "talk",
    "activity",
    "notes",
    "dashboard",
    "user_status",
)

@Composable
fun NextcloudNativeApp(
    services: NextcloudPlatformServices,
    presentation: NextcloudPresentation = NextcloudPresentation.Adaptive,
) {
    var themePreference by remember { mutableStateOf(services.loadThemePreference()) }
    val darkTheme = isNextcloudDarkTheme(themePreference)

    NextcloudNativeTheme(darkTheme = darkTheme) {
        NextcloudAppBackground {
            var session by remember { mutableStateOf(services.loadSession()) }
            if (session == null) {
                LoginScreen(
                    services = services,
                    onLoggedIn = { authenticated ->
                        services.saveSession(authenticated)
                        session = authenticated
                    },
                )
            } else {
                AuthenticatedApp(
                    services = services,
                    session = requireNotNull(session),
                    presentation = presentation,
                    themePreference = themePreference,
                    onThemePreferenceChanged = { selected ->
                        services.saveThemePreference(selected)
                        themePreference = selected
                    },
                    onLoggedOut = {
                        services.clearSession()
                        session = null
                    },
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    services: NextcloudPlatformServices,
    onLoggedIn: (NextcloudSession) -> Unit,
) {
    var serverUrl by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp).padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                Icon(
                    NextcloudIcons.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(14.dp).size(34.dp),
                )
            }
            Text("Nextcloud Native", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Your cloud, in one native app.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                placeholder = { Text("https://cloud.example.com") },
                singleLine = true,
                enabled = !connecting,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = serverUrl.isNotBlank() && !connecting,
                onClick = {
                    connecting = true
                    error = null
                    status = "Contacting your server…"
                    scope.launch {
                        runCatching {
                            val challenge = services.beginLogin(serverUrl)
                            services.openExternalUrl(challenge.loginUrl)
                            status = "Finish signing in in your browser, then return here."
                            repeat(150) {
                                services.pollLogin(challenge)?.let { return@runCatching it }
                                delay(2_000)
                            }
                            error("Login approval timed out. Please try again.")
                        }.onSuccess(onLoggedIn).onFailure { failure ->
                            error = failure.message ?: "Could not connect to this server."
                            connecting = false
                            status = null
                        }
                    }
                },
            ) {
                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp))
                }
                Text(if (connecting) "Waiting for approval" else "Connect")
            }
        }
    }
}

@Composable
private fun AuthenticatedApp(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    presentation: NextcloudPresentation,
    themePreference: ThemePreference,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onLoggedOut: () -> Unit,
) {
    var screen by rememberSaveable(session.serverUrl, session.loginName, stateSaver = screenSaver) {
        mutableStateOf<Screen>(Screen.Root)
    }
    var destination by rememberSaveable(
        session.serverUrl,
        session.loginName,
        stateSaver = enumSaver<NextcloudDestination>(),
    ) { mutableStateOf(NextcloudDestination.Home) }
    var returnDestination by rememberSaveable(
        session.serverUrl,
        session.loginName,
        stateSaver = enumSaver<NextcloudDestination>(),
    ) { mutableStateOf(NextcloudDestination.Home) }
    var serverInfo by remember(session) { mutableStateOf<NextcloudServerInfo?>(null) }
    val cachedAppDiscoveries = remember(session) { mutableStateMapOf<String, DynamicDescriptorDiscovery>() }
    var discoveryError by remember(session) { mutableStateOf<String?>(null) }
    var discoveryAttempt by remember(session) { mutableStateOf(0) }
    var fileLayout by rememberSaveable(stateSaver = enumSaver<FileLayout>()) { mutableStateOf(FileLayout.List) }
    var mediaMode by rememberSaveable(stateSaver = enumSaver<MediaMode>()) { mutableStateOf(MediaMode.Timeline) }
    val mediaCollectionsState = remember(session) { MediaCollectionsUiState() }
    val mediaCollectionGridState = rememberLazyGridState()

    LaunchedEffect(session, discoveryAttempt) {
        serverInfo = null
        discoveryError = null
        runCatching { services.loadServerInfo(session) }
            .onSuccess { serverInfo = it }
            .onFailure { discoveryError = it.message ?: "Could not load server details." }
    }

    fun openApp(app: NextcloudAppEntry, from: NextcloudDestination) {
        returnDestination = from
        services.saveLastOpenedAppId(app.id)
        screen = when (app.id) {
            "files" -> Screen.Files("")
            "photos", "memories" -> Screen.Media
            "spreed", "talk" -> Screen.Talk
            "notes" -> Screen.Notes
            "dashboard" -> {
                destination = NextcloudDestination.Home
                Screen.Root
            }
            "user_status" -> Screen.UserStatus
            "calendar" -> Screen.Calendar
            "contacts" -> Screen.Contacts
            "activity" -> {
                destination = NextcloudDestination.Activity
                Screen.Root
            }
            else -> Screen.AppInfo(app)
        }
    }

    fun openSearch() {
        returnDestination = destination
        screen = Screen.Search
    }

    fun navigateBack() {
        when (val current = screen) {
            Screen.Root -> destination = NextcloudDestination.Home
            is Screen.Files -> {
                screen = if (current.path.isBlank()) Screen.Root
                else Screen.Files(current.path.substringBeforeLast('/', ""))
                if (screen == Screen.Root) destination = returnDestination
            }
            Screen.Search,
            Screen.Media,
            Screen.Talk,
            Screen.Notes,
            Screen.Dashboard,
            Screen.UserStatus,
            Screen.Calendar,
            Screen.Contacts,
            is Screen.AppInfo,
            -> {
                screen = Screen.Root
                destination = returnDestination
            }
            Screen.AdminApps -> {
                screen = Screen.Root
                destination = NextcloudDestination.Settings
            }
            Screen.OfflineCenter -> {
                screen = Screen.Root
                destination = NextcloudDestination.Settings
            }
            is Screen.PersonMedia -> screen = Screen.Media
            is Screen.Chat -> screen = Screen.Talk
            is Screen.NoteEditor -> screen = Screen.Notes
            is Screen.MediaViewer -> screen = current.returnTo
            is Screen.FileInfo -> screen = Screen.Files(current.parentPath)
            is Screen.DocumentPreview -> screen = Screen.Files(current.parentPath)
            is Screen.TextEditor -> screen = Screen.Files(current.parentPath)
        }
    }

    PlatformBackHandler(
        enabled = when (screen) {
            is Screen.NoteEditor, is Screen.TextEditor -> false
            Screen.Root -> destination != NextcloudDestination.Home
            else -> true
        },
        onBack = ::navigateBack,
    )

    val desktopIdentity = serverInfo?.let { info ->
        NextcloudDesktopIdentity(
            displayName = info.displayName,
            cloudName = info.themeName ?: "Nextcloud",
        )
    }
    val screenContent: @Composable () -> Unit = {
        when (val current = screen) {
            Screen.Root -> when (rootDestinationContent(destination)) {
                RootDestinationContent.HomeWorkspace -> NativeDashboardScreen(
                    services = services,
                    session = session,
                    installedApps = serverInfo?.apps.orEmpty(),
                    onOpenApp = { openApp(it, NextcloudDestination.Home) },
                    onOpenStatus = serverInfo?.apps
                        ?.firstOrNull { it.id == "user_status" }
                        ?.let { statusApp ->
                            { openApp(statusApp, NextcloudDestination.Home) }
                        },
                    onBack = null,
                    onSearch = ::openSearch,
                    onSettings = { destination = NextcloudDestination.Settings },
                )
                RootDestinationContent.Apps -> AppsScreen(
                    serverInfo = serverInfo,
                    error = discoveryError,
                    onRetry = { discoveryAttempt += 1 },
                    onSettings = { destination = NextcloudDestination.Settings },
                    onSearch = ::openSearch,
                    onOpenApp = { openApp(it, NextcloudDestination.Apps) },
                )
                RootDestinationContent.Activity -> ActivityScreen(
                    services = services,
                    session = session,
                    activityInstalled = serverInfo?.apps?.any { it.id == "activity" } == true,
                    installedApps = serverInfo?.apps.orEmpty(),
                    onApps = { destination = NextcloudDestination.Apps },
                    onOpenApp = { app -> openApp(app, NextcloudDestination.Activity) },
                )
                RootDestinationContent.Settings -> SettingsScreen(
                    services = services,
                    session = session,
                    serverInfo = serverInfo,
                    themePreference = themePreference,
                    onThemePreferenceChanged = onThemePreferenceChanged,
                    onAdminApps = { screen = Screen.AdminApps },
                    onOfflineCenter = { screen = Screen.OfflineCenter },
                    onLoggedOut = onLoggedOut,
                )
            }
            Screen.OfflineCenter -> FileOfflineCenterScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            onBack = ::navigateBack,
        )
        is Screen.Files -> FilesScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId,
            fileSharing = serverInfo?.fileSharing ?: NextcloudFileSharingCapabilities.Unavailable,
            path = current.path,
            layout = fileLayout,
            onLayoutChanged = { fileLayout = it },
            onBack = ::navigateBack,
            onOpenFolder = { screen = Screen.Files(it) },
            onOpenFile = { file, siblings ->
                val document = describeDocument(file)
                screen = when {
                    file.isEditableText() -> Screen.TextEditor(file, current.path)
                    document.method == DocumentPreviewMethod.ServerRaster ->
                        Screen.DocumentPreview(file, current.path)
                    file.hasPreview && file.fileId != null -> Screen.MediaViewer(
                        media = siblings.filter { it.hasPreview && it.fileId != null },
                        selected = file,
                        returnTo = current,
                    )
                    else -> Screen.FileInfo(file, current.path)
                }
            },
            onFileAction = { file, action, siblings ->
                when (action) {
                    FileMenuAction.Open -> if (file.isDirectory) screen = Screen.Files(file.path)
                    FileMenuAction.Preview -> {
                        val document = describeDocument(file)
                        screen = if (document.method != DocumentPreviewMethod.Unsupported) {
                            Screen.DocumentPreview(file, current.path)
                        } else if (file.hasPreview && file.fileId != null) {
                            Screen.MediaViewer(
                                media = siblings.filter { it.hasPreview && it.fileId != null },
                                selected = file,
                                returnTo = current,
                            )
                        } else {
                            Screen.FileInfo(file, current.path)
                        }
                    }
                    FileMenuAction.Details -> screen = Screen.FileInfo(file, current.path)
                    FileMenuAction.VersionHistory -> screen = Screen.FileInfo(
                        file = file,
                        parentPath = current.path,
                        showVersions = true,
                    )
                    FileMenuAction.EditText -> if (file.isEditableText()) {
                        screen = Screen.TextEditor(file, current.path)
                    }
                    else -> Unit
                }
            },
        )
        Screen.Search -> Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            NextcloudUnifiedSearchScreen(
                services = services,
                session = session,
                onBack = ::navigateBack,
                onOpenResult = { selection ->
                    val fileParent = selection.nativeFileParentPathOrNull()
                    if (fileParent != null) {
                        screen = Screen.Files(fileParent)
                    } else {
                        val app = serverInfo?.apps?.firstOrNull { candidate ->
                            candidate.id == selection.provider.appId ||
                                selection.provider.id.startsWith(candidate.id)
                        }
                        if (app != null) {
                            openApp(app, returnDestination)
                        } else {
                            selection.entry.resourceUrl?.let { resource ->
                                val absolute = if (resource.startsWith("/")) session.serverUrl.trimEnd('/') + resource else resource
                                services.openExternalUrl(absolute)
                            }
                        }
                    }
                },
            )
        }
        Screen.Dashboard -> NativeDashboardScreen(
            services = services,
            session = session,
            installedApps = serverInfo?.apps.orEmpty(),
            onOpenApp = { app -> openApp(app, returnDestination) },
            onOpenStatus = serverInfo?.apps
                ?.firstOrNull { it.id == "user_status" }
                ?.let { statusApp ->
                    { openApp(statusApp, returnDestination) }
                },
            onBack = ::navigateBack,
        )
        Screen.UserStatus -> NativeUserStatusScreen(
            services = services,
            session = session,
            onBack = ::navigateBack,
        )
        Screen.Calendar -> NativeGroupwareCalendarScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId ?: session.loginName,
            onBack = ::navigateBack,
        )
        Screen.Contacts -> NativeGroupwareContactsScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId ?: session.loginName,
            onBack = ::navigateBack,
        )
        Screen.AdminApps -> AdminAppsScreen(
            services = services,
            session = session,
            serverInfo = serverInfo,
            onOpenApp = { openApp(it, NextcloudDestination.Settings) },
            onBack = ::navigateBack,
        )
        Screen.Media -> MediaScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId,
            mode = mediaMode,
            collectionState = mediaCollectionsState,
            collectionGridState = mediaCollectionGridState,
            onModeChanged = { mediaMode = it },
            onBack = ::navigateBack,
            onOpenMedia = { file, media ->
                screen = Screen.MediaViewer(media = media, selected = file, returnTo = Screen.Media)
            },
            onOpenPerson = { screen = Screen.PersonMedia(it) },
        )
        is Screen.PersonMedia -> PersonMediaScreen(
            services = services,
            session = session,
            currentUserId = serverInfo?.userId ?: session.loginName,
            recognizeBridge = serverInfo?.recognizeBridge ?: RecognizeBridgeDiscovery.NotAdvertised,
            person = current.person,
            onBack = ::navigateBack,
            onOpenMedia = { file, media ->
                screen = Screen.MediaViewer(media = media, selected = file, returnTo = current)
            },
        )
        Screen.Talk -> TalkScreen(
            services = services,
            session = session,
            onBack = ::navigateBack,
            onOpenRoom = { screen = Screen.Chat(it) },
        )
        Screen.Notes -> NextcloudNotesScreen(
            services = services,
            session = session,
            onBack = ::navigateBack,
            onOpenNote = { screen = Screen.NoteEditor(it) },
        )
        is Screen.NoteEditor -> NextcloudNoteEditor(
            services = services,
            session = session,
            note = current.note,
            onBack = ::navigateBack,
        )
        is Screen.Chat -> ChatScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            room = current.room,
            onBack = ::navigateBack,
            onOpenAttachment = { file ->
                screen = Screen.MediaViewer(
                    media = listOf(file),
                    selected = file,
                    returnTo = current,
                )
            },
        )
        is Screen.AppInfo -> AppInfoScreen(
            services = services,
            session = session,
            app = current.app,
            serverVersion = serverInfo?.version,
            cachedDiscovery = cachedAppDiscoveries[current.app.id]
                ?: sharedDynamicNativeMemoryCache.discovery(session, current.app.id),
            onDiscovery = { candidate ->
                val cached = cachedAppDiscoveries[current.app.id]
                if (cached == null || candidate.acquisition != DynamicDescriptorAcquisition.MetadataFallback) {
                    cachedAppDiscoveries[current.app.id] = candidate
                    sharedDynamicNativeMemoryCache.storeDiscovery(session, current.app.id, candidate)
                }
            },
            navigation = current.navigation,
            onNavigationChanged = { navigation ->
                val active = screen as? Screen.AppInfo
                if (active?.app?.id == current.app.id && active.navigation != navigation) {
                    screen = active.copy(navigation = navigation)
                }
            },
            onBack = ::navigateBack,
        )
        is Screen.MediaViewer -> NextcloudMediaViewer(
            media = current.media,
            selected = current.selected,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            services = services,
            taggingAvailable = serverInfo?.apps?.any { it.id == "memories" } == true,
            sharingCapabilities = serverInfo?.fileSharing ?: NextcloudFileSharingCapabilities.Unavailable,
            onSelect = { screen = current.copy(selected = it) },
            onSourceRemoved = { screen = current.returnTo },
            onClose = { screen = current.returnTo },
        )
        is Screen.FileInfo -> FileInfoScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            file = current.file,
            onBack = ::navigateBack,
            showVersions = current.showVersions,
            onVersionRestored = { screen = Screen.Files(current.parentPath) },
            onEdit = if (current.file.isEditableText()) {
                { screen = Screen.TextEditor(current.file, current.parentPath) }
            } else {
                null
            },
        )
        is Screen.DocumentPreview -> Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ScreenHeader(current.file.name, "Document preview", ::navigateBack)
            NextcloudDocumentPreview(
                file = current.file,
                session = session,
                userId = serverInfo?.userId.orEmpty(),
                services = services,
                modifier = Modifier.weight(1f),
            )
        }
            is Screen.TextEditor -> TextEditorScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            file = current.file,
            onBack = ::navigateBack,
            )
        }
    }
    if (shouldUseNextcloudRootShell(presentation, screen == Screen.Root)) {
        RootShell(
            presentation = presentation,
            selected = destination,
            onSelected = {
                destination = it
                screen = Screen.Root
            },
            identity = desktopIdentity,
            content = screenContent,
        )
    } else {
        screenContent()
    }
}

@Composable
private fun RootShell(
    presentation: NextcloudPresentation,
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    identity: NextcloudDesktopIdentity?,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (presentation == NextcloudPresentation.Desktop) {
            NextcloudDesktopShell(
                selected = selected,
                onSelected = onSelected,
                identity = identity,
                content = content,
            )
        } else {
            val layout = resolveNextcloudRootShellLayout(
                presentation = presentation,
                availableWidthDp = maxWidth.value.toInt(),
                destination = selected,
            )
            when (layout.navigationStyle) {
                NextcloudNavigationStyle.BottomBar -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                        NextcloudBottomNavigation(selected = selected, onSelected = onSelected)
                    }
                }

                NextcloudNavigationStyle.CompactRail,
                NextcloudNavigationStyle.ExpandedSidebar,
                -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NextcloudNavigationRail(selected = selected, onSelected = onSelected)
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            val maxContentWidth = requireNotNull(layout.contentMaximumWidthDp).dp
                            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = maxContentWidth)) {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsScreen(
    serverInfo: NextcloudServerInfo?,
    error: String?,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        ProductHeader(title = "Apps", onSettings = onSettings, onSearch = onSearch)
        when {
            error != null -> ErrorMessage(error, onRetry)
            serverInfo == null -> LoadingMessage("Loading installed apps…")
            else -> {
                val apps = serverInfo.apps.filter { app ->
                    app.id != "dashboard" &&
                        (search.isBlank() || app.name.contains(search, ignoreCase = true))
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp),
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    placeholder = { Text("Find an app") },
                    singleLine = true,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                )
                if (apps.isEmpty()) {
                    EmptyMessage("No installed app matches “$search”.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(
                            start = NextcloudSpacing.XLarge,
                            top = NextcloudSpacing.Small,
                            end = NextcloudSpacing.XLarge,
                            bottom = NextcloudSpacing.XXLarge,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(apps, key = NextcloudAppEntry::id) { app ->
                            NextcloudAppTile(
                                title = app.name,
                                icon = NextcloudIcons.app(app.id),
                                supportingText = if (app.id in nativeAppIds) nativeSubtitle(app.id) else nativeFamily(app.id),
                                onClick = { onOpenApp(app) },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAppsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    serverInfo: NextcloudServerInfo?,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onBack: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var catalogFilter by remember { mutableStateOf(NativeAppCatalogFilter.All) }
    var catalogResult by remember(session) { mutableStateOf<NativeAppCatalogResult?>(null) }
    var catalogAttempt by remember(session) { mutableStateOf(0) }
    var pendingLifecycleAction by remember {
        mutableStateOf<Pair<NativeManagedApp, NativeAppLifecycleAction>?>(null)
    }
    LaunchedEffect(session, catalogAttempt) {
        catalogResult = null
        catalogResult = runCatching {
            loadNativeAppCatalog { request -> services.executeNextcloudApi(session, request) }
        }.getOrElse {
            NativeAppCatalogResult.InvalidResponse("The administrator app catalog could not be loaded.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Server apps",
            subtitle = "Administrator app management",
            onBack = onBack,
        )
        when (val result = catalogResult) {
            null -> LoadingMessage("Loading administrator app catalog…")
            is NativeAppCatalogResult.Available -> NativeAppCatalogSurface(
                catalog = result.catalog,
                query = search,
                filter = catalogFilter,
                onQueryChanged = { search = it },
                onFilterChanged = { catalogFilter = it },
                onOpenInstalledApp = { managed ->
                    serverInfo?.apps?.firstOrNull { app -> app.id == managed.id }?.let(onOpenApp)
                },
                onLifecycleAction = { app, action -> pendingLifecycleAction = app to action },
            )
            NativeAppCatalogResult.Forbidden -> ErrorMessage(
                "This account does not have permission to manage server apps.",
                onRetry = { catalogAttempt += 1 },
            )
            NativeAppCatalogResult.Unavailable -> ErrorMessage(
                "Administrator app management is unavailable on this server.",
                onRetry = { catalogAttempt += 1 },
            )
            is NativeAppCatalogResult.InvalidResponse -> ErrorMessage(
                result.reason,
                onRetry = { catalogAttempt += 1 },
            )
        }
    }

    pendingLifecycleAction?.let { (app, action) ->
        AlertDialog(
            onDismissRequest = { pendingLifecycleAction = null },
            title = { Text("${action.uiLabel()} ${app.name}?") },
            text = {
                Text(
                    when (action) {
                        NativeAppLifecycleAction.InstallAndEnable ->
                            "This downloads server-side code and enables the app for users."
                        NativeAppLifecycleAction.Enable ->
                            "This activates the app and may add navigation, jobs, and integrations for users."
                        NativeAppLifecycleAction.Disable ->
                            "This makes the app and its integrations unavailable until an administrator enables it again."
                        NativeAppLifecycleAction.Update ->
                            "The server may enter maintenance mode while the app package is updated. Do not interrupt it."
                        NativeAppLifecycleAction.Uninstall ->
                            "This removes the app package. App data retention depends on the app and is not guaranteed."
                    } + "\n\nNextcloud requires administrator password confirmation. Continue in the authenticated server administration page.",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingLifecycleAction = null }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingLifecycleAction = null
                        services.openExternalUrl(session.serverUrl.trimEnd('/') + "/index.php/settings/apps")
                        catalogAttempt += 1
                    },
                    colors = if (action == NativeAppLifecycleAction.Uninstall) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text("Continue in browser")
                }
            },
        )
    }
}

@Composable
private fun AppInfoScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    app: NextcloudAppEntry,
    serverVersion: String?,
    cachedDiscovery: DynamicDescriptorDiscovery?,
    onDiscovery: (DynamicDescriptorDiscovery) -> Unit,
    navigation: DynamicAppNavigationState,
    onNavigationChanged: (DynamicAppNavigationState) -> Unit,
    onBack: () -> Unit,
) {
    val fallback = remember(app) { buildGenericNativeFallback(app, nativeFamily(app.id)) }
    var discovery by remember(app.id, session) { mutableStateOf(cachedDiscovery) }
    var discoveryError by remember(app.id, session) { mutableStateOf<String?>(null) }
    var discoveryAttempt by remember(app.id, session) { mutableStateOf(0) }

    LaunchedEffect(app.id, session, serverVersion, discoveryAttempt) {
        if (cachedDiscovery != null) {
            discovery = cachedDiscovery
        }
        val shouldRetry = discoveryAttempt > 0 || sharedDynamicNativeMemoryCache.shouldRetryDiscovery(session, app.id) ||
            !sharedDynamicNativeMemoryCache.isDiscoveryFresh(session, app.id)
        if (!shouldRetry && cachedDiscovery != null) {
            discoveryError = null
            return@LaunchedEffect
        }
        if (!shouldRetry) discovery = null
        discoveryError = null
        runCatching { discoverDynamicAppDescriptor(services, session, app, serverVersion) }
            .onSuccess { candidate ->
                onDiscovery(candidate)
                discovery = if (
                    candidate.acquisition == DynamicDescriptorAcquisition.MetadataFallback &&
                    cachedDiscovery?.acquisition != DynamicDescriptorAcquisition.MetadataFallback
                ) {
                    cachedDiscovery
                } else {
                    candidate
                }
                sharedDynamicNativeMemoryCache.storeDiscovery(session, app.id, discovery ?: candidate)
                if (cachedDiscovery == null || candidate.acquisition != DynamicDescriptorAcquisition.MetadataFallback) {
                    discovery = candidate
                }
            }
            .onFailure { failure ->
                sharedDynamicNativeMemoryCache.markDiscoveryFailure(session, app.id)
                if (cachedDiscovery == null) {
                    discoveryError = failure.message ?: "Could not discover this app's native API."
                }
            }
    }

    val unavailableExecutor = remember {
        NativeActionExecutor {
            NativeActionExecutionResult.Failure("No schema-declared action is available.")
        }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val resolved = discovery
        val isDiscovering = resolved == null
        val discoveryMessage = when {
            discoveryError != null -> "Using metadata fallback"
            isDiscovering -> "Preparing your workspace"
            else -> "Preparing actions"
        }
        // The discovered screen owns its own contextual header. Keeping the
        // discovery header around would stack two toolbars on every native app
        // (and makes the back action ambiguous). The outer header is only needed
        // while the contract is still being resolved or when using fallback UI.
        if (resolved == null) {
            ScreenHeader(app.name, discoveryMessage, onBack)
        }
        if (isDiscovering && discoveryError == null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = NextcloudSpacing.Large)
                    .padding(top = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Discovering this app's native contract", style = MaterialTheme.typography.bodySmall)
            }
        }
        discoveryError?.let { message ->
            ErrorMessage("Dynamic contract failed: $message") { discoveryAttempt += 1 }
        }
        if (resolved == null) {
            GenericNativeAppScreen(
                schema = fallback.schema,
                view = fallback.view,
                state = fallback.state,
                actionExecutor = unavailableExecutor,
                modifier = Modifier.weight(1f),
            )
        } else {
            DynamicDiscoveredAppScreen(
                services = services,
                session = session,
                discovery = resolved,
                restoredNavigation = navigation,
                onNavigationChanged = onNavigationChanged,
                onRetryDiscovery = { discoveryAttempt += 1 },
                onExit = onBack,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DynamicDiscoveredAppScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    discovery: DynamicDescriptorDiscovery,
    restoredNavigation: DynamicAppNavigationState,
    onNavigationChanged: (DynamicAppNavigationState) -> Unit,
    onRetryDiscovery: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val descriptor = discovery.descriptor
    val schema = remember(descriptor) { descriptor.toNativeAppSchema() }
    val initialViewId = remember(descriptor, schema) {
        descriptor.planDynamicNavigation().rootDestinations.firstOrNull()?.layoutId
            ?: schema.views.firstOrNull { it.component != NativeComponent.form }?.id
            ?: schema.views.firstOrNull()?.id
    }
    // A persisted navigation snapshot can outlive the request context that
    // created it. Reject it only when its authoritative load bindings conflict
    // with the stored route parameters. A same-resource record is valid: it is
    // how the generic native fallback detail survives rotation and relaunch.
    val restoredRecordIsInvalidCollectionContext = restoredNavigation.selectedRecord?.bindingContext
        ?.any { (name, value) ->
            restoredNavigation.pathParameterValues[name]?.let { stored -> stored != value } == true
        } == true
    val restoredRecord = restoredNavigation.selectedRecord
        .takeUnless { restoredRecordIsInvalidCollectionContext }
    val restoredRecordResourceId = restoredNavigation.selectedRecordResourceId
        .takeUnless { restoredRecordIsInvalidCollectionContext }
    val restoredPathParameterValues = restoredNavigation.pathParameterValues
        .takeUnless { restoredRecordIsInvalidCollectionContext }
        .orEmpty()
    var selectedViewId by remember(descriptor) {
        mutableStateOf(
            if (restoredRecordIsInvalidCollectionContext) initialViewId
            else restoredNavigation.selectedViewId ?: initialViewId,
        )
    }
    var selectedRecord by remember(descriptor) { mutableStateOf(restoredRecord) }
    var selectedRecordResourceId by remember(descriptor) {
        mutableStateOf(restoredRecordResourceId)
    }
    var selectedPathParameterValues by remember(descriptor) {
        mutableStateOf(restoredPathParameterValues)
    }
    var navigationHistory by remember(descriptor) { mutableStateOf(restoredNavigation.history) }
    var viewState by remember(descriptor) { mutableStateOf<NativeScreenState>(NativeScreenState.Loading) }
    var recordsByResourceId by remember(descriptor) {
        mutableStateOf<Map<String, List<NativeRecord>>>(emptyMap())
    }
    var loadAttempt by remember(descriptor) { mutableStateOf(0) }
    var paginationState by remember(descriptor) { mutableStateOf<DynamicPaginationState?>(null) }
    var loadingMore by remember(descriptor) { mutableStateOf(false) }
    var loadMoreError by remember(descriptor) { mutableStateOf<String?>(null) }
    val dynamicRecoveryScope = rememberCoroutineScope()
    val dynamicPaginationScope = rememberCoroutineScope()
    val dynamicAssetCache = remember(session.serverUrl, session.loginName, descriptor.app.id) {
        DynamicArtworkMemoryCache<ImageBitmap>(
            maximumBytes = MAX_DYNAMIC_ARTWORK_DECODED_BYTES,
            sizeOf = { image ->
                image.width.toLong().coerceAtLeast(1) *
                    image.height.toLong().coerceAtLeast(1) *
                    ARGB_8888_BYTES_PER_PIXEL
            },
        )
    }
    val imageLoader = remember(services, session, descriptor.app.id, dynamicAssetCache) {
        NativeImageLoader { assetPath ->
            val cacheKey = stableDynamicAssetCacheKey(assetPath)
            dynamicAssetCache.getOrLoad(cacheKey) {
                dynamicAppAssetRequest(descriptor.app.id, assetPath)?.let { request ->
                    val firstResponse = services.executeNextcloudApi(session, request)
                    val response = if (firstResponse.status in 300..399) {
                        firstResponse.location
                            ?.let { location -> dynamicAppAssetRequest(descriptor.app.id, location) }
                            ?.let { redirectedRequest -> services.executeNextcloudApi(session, redirectedRequest) }
                            ?: firstResponse
                    } else {
                        firstResponse
                    }
                    response.takeIf {
                        it.status in 200..299 &&
                            it.contentType.isSupportedDynamicArtworkContentType()
                    }
                        ?.body
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { bytes ->
                            decodePlatformImageSampled(
                                bytes,
                                MAX_DYNAMIC_ARTWORK_DIMENSION,
                            )?.image
                        }
                }
            }
        }
    }
    val selectedView = schema.views.firstOrNull { it.id == selectedViewId }
        ?: schema.views.firstOrNull()
    val audioSourceCapability = remember(discovery) {
        descriptor.actions.firstNotNullOfOrNull { action ->
            nativeAudioSourceCapability(discovery, action)
        }
    }
    val mediaArtworkResolver = remember(discovery) {
        nativeMediaArtworkResolver(discovery)
    }
    val audioEngine = rememberPlatformAudioPlaybackEngine()
    val audioEngineState by audioEngine.state.collectAsState()
    var audioQueue by remember(descriptor) { mutableStateOf(NativeAudioQueueState()) }

    fun playCurrentAudioTrack(queue: NativeAudioQueueState) {
        val track = queue.currentTrack ?: return
        val sources = queue.tracks.mapNotNull { candidate ->
            audioSourceCapability?.source(candidate)
        }
        val source = audioSourceCapability?.source(track) ?: return
        val sourceIndex = sources.indexOfFirst { candidate -> candidate.id == source.id }
        if (sourceIndex < 0) return
        audioQueue = queue
        audioEngine.playQueue(session, sources, sourceIndex)
    }

    LaunchedEffect(audioEngineState.sourceId) {
        val sourceId = audioEngineState.sourceId ?: return@LaunchedEffect
        val index = audioQueue.tracks.indexOfFirst { track ->
            audioSourceCapability?.source(track)?.id == sourceId
        }
        if (index >= 0 && index != audioQueue.currentIndex) {
            audioQueue = audioQueue.copy(currentIndex = index)
        }
    }

    LaunchedEffect(audioEngineState.status, audioEngineState.sourceId) {
        if (audioEngineState.status == NativeAudioEngineStatus.Ended) {
            val advanced = audioQueue.next()
            if (advanced.currentTrack != null) playCurrentAudioTrack(advanced)
        }
    }

    LaunchedEffect(
        selectedViewId,
        selectedRecord,
        selectedRecordResourceId,
        selectedPathParameterValues,
        navigationHistory,
    ) {
        onNavigationChanged(
            DynamicAppNavigationState(
                selectedViewId = selectedViewId,
                selectedRecord = selectedRecord,
                selectedRecordResourceId = selectedRecordResourceId,
                pathParameterValues = selectedPathParameterValues,
                history = navigationHistory,
            ),
        )
    }

    LaunchedEffect(descriptor, selectedView?.id, selectedRecord?.id, selectedPathParameterValues, loadAttempt) {
        val view = selectedView ?: return@LaunchedEffect
        val cacheKey = dynamicScreenCacheKey(
            session = session,
            appId = descriptor.app.id,
            viewId = view.id,
            selectedRecordId = selectedRecord?.id,
            parameterValues = selectedPathParameterValues,
        )
        paginationState = null
        loadingMore = false
        loadMoreError = null
        if (view.component == NativeComponent.form) {
            val rememberedRecords = recordsByResourceId[view.resourceId].orEmpty()
            val cachedRecords = rememberedRecords.ifEmpty {
                sharedDynamicNativeMemoryCache.screen(cacheKey)?.records.orEmpty()
            }
            if (cachedRecords.isNotEmpty()) {
                recordsByResourceId = recordsByResourceId + (view.resourceId to cachedRecords)
                viewState = NativeScreenState.Ready(cachedRecords)
                return@LaunchedEffect
            }
            val prefillView = schema.settingsFormPrefillView(view)
            if (prefillView == null) {
                viewState = NativeScreenState.Ready(emptyList())
                return@LaunchedEffect
            }
            viewState = NativeScreenState.Loading
            val values = selectedRecord?.toDynamicRuntimeValues().orEmpty() + selectedPathParameterValues
            runCatching {
                loadDynamicRecords(
                    services = services,
                    session = session,
                    descriptor = descriptor,
                    actionId = prefillView.sourceActionId,
                    values = values,
                    runtimeContext = values,
                )
            }.onSuccess { records ->
                if (records.isEmpty()) {
                    viewState = NativeScreenState.Error(
                        message = "The server returned no current settings to edit.",
                        retry = { loadAttempt += 1 },
                    )
                } else {
                    val updatedRecords = recordsByResourceId + (view.resourceId to records)
                    recordsByResourceId = updatedRecords
                    viewState = NativeScreenState.Ready(records)
                    sharedDynamicNativeMemoryCache.storeScreen(
                        cacheKey,
                        DynamicScreenSnapshot(records, updatedRecords),
                    )
                }
            }.onFailure { failure ->
                viewState = NativeScreenState.Error(
                    message = failure.message ?: "Could not load the current settings.",
                    retry = { loadAttempt += 1 },
                )
            }
            return@LaunchedEffect
        }
        val freshSnapshot = if (loadAttempt == 0) {
            sharedDynamicNativeMemoryCache.screen(cacheKey, freshOnly = true)
        } else {
            null
        }
        if (freshSnapshot != null) {
            recordsByResourceId = freshSnapshot.relatedRecords
            viewState = NativeScreenState.Ready(freshSnapshot.records)
            paginationState = freshSnapshot.pagination?.let { checkpoint ->
                descriptor.actions.firstOrNull { action -> action.id == view.sourceActionId }
                    ?.dynamicPaginationSpec()
                    ?.let { spec ->
                        DynamicPaginationState(
                            viewId = view.id,
                            spec = spec,
                            nextPageNumber = checkpoint.nextPageNumber,
                            nextRequestValue = checkpoint.nextRequestValue,
                        )
                    }
            }
            return@LaunchedEffect
        }
        val staleSnapshot = if (loadAttempt == 0) sharedDynamicNativeMemoryCache.screen(cacheKey) else null
        if (staleSnapshot != null) {
            recordsByResourceId = staleSnapshot.relatedRecords
            viewState = NativeScreenState.Ready(staleSnapshot.records)
        }
        val composite = view.compositeDataGrid
        if (composite != null) {
            if (staleSnapshot == null) viewState = NativeScreenState.Loading
            val values = selectedRecord?.toDynamicRuntimeValues().orEmpty() + selectedPathParameterValues
            runCatching {
                coroutineScope {
                    listOf(
                        composite.columnResourceId to composite.columnSourceActionId,
                        composite.rowResourceId to composite.rowSourceActionId,
                    ).map { (resourceId, actionId) ->
                        async {
                            resourceId to loadDynamicRecords(
                                services = services,
                                session = session,
                                descriptor = descriptor,
                                actionId = actionId,
                                values = values,
                                runtimeContext = values,
                            )
                        }
                    }.awaitAll()
                }
            }.onSuccess { loaded ->
                val updatedRecords = recordsByResourceId + loaded.toMap()
                val rows = loaded.first { (resourceId, _) -> resourceId == composite.rowResourceId }.second
                recordsByResourceId = updatedRecords
                viewState = NativeScreenState.Ready(rows)
                sharedDynamicNativeMemoryCache.storeScreen(
                    cacheKey,
                    DynamicScreenSnapshot(rows, updatedRecords),
                )
            }.onFailure { failure ->
                viewState = staleSnapshot?.let { NativeScreenState.Ready(it.records) }
                    ?: NativeScreenState.Error(
                        message = failure.message ?: "Could not load ${view.title}.",
                        retry = { loadAttempt += 1 },
                    )
            }
            return@LaunchedEffect
        }
        if (view.sourceActionId.isBlank()) {
            val records = metadataRecordsForDynamicView(discovery, view.resourceId)
            val updatedRecords = recordsByResourceId + (view.resourceId to records)
            recordsByResourceId = updatedRecords
            viewState = NativeScreenState.Ready(records)
            sharedDynamicNativeMemoryCache.storeScreen(
                cacheKey,
                DynamicScreenSnapshot(records, updatedRecords),
            )
            return@LaunchedEffect
        }
        if (staleSnapshot == null) viewState = NativeScreenState.Loading
        val values = selectedRecord?.toDynamicRuntimeValues().orEmpty() + selectedPathParameterValues
        runCatching {
            loadDynamicRecords(
                services = services,
                session = session,
                descriptor = descriptor,
                actionId = view.sourceActionId,
                values = values,
                runtimeContext = values,
            )
        }.onSuccess { records ->
            val updatedRecords = recordsByResourceId + (view.resourceId to records)
            val nextPagination = descriptor.actions.firstOrNull { action -> action.id == view.sourceActionId }
                ?.dynamicPaginationSpec()
                ?.toDynamicPaginationState(view.id, records)
            recordsByResourceId = updatedRecords
            viewState = NativeScreenState.Ready(records)
            records.firstOrNull()?.let { authoritative ->
                if (
                    view.component == NativeComponent.detail &&
                    selectedRecord?.id == authoritative.id
                ) {
                    // Promote the freshly loaded server record over the sparse list stub. This
                    // gives contextual actions a declared identity and prefills edit forms with
                    // the complete current recipe.
                    selectedRecord = authoritative
                }
            }
            paginationState = nextPagination
            sharedDynamicNativeMemoryCache.storeScreen(
                cacheKey,
                DynamicScreenSnapshot(
                    records = records,
                    relatedRecords = updatedRecords,
                    pagination = nextPagination?.toCheckpoint(),
                ),
            )
        }.onFailure { failure ->
            if (staleSnapshot != null) {
                viewState = NativeScreenState.Ready(staleSnapshot.records)
                return@onFailure
            }
            val recoveryRequest = failure.takeIf(Throwable::isUnsynchronizedDynamicCollectionFailure)
                ?.let { buildDynamicRefreshRecoveryRequest(descriptor, values) }
            viewState = NativeScreenState.Error(
                message = failure.message ?: "Could not load ${view.title}.",
                retry = recoveryRequest?.let { request ->
                    {
                        dynamicRecoveryScope.launch {
                            viewState = NativeScreenState.Loading
                            runCatching {
                                val response = services.executeNextcloudApi(session, request)
                                check(response.acceptedDynamicRefresh()) {
                                    "Mailbox synchronization failed (HTTP ${response.status})."
                                }
                                delay(1_200)
                            }.onSuccess {
                                loadAttempt += 1
                            }.onFailure { recoveryFailure ->
                                viewState = NativeScreenState.Error(
                                    message = recoveryFailure.message ?: "Could not synchronize this mailbox.",
                                    retry = { loadAttempt += 1 },
                                )
                            }
                        }
                    }
                } ?: { loadAttempt += 1 },
                retryLabel = if (recoveryRequest == null) "Try again" else "Sync and retry",
            )
        }
    }

    if (selectedView == null) {
        val fallback = remember(descriptor.app) {
            buildGenericNativeFallback(
                NextcloudAppEntry(descriptor.app.id, descriptor.app.name, href = null),
                "No dynamic views advertised",
            )
        }
        GenericNativeAppScreen(
            schema = fallback.schema,
            view = fallback.view,
            state = NativeScreenState.Error("The discovered descriptor did not contain a renderable view."),
            actionExecutor = NativeActionExecutor {
                NativeActionExecutionResult.Failure("No action is available.")
            },
            modifier = modifier,
        )
        return
    }

    val runtimeValues = selectedRecord?.toDynamicRuntimeValues().orEmpty() + selectedPathParameterValues
    val executor = remember(services, session, descriptor, runtimeValues) {
        DynamicNextcloudActionExecutor(services, session, descriptor, runtimeValues)
    }
    val recordContext = selectedRecord?.let { record ->
        val visitedStates = buildSet {
            navigationHistory.forEach { snapshot ->
                add(
                    dynamicNavigationState(
                        resourceId = snapshot.resourceId,
                        layoutId = snapshot.viewId,
                        parameterValues = snapshot.pathParameterValues,
                    ),
                )
            }
            add(
                dynamicNavigationState(
                    resourceId = selectedView.resourceId,
                    layoutId = selectedView.id,
                    parameterValues = selectedPathParameterValues,
                ),
            )
        }
        DynamicResourceRecordContext(
            resourceId = selectedRecordResourceId.orEmpty(),
            recordId = record.id,
            fieldValues = record.values,
            parameterValues = selectedPathParameterValues,
            actionSafeIdentity = record.actionSafeIdentity,
            currentLayoutId = selectedView.id,
            visitedStates = visitedStates,
        )
    }
    val navigationPlan = remember(descriptor, recordContext) {
        descriptor.planDynamicNavigation(recordContext)
    }
    val contextDetailResolution = remember(descriptor, schema, recordContext) {
        val context = recordContext ?: return@remember null
        schema.bestDynamicDetailView(context.resourceId)?.let { view ->
            descriptor.resolveDynamicRecordReadParameters(view.sourceActionId, context)
                ?.let { parameters -> view to parameters }
        }
    }
    val contextDetailView = contextDetailResolution?.first
    val contextDetailPathParameters = contextDetailResolution?.second.orEmpty()
    val navigationDestinations = remember(
        navigationPlan,
        schema,
        contextDetailView,
        selectedRecord,
        selectedRecordResourceId,
    ) {
        if (selectedRecord == null) {
            navigationPlan.rootDestinations.mapNotNull { destination ->
                schema.views.firstOrNull { it.id == destination.layoutId }?.let { view -> destination to view }
            }
        } else {
            buildList {
                schema.views.filter { view ->
                    view.compositeDataGrid?.parentResourceId == selectedRecordResourceId
                }.forEach { view ->
                    val grid = requireNotNull(view.compositeDataGrid)
                    val sourceDestinations = navigationPlan.contextualChildDestinations.filter { destination ->
                        destination.actionId in setOf(grid.columnSourceActionId, grid.rowSourceActionId)
                    }
                    add(
                        DynamicNavigationDestination(
                            layoutId = view.id,
                            label = view.title,
                            resourceId = view.resourceId,
                            actionId = view.sourceActionId,
                            pathParameterValues = sourceDestinations
                                .flatMap { it.pathParameterValues.entries }
                                .associate(Map.Entry<String, String>::toPair),
                        ) to view,
                    )
                }
                contextDetailView?.let { view ->
                    add(
                        DynamicNavigationDestination(
                            layoutId = view.id,
                            label = "Overview",
                            resourceId = view.resourceId,
                            actionId = view.sourceActionId,
                            pathParameterValues = contextDetailPathParameters,
                        ) to view,
                    )
                }
                navigationPlan.contextualChildDestinations
                    .filterNot { destination ->
                        selectedRecordResourceId.isDynamicMessageResource() &&
                            destination.resourceId.isMailNavigationAncestor()
                    }
                    .forEach { destination ->
                    schema.views.firstOrNull { it.id == destination.layoutId }?.let { view -> add(destination to view) }
                }
            }
        }
    }
    val secondaryNavigationDestinations = remember(
        descriptor,
        recordContext,
        navigationDestinations,
    ) {
        val context = recordContext ?: return@remember emptyList()
        navigationDestinations.filter { (destination, _) ->
            descriptor.isSecondaryTechnicalDestination(context, destination)
        }
    }
    val primaryNavigationDestinations = remember(
        navigationDestinations,
        secondaryNavigationDestinations,
    ) {
        val secondaryViewIds = secondaryNavigationDestinations.mapTo(hashSetOf()) { (_, view) -> view.id }
        navigationDestinations.filterNot { (_, view) -> view.id in secondaryViewIds }
    }
    val actionViews = remember(navigationPlan, schema, selectedRecord, selectedView.resourceId) {
        val planned = if (selectedRecord == null) {
            navigationPlan.rootFormActions.filter { action ->
                action.resourceId == selectedView.resourceId
            }
        } else {
            val currentResourceId = selectedRecordResourceId.orEmpty()
            navigationPlan.contextualFormActions.filter { action ->
                val spec = schema.action(action.actionId)
                val targetsCurrentRecord = action.resourceId.sameDynamicResourceAs(currentResourceId)
                val targetsCurrentView = action.resourceId.sameDynamicResourceAs(selectedView.resourceId)
                val createsCurrentViewResource = spec?.intent == ActionIntent.create && targetsCurrentView
                val editsSelectedRecord = spec?.intent in setOf(ActionIntent.update, ActionIntent.delete) &&
                    targetsCurrentRecord &&
                    (targetsCurrentView || selectedView.component == NativeComponent.detail)
                // Create actions belong to the active collection tab. Update and
                // delete actions belong to the selected record and stay visible
                // on its detail surface, not on unrelated child collections.
                createsCurrentViewResource || editsSelectedRecord
            }
        }
        planned.mapNotNull { action ->
            schema.views.firstOrNull { it.id == action.formId }?.let { view -> action to view }
        }.distinctBy { (action, view) ->
            // Contracts from apps such as Cospend can expose the same semantic
            // action through several resource-specific form routes. Keep one
            // menu item per user-facing action instead of rendering a long list
            // of duplicate Edit/Delete entries.
            val spec = schema.action(action.actionId)
            val label = (spec?.let { dynamicHeaderActionLabel(it, view.dynamicActionLabel()) }
                ?: view.dynamicActionLabel()).trim().lowercase()
            // Deduplicate aliases only when they resolve to the same semantic
            // operation. Distinct HTTP routes remain available to the user.
            val route = spec?.let { "${it.binding.method}:${it.binding.path}" } ?: view.id
            "$label|$route"
        }
    }
    val quickActionViews = remember(actionViews, schema) {
        actionViews.sortedBy { (action, _) ->
            dynamicQuickActionPriority(schema.action(action.actionId))
        }.take(2)
    }
    var actionMenuExpanded by remember(descriptor) { mutableStateOf(false) }
    var advancedNavigationExpanded by remember(descriptor, recordContext) { mutableStateOf(false) }
    var pendingDirectAction by remember(descriptor) {
        mutableStateOf<PendingDynamicDirectAction?>(null)
    }
    var directActionRunning by remember(descriptor) { mutableStateOf(false) }
    var directActionError by remember(descriptor) { mutableStateOf<String?>(null) }
    var contractInfoExpanded by remember(descriptor) { mutableStateOf(false) }
    val contractInfo = remember(discovery, recordContext) { discovery.toContractInfo(recordContext) }
    val dynamicActionScope = rememberCoroutineScope()

    val activePagination = paginationState?.takeIf { pagination -> pagination.viewId == selectedView.id }
    val onLoadMore = activePagination?.let { pagination ->
        {
            if (!loadingMore) {
                loadingMore = true
                loadMoreError = null
                val pagingView = selectedView
                val existingRecords = (viewState as? NativeScreenState.Ready)?.records.orEmpty()
                val values = selectedRecord?.toDynamicRuntimeValues().orEmpty() +
                    selectedPathParameterValues +
                    (pagination.spec.parameterName to pagination.nextRequestValue)
                dynamicPaginationScope.launch {
                    runCatching {
                        loadDynamicRecords(
                            services = services,
                            session = session,
                            descriptor = descriptor,
                            actionId = pagingView.sourceActionId,
                            values = values,
                            runtimeContext = values,
                        )
                    }.onSuccess { pageRecords ->
                        if (selectedViewId != pagingView.id) return@onSuccess
                        val existingIds = existingRecords.mapTo(hashSetOf(), NativeRecord::id)
                        val novelRecords = pageRecords.distinctBy(NativeRecord::id)
                            .filterNot { record -> record.id in existingIds }
                        val mergedRecords = existingRecords + novelRecords
                        recordsByResourceId = recordsByResourceId + (pagingView.resourceId to mergedRecords)
                        viewState = NativeScreenState.Ready(mergedRecords)
                        paginationState = pagination.spec.toDynamicPaginationState(
                            viewId = pagingView.id,
                            lastPage = pageRecords,
                            loadedRecordCount = mergedRecords.size,
                            novelRecordCount = novelRecords.size,
                            nextPageNumber = pagination.nextPageNumber + 1,
                        )
                        sharedDynamicNativeMemoryCache.storeScreen(
                            dynamicScreenCacheKey(
                                session = session,
                                appId = descriptor.app.id,
                                viewId = pagingView.id,
                                selectedRecordId = selectedRecord?.id,
                                parameterValues = selectedPathParameterValues,
                            ),
                            DynamicScreenSnapshot(
                                records = mergedRecords,
                                relatedRecords = recordsByResourceId,
                                pagination = paginationState?.toCheckpoint(),
                            ),
                        )
                        loadingMore = false
                    }.onFailure { failure ->
                        if (selectedViewId != pagingView.id) return@onFailure
                        loadMoreError = failure.message ?: "Could not load the next page."
                        loadingMore = false
                    }
                }
            }
        }
    }

    fun rememberCurrentLocation() {
        navigationHistory = navigationHistory + DynamicNavigationSnapshot(
            viewId = selectedView.id,
            resourceId = selectedView.resourceId,
            record = selectedRecord,
            recordResourceId = selectedRecordResourceId,
            pathParameterValues = selectedPathParameterValues,
        )
    }

    fun navigateWithinDynamicApp() {
        navigationHistory.lastOrNull()?.let { previous ->
            navigationHistory = navigationHistory.dropLast(1)
            selectedViewId = previous.viewId
            selectedRecord = previous.record
            selectedRecordResourceId = previous.recordResourceId
            selectedPathParameterValues = previous.pathParameterValues
            return
        }
        val contextResource = selectedRecordResourceId
        if (selectedRecord != null && contextResource != null && selectedView.resourceId != contextResource) {
            selectedViewId = schema.views.firstOrNull { view ->
                view.resourceId == contextResource && view.component == NativeComponent.detail
            }?.id ?: initialViewId
            selectedPathParameterValues = emptyMap()
            return
        }
        if (selectedRecord != null) {
            selectedRecord = null
            selectedRecordResourceId = null
            selectedPathParameterValues = emptyMap()
            selectedViewId = initialViewId
            return
        }
        if (selectedViewId != initialViewId) {
            selectedViewId = initialViewId
            return
        }
        onExit()
    }

    fun selectDynamicAction(
        action: dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationFormAction,
        view: ViewSpec,
    ) {
        actionMenuExpanded = false
        val actionSpec = schema.action(action.actionId)
        val editableFieldCount = actionSpec?.let { spec ->
            schema.resource(spec.resourceId)?.let { resource ->
                editableNativeFields(resource, spec).size
            }
        } ?: 0
        if (
            actionSpec != null &&
            dynamicActionUiMode(actionSpec, editableFieldCount) == DynamicActionUiMode.ConfirmDirectly
        ) {
            val label = selectedRecord?.values?.entries?.firstOrNull { (key, value) ->
                key.lowercase().filter(Char::isLetterOrDigit) in setOf("name", "title", "displayname") &&
                    !value.isNullOrBlank()
            }?.value ?: selectedRecord?.id ?: schema.resource(actionSpec.resourceId)?.name ?: "item"
            directActionError = null
            pendingDirectAction = PendingDynamicDirectAction(
                action = actionSpec,
                values = action.pathParameterValues,
                targetLabel = label,
            )
            return
        }
        rememberCurrentLocation()
        selectedPathParameterValues = action.pathParameterValues
        selectedViewId = view.id
    }

    val hasInternalBack = navigationHistory.isNotEmpty() || selectedRecord != null || selectedViewId != initialViewId
    PlatformBackHandler(enabled = hasInternalBack, onBack = ::navigateWithinDynamicApp)
    val showFallbackRecordDetail = shouldShowDynamicRecordFallbackDetail(
        viewResourceId = selectedView.resourceId,
        viewComponent = selectedView.component,
        selectedRecord = selectedRecord,
        selectedRecordResourceId = selectedRecordResourceId,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactLandscape = maxWidth > maxHeight && maxHeight < 600.dp
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = descriptor.app.name,
                subtitle = selectedRecord?.dynamicContextSubtitle(
                    selectedView,
                    schema.resource(selectedRecordResourceId.orEmpty())?.name,
                ) ?: selectedView.dynamicRootSubtitle(descriptor.app.name),
                onBack = ::navigateWithinDynamicApp,
                compact = compactLandscape,
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (actionViews.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { actionMenuExpanded = true }) {
                                    Icon(NextcloudIcons.More, contentDescription = "Available actions")
                                }
                                DropdownMenu(
                                    expanded = actionMenuExpanded,
                                    onDismissRequest = { actionMenuExpanded = false },
                                ) {
                                    actionViews.forEach { (action, view) ->
                                        val actionSpec = schema.action(action.actionId)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    actionSpec?.let { spec ->
                                                        dynamicHeaderActionLabel(spec, view.dynamicActionLabel())
                                                    } ?: view.dynamicActionLabel(),
                                                )
                                            },
                                            onClick = { selectDynamicAction(action, view) },
                                        )
                                    }
                                }
                            }
                        }
                        if (compactLandscape) {
                            TextButton(onClick = { contractInfoExpanded = true }) { Text("Contract") }
                        }
                    }
                },
            )
            if (!compactLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { contractInfoExpanded = true }) {
                        Text("Contract info")
                    }
                }
            }
            if (discovery.acquisition == DynamicDescriptorAcquisition.MetadataFallback) {
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
                    Text("No verified native API", style = MaterialTheme.typography.titleSmall)
                    Text(
                        discovery.diagnostics.lastOrNull()
                            ?: "No usable API contract or verified static read routes were found for this app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetryDiscovery) {
                        Text("Retry discovery")
                    }
                }
            }
        }
            if (primaryNavigationDestinations.size > 1 || secondaryNavigationDestinations.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = NextcloudSpacing.Large,
                        end = NextcloudSpacing.Small,
                        top = NextcloudSpacing.Small,
                        bottom = NextcloudSpacing.Small,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    listItems(primaryNavigationDestinations, key = { (_, view) -> view.id }) { (destination, view) ->
                        FilterChip(
                            selected = view.id == selectedView.id,
                            onClick = {
                                selectedPathParameterValues = destination.pathParameterValues
                                selectedViewId = view.id
                            },
                            label = { Text(destination.label.dynamicUiLabel(descriptor.app.name)) },
                        )
                    }
                }
                if (secondaryNavigationDestinations.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { advancedNavigationExpanded = true }) {
                            Icon(NextcloudIcons.More, contentDescription = "Advanced views")
                        }
                        DropdownMenu(
                            expanded = advancedNavigationExpanded,
                            onDismissRequest = { advancedNavigationExpanded = false },
                        ) {
                            secondaryNavigationDestinations.forEach { (destination, view) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(destination.label.dynamicUiLabel(descriptor.app.name))
                                    },
                                    onClick = {
                                        selectedPathParameterValues = destination.pathParameterValues
                                        selectedViewId = view.id
                                        advancedNavigationExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
            if (!compactLandscape && quickActionViews.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = NextcloudSpacing.Large,
                        end = NextcloudSpacing.Large,
                        bottom = NextcloudSpacing.Small,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    listItems(quickActionViews, key = { (action, view) -> "${action.actionId}:${view.id}" }) {
                            (action, view) ->
                        val actionSpec = schema.action(action.actionId)
                        val label = actionSpec?.let { spec ->
                            dynamicHeaderActionLabel(spec, view.dynamicActionLabel())
                        } ?: view.dynamicActionLabel()
                        if (actionSpec?.intent == ActionIntent.create) {
                            Button(onClick = { selectDynamicAction(action, view) }) {
                                Text(label)
                            }
                        } else {
                            OutlinedButton(onClick = { selectDynamicAction(action, view) }) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            GenericNativeAppScreen(
                schema = schema,
                view = selectedView,
                state = viewState,
                actionExecutor = executor,
                selectedRecordId = selectedRecord?.id,
                showSelectedRecordDetail = showFallbackRecordDetail,
                datasetContext = NativeDatasetContext(
                    parentResourceId = selectedRecordResourceId,
                    parentRecord = selectedRecord,
                    relatedRecords = recordsByResourceId,
                ),
            onSelectRecord = selectedView.takeIf {
                it.component != NativeComponent.detail && it.component != NativeComponent.form
            }?.let {
                { record ->
                    rememberCurrentLocation()
                    val selectedParentResourceId = record.effectiveNativeResourceId(selectedView.resourceId)
                    val inheritedParameters = inheritDynamicParentParameters(
                        selectedPathParameterValues = selectedPathParameterValues,
                        runtimeValues = runtimeValues,
                    )
                    val nextContext = DynamicResourceRecordContext(
                        resourceId = selectedParentResourceId,
                        recordId = record.id,
                        fieldValues = record.values,
                        parameterValues = inheritedParameters,
                        actionSafeIdentity = record.actionSafeIdentity,
                        currentLayoutId = selectedView.id,
                    )
                    val nextPlan = descriptor.planDynamicNavigation(nextContext)
                    val compositeTarget = schema.views.firstOrNull { candidate ->
                        candidate.compositeDataGrid?.parentResourceId == selectedParentResourceId
                    }
                    val compositeActionIds = compositeTarget?.compositeDataGrid?.let { grid ->
                        setOf(grid.columnSourceActionId, grid.rowSourceActionId)
                    }.orEmpty()
                    val detailResolution = schema.bestDynamicDetailView(selectedParentResourceId)
                        ?.takeIf { target -> target.id != selectedView.id }
                        ?.let { target ->
                            descriptor.resolveDynamicRecordReadParameters(target.sourceActionId, nextContext)
                                ?.let { parameters -> target to parameters }
                    }
                    val detailTarget = detailResolution?.first
                    val directChild = descriptor.singleSafeContextualChild(
                        context = nextContext,
                        hasDedicatedSurface = compositeTarget != null || detailTarget != null,
                    )
                    val preferredCollectionChild = descriptor.preferredSemanticContextualChild(nextContext)
                    val primaryContentTarget = primaryDynamicContentDestination(
                        parentResourceId = selectedParentResourceId,
                        destinations = nextPlan.contextualChildDestinations,
                    )
                    val nextViewId = compositeTarget?.id
                        ?: primaryContentTarget?.layoutId
                        ?: preferredCollectionChild?.layoutId
                        ?: detailTarget?.id
                        ?: directChild?.layoutId
                        ?: selectedViewId
                    val explicitTargetParameters = primaryContentTarget?.pathParameterValues
                        ?: preferredCollectionChild?.pathParameterValues
                        ?: directChild?.pathParameterValues
                        ?: detailResolution?.second
                    val fallbackTargetParameters = inheritedParameters +
                        nextPlan.contextualChildDestinations
                            .filter { destination -> destination.actionId in compositeActionIds }
                            .flatMap { destination -> destination.pathParameterValues.entries }
                            .associate(Map.Entry<String, String>::toPair)
                    selectedRecord = record
                    selectedRecordResourceId = selectedParentResourceId
                    selectedPathParameterValues = resolveDynamicRecordSelectionParameters(
                        currentViewId = selectedViewId.orEmpty(),
                        nextViewId = nextViewId.orEmpty(),
                        currentParameters = selectedPathParameterValues,
                        explicitTargetParameters = explicitTargetParameters,
                        fallbackTargetParameters = fallbackTargetParameters,
                    )
                    selectedViewId = nextViewId
                }
                },
                onActionSucceeded = {
                    if (schema.action(selectedView.sourceActionId)?.intent == ActionIntent.delete) {
                        navigationHistory = emptyList()
                        selectedRecord = null
                        selectedRecordResourceId = null
                        selectedPathParameterValues = emptyMap()
                        selectedViewId = initialViewId
                    } else {
                        // Update/config actions return to their previous surface, which immediately
                        // reloads the authoritative server representation through loadAttempt.
                        navigateWithinDynamicApp()
                    }
                    loadAttempt += 1
                },
                onInlineActionSucceeded = { loadAttempt += 1 },
                onOpenLink = services::openExternalUrl,
                imageLoader = imageLoader,
                audioPlayer = audioSourceCapability?.let {
                    NativeAudioRecordPlayer { resource, records, selected, collectionContext ->
                        val queue = startNativeAudioQueue(
                            tracks = records.mapNotNull { record ->
                                nativeAudioTrack(resource, record, collectionContext)
                            },
                            selectedRecordId = selected.id,
                        )
                        playCurrentAudioTrack(queue)
                    }
                },
                mediaArtworkResolver = mediaArtworkResolver,
                onLoadMore = onLoadMore.takeUnless { showFallbackRecordDetail },
                loadingMore = loadingMore,
                loadMoreError = loadMoreError,
                modifier = Modifier.weight(1f),
            )
            if (audioSourceCapability != null && audioQueue.currentTrack != null) {
                NativeAudioMiniPlayer(
                    queue = audioQueue,
                    engineState = audioEngineState,
                    artworkRelativePath = audioQueue.currentTrack
                        ?.let { track -> audioSourceCapability.source(track)?.artworkRelativePath },
                    imageLoader = imageLoader,
                    onPrevious = {
                        if (audioEngineState.positionMillis > AUDIO_PREVIOUS_RESTART_THRESHOLD_MILLIS) {
                            audioEngine.seekTo(0)
                        } else {
                            playCurrentAudioTrack(audioQueue.previous())
                        }
                    },
                    onTogglePlayback = {
                        when (audioEngineState.status) {
                            NativeAudioEngineStatus.Playing -> audioEngine.pause()
                            NativeAudioEngineStatus.Loading -> audioEngine.stop()
                            NativeAudioEngineStatus.Paused -> audioEngine.resume()
                            NativeAudioEngineStatus.Ended,
                            NativeAudioEngineStatus.Error,
                            NativeAudioEngineStatus.Idle,
                            -> playCurrentAudioTrack(audioQueue)
                        }
                    },
                    onNext = {
                        val advanced = audioQueue.next()
                        if (advanced.currentTrack != null) playCurrentAudioTrack(advanced)
                    },
                    onSelectTrack = { index ->
                        playCurrentAudioTrack(audioQueue.copy(currentIndex = index))
                    },
                    onSeek = audioEngine::seekTo,
                    onStop = {
                        audioEngine.stop()
                        audioQueue = NativeAudioQueueState()
                    },
                )
            }
        }
    }
    if (contractInfoExpanded) {
        DynamicContractInfoDialog(
            info = contractInfo,
            onDismiss = { contractInfoExpanded = false },
        )
    }
    pendingDirectAction?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                if (!directActionRunning) {
                    pendingDirectAction = null
                    directActionError = null
                }
            },
            title = { Text("Delete ${pending.targetLabel}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text("This removes the item from the server and cannot be undone.")
                    directActionError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !directActionRunning,
                    onClick = {
                        pendingDirectAction = null
                        directActionError = null
                    },
                ) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    enabled = !directActionRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        directActionRunning = true
                        directActionError = null
                        dynamicActionScope.launch {
                            when (
                                val result = executor.execute(
                                    NativeActionRequest.Submit(
                                        action = pending.action,
                                        values = pending.values,
                                        confirmed = true,
                                    ),
                                )
                            ) {
                                is NativeActionExecutionResult.Success -> {
                                    pendingDirectAction = null
                                    navigationHistory = emptyList()
                                    selectedRecord = null
                                    selectedRecordResourceId = null
                                    selectedPathParameterValues = emptyMap()
                                    selectedViewId = initialViewId
                                    loadAttempt += 1
                                }
                                is NativeActionExecutionResult.Failure -> {
                                    directActionError = result.message
                                }
                            }
                            directActionRunning = false
                        }
                    },
                ) {
                    if (directActionRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Delete")
                    }
                }
            },
        )
    }
}

@Composable
private fun NativeAudioMiniPlayer(
    queue: NativeAudioQueueState,
    engineState: NativeAudioEngineState,
    artworkRelativePath: String?,
    imageLoader: NativeImageLoader?,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onSelectTrack: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    val track = queue.currentTrack ?: return
    val duration = engineState.durationMillis ?: track.durationMillis
    var queueExpanded by remember(queue.tracks) { mutableStateOf(false) }
    val artwork by produceState<ImageBitmap?>(
        initialValue = null,
        artworkRelativePath,
        imageLoader,
    ) {
        value = if (artworkRelativePath != null && imageLoader != null) {
            imageLoader.load(artworkRelativePath)
        } else {
            null
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Large,
                vertical = NextcloudSpacing.Small,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    color = NextcloudTheme.colors.appIconContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    if (artwork != null) {
                        Image(
                            bitmap = artwork!!,
                            contentDescription = "Album artwork",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                NextcloudIcons.Play,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).clickable {
                        queueExpanded = !queueExpanded
                    },
                ) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    listOfNotNull(track.artist, track.album).distinct().joinToString(" · ")
                        .takeIf(String::isNotBlank)
                        ?.let { subtitle ->
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                }
                Text(
                    "${(queue.currentIndex ?: 0) + 1}/${queue.tracks.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrevious) {
                    Icon(NextcloudIcons.SkipPrevious, contentDescription = "Previous track")
                }
                IconButton(onClick = onTogglePlayback) {
                    if (engineState.status == NativeAudioEngineStatus.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (engineState.status == NativeAudioEngineStatus.Playing) {
                                NextcloudIcons.Pause
                            } else {
                                NextcloudIcons.Play
                            },
                            contentDescription = if (engineState.status == NativeAudioEngineStatus.Playing) {
                                "Pause"
                            } else {
                                "Play"
                            },
                        )
                    }
                }
                IconButton(
                    onClick = onNext,
                    enabled = (queue.currentIndex ?: 0) < queue.tracks.lastIndex,
                ) {
                    Icon(NextcloudIcons.SkipNext, contentDescription = "Next track")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { queueExpanded = !queueExpanded }) {
                    Text(if (queueExpanded) "Hide queue" else "Queue")
                }
                TextButton(onClick = onStop) {
                    Text("Stop")
                }
            }
            if (duration != null && duration > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Text(
                        formatAudioPosition(engineState.positionMillis),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = engineState.positionMillis.coerceIn(0, duration).toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatAudioPosition(duration), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (queueExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 224.dp),
                ) {
                    indexedListItems(
                        items = queue.tracks,
                        key = { _, queuedTrack -> queuedTrack.recordId },
                    ) { index, queuedTrack ->
                        val selected = index == queue.currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTrack(index) }
                                .padding(
                                    horizontal = NextcloudSpacing.Small,
                                    vertical = NextcloudSpacing.Small,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        ) {
                            Text(
                                (index + 1).toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    queuedTrack.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                listOfNotNull(queuedTrack.artist, queuedTrack.album)
                                    .distinct()
                                    .joinToString(" · ")
                                    .takeIf(String::isNotBlank)
                                    ?.let { subtitle ->
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                            }
                            queuedTrack.durationMillis?.let { trackDuration ->
                                Text(
                                    formatAudioPosition(trackDuration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            engineState.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatAudioPosition(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val AUDIO_PREVIOUS_RESTART_THRESHOLD_MILLIS = 3_000L

/** Exact resources beat semantic singular/plural aliases such as random `album` versus `albums`. */
internal fun dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema.bestDynamicDetailView(
    resourceId: String,
): ViewSpec? = views.asSequence()
    .filter { view ->
        view.component == NativeComponent.detail && view.sourceActionId.isNotBlank() &&
            view.resourceId.sameDynamicResourceAs(resourceId)
    }
    .sortedWith(
        compareByDescending<ViewSpec> { view -> view.resourceId == resourceId }
            .thenByDescending { view -> action(view.sourceActionId)?.binding?.requiredPathParameterNames?.isNotEmpty() == true }
            .thenBy(ViewSpec::id),
    )
    .firstOrNull()

internal fun dynamicAppAssetRequest(appId: String, assetPath: String): NextcloudApiRequest? {
    if (appId.isBlank() || appId.any { !it.isLetterOrDigit() && it !in setOf('_', '-') }) return null
    if ('#' in assetPath) return null
    val path = assetPath.substringBefore('?')
    val allowed = path.startsWith("/apps/$appId/") || path.startsWith("/index.php/apps/$appId/")
    if (!allowed) return null
    val query = assetPath.substringAfter('?', "")
    val parameters = if (query.isBlank()) {
        emptyMap()
    } else {
        val pairs = query.split('&')
        if (pairs.size > 8) return null
        buildMap {
            pairs.forEach { pair ->
                val separator = pair.indexOf('=')
                if (separator !in 1 until pair.lastIndex) return null
                val key = pair.take(separator)
                val value = pair.drop(separator + 1)
                if (!key.isSafeDynamicAssetQueryPart() || !value.isSafeDynamicAssetQueryPart()) return null
                if (put(key, value) != null) return null
            }
        }
    }
    return runCatching { NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = path,
        queryParameters = parameters,
        ocsApiRequest = true,
        maximumResponseBytes = 8L * 1024L * 1024L,
    ).requireSafe() }.getOrNull()
}

internal fun stableDynamicAssetCacheKey(assetPath: String): String {
    val pathSegments = assetPath.substringBefore('?')
        .split('/')
        .filter(String::isNotBlank)
        .let { segments -> if (segments.firstOrNull() == "index.php") segments.drop(1) else segments }
    val query = assetPath.substringAfter('?', "")
        .split('&')
        .filter(String::isNotBlank)
        .sorted()
        .joinToString("&")
    val path = pathSegments.joinToString("/", prefix = "/")
    return if (query.isBlank()) path else "$path?$query"
}

private fun String.isSafeDynamicAssetQueryPart(): Boolean =
    length in 1..128 && all { character ->
        character.isLetterOrDigit() || character in setOf('-', '_', '.', '~')
    }

internal fun String?.isSupportedDynamicArtworkContentType(): Boolean {
    val mime = this?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return mime.startsWith("image/") || mime == "application/octet-stream"
}

private const val MAX_DYNAMIC_ARTWORK_DECODED_BYTES = 32L * 1024L * 1024L
private const val MAX_DYNAMIC_ARTWORK_DIMENSION = 1_024
private const val ARGB_8888_BYTES_PER_PIXEL = 4L

@Composable
private fun DynamicContractInfoDialog(
    info: DynamicContractInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contract info") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                ContractInfoSection("Acquisition", info.acquisition)
                ContractInfoSection("App version", info.appVersion)
                ContractInfoSection("Source spec", info.sourceSpecFile)
                ContractInfoSection("Descriptor", info.countSummary())
                ContractInfoSection("Resources", info.resourceIds.safeContractList())
                ContractInfoSection("Layouts", info.layoutIds.safeContractList())
                ContractInfoSection("Links", info.linkIds.safeContractList())
                ContractInfoSection("Actions", info.actionIds.safeContractList())
                ContractInfoSection(
                    "Diagnostics",
                    info.diagnosticCodes.ifEmpty { listOf("none") }.joinToString(", "),
                )
                if (info.childCandidates.isNotEmpty()) {
                    Text("Current record navigation", style = MaterialTheme.typography.titleSmall)
                    info.childCandidates.forEach { child ->
                        val missing = child.missingContextParameters.takeIf(List<String>::isNotEmpty)
                            ?.joinToString(", ", prefix = " · needs ")
                            .orEmpty()
                        Text(
                            "${child.resourceId} · ${child.reasonLabel()}$missing",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    ContractInfoSection("Current record navigation", "No child collection candidates")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ContractInfoSection(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun List<String>.safeContractList(): String = ifEmpty { listOf("none") }.joinToString(", ")

@Serializable
private data class DynamicNavigationSnapshot(
    val viewId: String,
    val resourceId: String,
    val record: NativeRecord?,
    val recordResourceId: String?,
    val pathParameterValues: Map<String, String>,
)

@Serializable
private data class DynamicAppNavigationState(
    val selectedViewId: String? = null,
    val selectedRecord: NativeRecord? = null,
    val selectedRecordResourceId: String? = null,
    val pathParameterValues: Map<String, String> = emptyMap(),
    val history: List<DynamicNavigationSnapshot> = emptyList(),
)

private data class DynamicPaginationState(
    val viewId: String,
    val spec: DynamicPaginationSpec,
    val nextPageNumber: Int = 2,
    val nextRequestValue: String,
)

private fun DynamicPaginationState.toCheckpoint(): DynamicPaginationCheckpoint = DynamicPaginationCheckpoint(
    nextPageNumber = nextPageNumber,
    nextRequestValue = nextRequestValue,
)

private fun DynamicPaginationSpec.toDynamicPaginationState(
    viewId: String,
    lastPage: List<NativeRecord>,
    loadedRecordCount: Int = lastPage.size,
    novelRecordCount: Int = lastPage.size,
    nextPageNumber: Int = 2,
): DynamicPaginationState? {
    if (!canContinue(lastPage.size, novelRecordCount)) return null
    val nextValue = nextValue(nextPageNumber, loadedRecordCount, lastPage) ?: return null
    return DynamicPaginationState(viewId, this, nextPageNumber, nextValue)
}

private fun String.dynamicUiLabel(appName: String): String {
    val cleaned = removePrefix("API ").removePrefix("Api ").removePrefix("api ").trim()
    return if (cleaned.equals("general", ignoreCase = true)) appName else cleaned
}

private fun String.dynamicResourceWords(): Set<String> = lowercase()
    .map { character -> if (character.isLetterOrDigit()) character else ' ' }
    .joinToString("")
    .split(' ')
    .filter(String::isNotBlank)
    .toSet()

private fun String?.isDynamicMessageResource(): Boolean = this?.dynamicResourceWords().orEmpty().any { word ->
    word in setOf("message", "messages", "email", "emails", "thread", "threads")
}

internal fun primaryDynamicContentDestination(
    parentResourceId: String,
    destinations: List<DynamicNavigationDestination>,
): DynamicNavigationDestination? {
    val parentWords = parentResourceId.dynamicResourceWords()
    return when {
        parentResourceId.isDynamicMessageResource() -> destinations.firstOrNull { destination ->
            destination.resourceId.dynamicResourceWords().any { word ->
                word in setOf("body", "content", "messagebody", "htmlbody")
            }
        }
        parentWords.any { it in setOf("board", "boards", "kanban", "workflow") } ->
            destinations.firstOrNull { destination ->
                destination.resourceId.dynamicResourceWords().any { word ->
                    word in setOf("stack", "stacks", "lane", "lanes", "column", "columns")
                }
            }
        parentWords.any { it in setOf("project", "projects", "budget", "budgets", "ledger", "ledgers") } ->
            destinations.firstOrNull { destination ->
                destination.resourceId.dynamicResourceWords().any { word ->
                    word in setOf(
                        "bill",
                        "bills",
                        "expense",
                        "expenses",
                        "transaction",
                        "transactions",
                        "entry",
                        "entries",
                    )
                }
            }
        else -> null
    }
}

private fun String.isMailNavigationAncestor(): Boolean = dynamicResourceWords().any { word ->
    word in setOf(
        "account", "accounts", "alias", "aliases", "mailbox", "mailboxes",
        "certificate", "certificates", "internaladdress", "internaladdresses",
    )
}

private fun NativeRecord.dynamicContextSubtitle(view: ViewSpec, resourceName: String?): String {
    val title = listOf("name", "title", "displayName", "subject", "what", "merchant", "label", "description")
        .firstNotNullOfOrNull { key ->
            (displayValues[key] ?: values[key])?.takeIf(String::isNotBlank)
        }
        ?: id
    val section = view.dynamicNavigationLabel(resourceName.orEmpty()).takeIf(String::isNotBlank)
    return listOfNotNull(title, section?.takeUnless { it.equals(title, ignoreCase = true) }).joinToString(" · ")
}

private fun ViewSpec.dynamicRootSubtitle(appName: String): String = when (component) {
    NativeComponent.form -> dynamicActionLabel()
    NativeComponent.detail -> "Overview"
    else -> dynamicNavigationLabel(appName)
}

private fun ViewSpec.dynamicNavigationLabel(appName: String): String {
    val cleaned = title
        .removePrefix("API ")
        .removePrefix("Api ")
        .removePrefix("api ")
        .trim()
    return if (cleaned.equals("general", ignoreCase = true)) appName else cleaned
}

private fun ViewSpec.dynamicActionLabel(): String = title
    .replace(Regex("^\\[api\\s+v?[0-9.]+]\\s*", RegexOption.IGNORE_CASE), "")
    .trim()
    .replaceFirstChar { character -> character.titlecase() }

private fun ViewSpec.dynamicActionMenuKey(): String = dynamicActionLabel()
    .lowercase()
    .replace(" a ", " ")
    .replace(" an ", " ")
    .replace(Regex("\\s+"), " ")

private fun metadataRecordsForDynamicView(
    discovery: DynamicDescriptorDiscovery,
    resourceId: String,
): List<NativeRecord> {
    if (resourceId != "app-metadata") return emptyList()
    val app = discovery.descriptor.app
    return listOf(
        NativeRecord(
            id = app.id,
            values = mapOf("id" to app.id, "name" to app.name, "version" to app.version),
        ),
    )
}

private fun NativeRecord.toDynamicRuntimeValues(): Map<String, String> = buildMap {
    putAll(actionBindingValues(allowUnsafeIdentity = true))
}

internal fun inheritDynamicParentParameters(
    selectedPathParameterValues: Map<String, String>,
    runtimeValues: Map<String, String>,
): Map<String, String> = selectedPathParameterValues
    .filterKeys { key -> !key.equals("id", ignoreCase = true) } +
    runtimeValues.filterKeys { key ->
        !key.equals("id", ignoreCase = true) && key.endsWith("Id", ignoreCase = true)
    }

/**
 * Selecting a record without a destination keeps the current collection on screen. Its path
 * bindings still belong to that collection and must survive the selection. Otherwise a child's
 * generic `id` can replace the parent's generic `id` when the collection reloads.
 */
internal fun resolveDynamicRecordSelectionParameters(
    currentViewId: String,
    nextViewId: String,
    currentParameters: Map<String, String>,
    explicitTargetParameters: Map<String, String>?,
    fallbackTargetParameters: Map<String, String>,
): Map<String, String> = explicitTargetParameters
    ?: if (nextViewId == currentViewId) currentParameters else fallbackTargetParameters

internal fun shouldShowDynamicRecordFallbackDetail(
    viewResourceId: String,
    viewComponent: NativeComponent,
    selectedRecord: NativeRecord?,
    selectedRecordResourceId: String?,
): Boolean = selectedRecord != null &&
    viewComponent != NativeComponent.detail &&
    viewComponent != NativeComponent.form &&
    selectedRecordResourceId?.sameDynamicResourceAs(viewResourceId) == true

@Composable
private fun ActivityScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    activityInstalled: Boolean,
    installedApps: List<NextcloudAppEntry>,
    onApps: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    var timeline by remember(session, activityInstalled) { mutableStateOf(ActivityTimelineState()) }
    var loadAttempt by remember(session, activityInstalled) { mutableStateOf(0) }
    var olderPageAttempt by remember(session, activityInstalled) { mutableStateOf(0) }
    var query by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var selectedApp by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf<String?>(null) }
    var selectedType by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf<String?>(null) }
    var selectedSemanticName by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf<String?>(null)
    }
    val selectedSemantic = selectedSemanticName?.let { value ->
        NextcloudActivitySemantic.entries.firstOrNull { semantic -> semantic.name == value }
    }
    val filter = ActivityFeedFilter(
        query = query,
        app = selectedApp,
        type = selectedType,
        semantic = selectedSemantic,
    )
    val feed = buildActivityFeedPresentation(timeline.activities, filter)
    val installedAppIds = installedApps.mapTo(linkedSetOf(), NextcloudAppEntry::id)

    fun clearFilters() {
        query = ""
        selectedApp = null
        selectedType = null
        selectedSemanticName = null
    }

    fun openActivityAction(action: ActivityOpenAction) {
        val app = action.appId?.let { appId ->
            installedApps.firstOrNull { installed -> installed.id == appId }
        }
        when {
            app != null -> onOpenApp(app)
            action.sameOriginUrl != null -> services.openExternalUrl(action.sameOriginUrl)
        }
    }

    LaunchedEffect(session, activityInstalled, loadAttempt) {
        if (!activityInstalled) return@LaunchedEffect
        timeline = timeline.beginActivityRefresh()
        runCatching {
            loadNextcloudActivityPage { request -> services.executeNextcloudApi(session, request) }
        }
            .onSuccess { page -> timeline = timeline.applyActivityRefresh(page) }
            .onFailure { failure ->
                timeline = timeline.failActivityLoad(failure.message ?: "Could not load your activity.")
            }
    }

    LaunchedEffect(session, activityInstalled, olderPageAttempt) {
        if (!activityInstalled || olderPageAttempt == 0) return@LaunchedEffect
        val cursor = timeline.nextSince ?: return@LaunchedEffect
        timeline = timeline.beginNextActivityPage()
        runCatching {
            loadNextcloudActivityPage(since = cursor) { request ->
                services.executeNextcloudApi(session, request)
            }
        }
            .onSuccess { page -> timeline = timeline.applyNextActivityPage(page) }
            .onFailure { failure ->
                timeline = timeline.failActivityLoad(failure.message ?: "Could not load more activity.")
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ProductHeader(title = "Activity")
        when {
            !activityInstalled -> Box(
                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                        Icon(
                            NextcloudIcons.Activity,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(18.dp).size(34.dp),
                        )
                    }
                    Text("Activity is not installed", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Install the Nextcloud Activity app to make its events available here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onApps) { Text("View installed apps") }
                }
            }
            !timeline.initialized && timeline.error != null ->
                ErrorMessage(requireNotNull(timeline.error)) { loadAttempt += 1 }
            !timeline.initialized -> LoadingMessage("Loading activity…")
            timeline.activities.isEmpty() -> EmptyMessage("There is no recent activity.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NextcloudSpacing.XLarge,
                    end = NextcloudSpacing.XLarge,
                    top = NextcloudSpacing.Medium,
                    bottom = NextcloudSpacing.XXLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        TextButton(
                            enabled = !timeline.refreshing && !timeline.loadingMore,
                            onClick = { loadAttempt += 1 },
                        ) {
                            Icon(NextcloudIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                if (timeline.refreshing) "Refreshing…" else "Refresh",
                                modifier = Modifier.padding(start = NextcloudSpacing.Small),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search activity") },
                        placeholder = { Text("People, files, messages, or apps") },
                        singleLine = true,
                    )
                }
                item {
                    Text(
                        "Type",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                    ) {
                        NextcloudActivitySemantic.entries.forEach { semantic ->
                            val count = feed.semanticCounts[semantic] ?: 0
                            if (count > 0) {
                                item(semantic.name) {
                                    FilterChip(
                                        selected = selectedSemantic == semantic,
                                        onClick = {
                                            selectedSemanticName =
                                                if (selectedSemantic == semantic) null else semantic.name
                                        },
                                        label = { Text("${readableActivitySemantic(semantic)} $count") },
                                    )
                                }
                            }
                        }
                    }
                }
                if (feed.appFacets.size > 1) {
                    item {
                        Text(
                            "App",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        ) {
                            feed.appFacets.forEach { facet ->
                                item(facet.key) {
                                    FilterChip(
                                        selected = selectedApp == facet.key,
                                        onClick = {
                                            selectedApp = facet.key.takeUnless { selectedApp == facet.key }
                                        },
                                        label = { Text("${facet.label} ${facet.count}") },
                                    )
                                }
                            }
                        }
                    }
                }
                if (feed.typeFacets.size > 1) {
                    item {
                        Text(
                            "Event",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        ) {
                            feed.typeFacets.forEach { facet ->
                                item(facet.key) {
                                    FilterChip(
                                        selected = selectedType == facet.key,
                                        onClick = {
                                            selectedType = facet.key.takeUnless { selectedType == facet.key }
                                        },
                                        label = { Text("${facet.label} ${facet.count}") },
                                    )
                                }
                            }
                        }
                    }
                }
                timeline.error?.let { message ->
                    item {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (feed.groups.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.XLarge),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            Text("No activity matches these filters.", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = ::clearFilters) { Text("Clear filters") }
                        }
                    }
                }
                feed.groups.forEach { group ->
                    item("day:${group.dateKey}") {
                        Text(
                            group.label,
                            modifier = Modifier.padding(top = NextcloudSpacing.Large),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    listItems(group.activities, key = NextcloudActivity::id) { activity ->
                        ActivityRow(
                            activity = activity,
                            action = activity.activityOpenAction(installedAppIds, session.serverUrl),
                            onOpenAction = ::openActivityAction,
                        )
                    }
                }
                if (timeline.hasMore || timeline.loadingMore) {
                    item {
                        TextButton(
                            enabled = !timeline.loadingMore && !timeline.refreshing,
                            onClick = { olderPageAttempt += 1 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (timeline.loadingMore) "Loading…" else "Load older activity")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    activity: NextcloudActivity,
    action: ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
            Icon(
                NextcloudIcons.app(activity.app),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(11.dp).size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(activity.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            activity.message?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val metadata = listOfNotNull(
                activity.app.takeIf(String::isNotBlank)?.let(::readableAppName),
                activity.dateTime?.let(::readableActivityDate),
            ).joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.let { plannedAction ->
                TextButton(
                    onClick = { onOpenAction(plannedAction) },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(plannedAction.label)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun readableActivitySemantic(semantic: NextcloudActivitySemantic): String = when (semantic) {
    NextcloudActivitySemantic.Message -> "Messages"
    NextcloudActivitySemantic.Media -> "Media"
    NextcloudActivitySemantic.File -> "Files"
    NextcloudActivitySemantic.General -> "Other"
}

private fun readableActivityDate(value: String): String = value
    .replace('T', ' ')
    .substringBefore('+')
    .removeSuffix("Z")

private fun readableAppName(value: String): String = value
    .replace('_', ' ')
    .split(' ')
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable
private fun FilesScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    fileSharing: NextcloudFileSharingCapabilities,
    path: String,
    layout: FileLayout,
    onLayoutChanged: (FileLayout) -> Unit,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (NextcloudFile, List<NextcloudFile>) -> Unit,
    onFileAction: (NextcloudFile, FileMenuAction, List<NextcloudFile>) -> Unit,
) {
    var files by remember(path, userId) { mutableStateOf<List<NextcloudFile>?>(null) }
    var error by remember(path, userId) { mutableStateOf<String?>(null) }
    var refreshing by remember(path, userId) { mutableStateOf(false) }
    var listingSource by remember(path, userId) {
        mutableStateOf<NextcloudFileListingSource?>(null)
    }
    var loadAttempt by remember(path, userId) { mutableStateOf(0) }
    var renameTarget by remember(path, userId) { mutableStateOf<NextcloudFile?>(null) }
    var renameValue by remember(path, userId) { mutableStateOf("") }
    var transferTarget by remember(path, userId) { mutableStateOf<Pair<NextcloudFile, FileMenuAction>?>(null) }
    var transferDirectory by remember(path, userId) { mutableStateOf("") }
    var transferName by remember(path, userId) { mutableStateOf("") }
    var deleteTarget by remember(path, userId) { mutableStateOf<NextcloudFile?>(null) }
    var creationKind by remember(path, userId) { mutableStateOf<FileCreationKind?>(null) }
    var creationName by remember(path, userId) { mutableStateOf("") }
    var creationError by remember(path, userId) { mutableStateOf<String?>(null) }
    var creationRunning by remember(path, userId) { mutableStateOf(false) }
    var filterVisible by remember(path, userId) { mutableStateOf(false) }
    var filterQuery by remember(path, userId) { mutableStateOf("") }
    var mutationRunning by remember(path, userId) { mutableStateOf(false) }
    var mutationError by remember(path, userId) { mutableStateOf<String?>(null) }
    var mutationNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    var offlineAvailability by remember(path, userId) {
        mutableStateOf<Map<String, FileOfflineAvailability>>(emptyMap())
    }
    var offlineError by remember(path, userId) { mutableStateOf<String?>(null) }
    var offlineNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    var handoffError by remember(path, userId) { mutableStateOf<String?>(null) }
    var handoffNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    var shareTarget by remember(path, userId) { mutableStateOf<NextcloudFile?>(null) }
    var fileShares by remember(path, userId) { mutableStateOf<List<NextcloudFileShare>?>(null) }
    var shareType by remember(path, userId) { mutableStateOf(FileShareTarget.PublicLink) }
    var shareRecipient by remember(path, userId) { mutableStateOf("") }
    var shareAllowsEditing by remember(path, userId) { mutableStateOf(false) }
    var shareRunning by remember(path, userId) { mutableStateOf(false) }
    var shareError by remember(path, userId) { mutableStateOf<String?>(null) }
    var shareNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    val externalHandoffCapability = remember(services) {
        (services.externalFileHandoffSupport as? ExternalFileHandoffSupport.Available)?.capability
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, userId, loadAttempt) {
        if (userId == null) return@LaunchedEffect
        val retainedFiles = files
        error = null
        val cached = runCatching { services.listFilesCachedWithSource(session, userId, path) }.getOrNull()
        if (cached != null) {
            files = cached.files
            listingSource = cached.source
            if (services.supportsFileOfflineStorage) {
                runCatching { services.loadFileOfflineAvailability(session, userId, cached.files) }
                    .onSuccess { offlineAvailability = it }
                    .onFailure { offlineError = it.message ?: "Could not read offline file status." }
            }
        }
        val hasRetainedFiles = cached != null || retainedFiles != null
        refreshing = hasRetainedFiles
        runCatching { services.listFilesWithSource(session, userId, path) }
            .onSuccess { listing ->
                files = listing.files
                listingSource = listing.source
                refreshing = false
                if (services.supportsFileOfflineStorage) {
                    runCatching { services.loadFileOfflineAvailability(session, userId, listing.files) }
                        .onSuccess { offlineAvailability = it }
                        .onFailure { offlineError = it.message ?: "Could not read offline file status." }
                }
            }
            .onFailure {
                refreshing = false
                error = nextcloudFileRefreshFailure(hasRetainedFiles, it)
            }
    }
    LaunchedEffect(mutationNotice) {
        if (mutationNotice != null) {
            delay(3_500)
            mutationNotice = null
        }
    }
    LaunchedEffect(offlineNotice) {
        if (offlineNotice != null) {
            delay(3_500)
            offlineNotice = null
        }
    }
    LaunchedEffect(handoffNotice) {
        if (handoffNotice != null) {
            delay(3_500)
            handoffNotice = null
        }
    }
    val offlineWorkPending = offlineAvailability.values.any { availability ->
        availability in setOf(
            FileOfflineAvailability.Queued,
            FileOfflineAvailability.Downloading,
            FileOfflineAvailability.Removing,
            FileOfflineAvailability.WaitingForNetwork,
        )
    }
    LaunchedEffect(path, userId, offlineWorkPending) {
        if (!offlineWorkPending || userId == null || !services.supportsFileOfflineStorage) return@LaunchedEffect
        while (true) {
            delay(800)
            val loaded = files ?: break
            val refreshed = runCatching {
                services.loadFileOfflineAvailability(session, userId, loaded)
            }.getOrElse {
                offlineError = it.message ?: "Could not refresh offline file status."
                break
            }
            offlineAvailability = refreshed
            if (refreshed.values.none { availability ->
                    availability in setOf(
                        FileOfflineAvailability.Queued,
                        FileOfflineAvailability.Downloading,
                        FileOfflineAvailability.Removing,
                        FileOfflineAvailability.WaitingForNetwork,
                    )
                }
            ) break
        }
    }

    fun dispatchFileAction(
        file: NextcloudFile,
        action: FileMenuAction,
        loadedFiles: List<NextcloudFile>,
    ) {
        when (action) {
            FileMenuAction.Rename -> {
                renameTarget = file
                renameValue = file.name
                mutationError = null
            }
            FileMenuAction.Delete -> {
                deleteTarget = file
                mutationError = null
            }
            FileMenuAction.Move, FileMenuAction.Copy -> {
                transferTarget = file to action
                transferDirectory = path
                transferName = file.name
                mutationError = null
            }
            FileMenuAction.MakeAvailableOffline, FileMenuAction.RemoveOffline -> {
                val makeAvailable = action == FileMenuAction.MakeAvailableOffline
                val previous = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly
                offlineError = null
                offlineAvailability = offlineAvailability + (
                    file.path to if (makeAvailable) FileOfflineAvailability.Queued else FileOfflineAvailability.Removing
                    )
                scope.launch {
                    runCatching {
                        services.setFileAvailableOffline(session, requireNotNull(userId), file, makeAvailable)
                    }.onSuccess { availability ->
                        offlineAvailability = offlineAvailability + (file.path to availability)
                        offlineNotice = if (makeAvailable) {
                            "${file.name} queued for offline use"
                        } else {
                            "Removing the offline copy of ${file.name}"
                        }
                    }.onFailure {
                        offlineAvailability = offlineAvailability + (file.path to previous)
                        offlineError = it.message ?: "Could not update offline availability."
                    }
                }
            }
            FileMenuAction.Share -> {
                shareTarget = file
                fileShares = null
                shareRecipient = ""
                shareAllowsEditing = false
                shareType = when {
                    fileSharing.publicLinks -> FileShareTarget.PublicLink
                    fileSharing.userShares -> FileShareTarget.User
                    else -> FileShareTarget.Group
                }
                shareError = null
                shareNotice = null
                scope.launch {
                    runCatching { services.listFileShares(session, file.path) }
                        .onSuccess { fileShares = it }
                        .onFailure {
                            fileShares = emptyList()
                            shareError = it.message ?: "Could not load existing shares."
                        }
                }
            }
            FileMenuAction.OpenWith -> {
                handoffError = null
                handoffNotice = "Preparing ${file.name}…"
                scope.launch {
                    runCatching {
                        services.handoffFileToExternalApp(
                            session = session,
                            userId = requireNotNull(userId),
                            file = file,
                            action = ExternalFileHandoffAction.OpenWith,
                        )
                    }.onSuccess { result ->
                        when (result) {
                            is ExternalFileHandoffResult.Launched -> handoffNotice = null
                            is ExternalFileHandoffResult.Rejected -> {
                                handoffNotice = null
                                handoffError = result.message
                            }
                            is ExternalFileHandoffResult.NoCompatibleApplication -> {
                                handoffNotice = null
                                handoffError = "No installed app can open this file."
                            }
                            is ExternalFileHandoffResult.Unsupported -> {
                                handoffNotice = null
                                handoffError = result.reason
                            }
                        }
                    }.onFailure {
                        handoffNotice = null
                        handoffError = it.message ?: "Could not prepare ${file.name} for another app."
                    }
                }
            }
            FileMenuAction.SendCopy -> {
                handoffError = null
                handoffNotice = "Preparing ${file.name}…"
                scope.launch {
                    runCatching {
                        services.handoffFileToExternalApp(
                            session = session,
                            userId = requireNotNull(userId),
                            file = file,
                            action = ExternalFileHandoffAction.Share,
                        )
                    }.onSuccess { result ->
                        when (result) {
                            is ExternalFileHandoffResult.Launched -> handoffNotice = null
                            is ExternalFileHandoffResult.Rejected -> {
                                handoffNotice = null
                                handoffError = result.message
                            }
                            is ExternalFileHandoffResult.NoCompatibleApplication -> {
                                handoffNotice = null
                                handoffError = "No installed app can receive this file."
                            }
                            is ExternalFileHandoffResult.Unsupported -> {
                                handoffNotice = null
                                handoffError = result.reason
                            }
                        }
                    }.onFailure {
                        handoffNotice = null
                        handoffError = it.message ?: "Could not prepare ${file.name} to send."
                    }
                }
            }
            else -> onFileAction(file, action, loadedFiles)
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Files", if (path.isBlank()) "All files" else "/$path", onBack)
        mutationNotice?.let { notice ->
            Surface(
                color = NextcloudTheme.colors.success.copy(alpha = 0.12f),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    notice,
                    color = NextcloudTheme.colors.success,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        offlineNotice?.let { notice ->
            Surface(
                color = NextcloudTheme.colors.success.copy(alpha = 0.12f),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    notice,
                    color = NextcloudTheme.colors.success,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        offlineError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        handoffNotice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        handoffError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        if (files != null && error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    requireNotNull(error),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        when {
            error != null && files == null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
            files == null -> LoadingMessage("Loading files…")
            files?.isEmpty() == true -> EmptyMessage("This folder is empty.")
            else -> {
                val loadedFiles = requireNotNull(files)
                val visibleFiles = remember(loadedFiles, filterQuery) { presentFiles(loadedFiles, filterQuery) }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            nextcloudFileListingSummary(
                                source = listingSource,
                                visibleCount = visibleFiles.size,
                                totalCount = loadedFiles.size,
                                filtered = filterQuery.isNotBlank(),
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            OutlinedButton(
                                onClick = {
                                    creationKind = FileCreationKind.Folder
                                    creationName = ""
                                    creationError = null
                                },
                            ) {
                                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("New")
                            }
                            IconButton(
                                onClick = {
                                    filterVisible = !filterVisible
                                    if (!filterVisible) filterQuery = ""
                                },
                            ) {
                                Icon(NextcloudIcons.Search, contentDescription = "Search this folder")
                            }
                            IconButton(
                                onClick = { loadAttempt += 1 },
                                enabled = !refreshing,
                            ) {
                                if (refreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(NextcloudIcons.Refresh, contentDescription = "Refresh folder")
                                }
                            }
                            IconButton(
                                onClick = {
                                    onLayoutChanged(
                                        if (layout == FileLayout.List) FileLayout.Grid else FileLayout.List,
                                    )
                                },
                            ) {
                                Icon(
                                    if (layout == FileLayout.List) NextcloudIcons.Apps else NextcloudIcons.ListView,
                                    contentDescription = if (layout == FileLayout.List) {
                                        "Switch to grid layout"
                                    } else {
                                        "Switch to list layout"
                                    },
                                )
                            }
                        }
                    }
                    if (filterVisible) {
                        OutlinedTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            label = { Text("Search this folder") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = NextcloudSpacing.XLarge, vertical = NextcloudSpacing.Small),
                        )
                    }
                    if (visibleFiles.isEmpty()) {
                        EmptyMessage("No files match “${filterQuery.trim()}”.")
                    } else if (layout == FileLayout.List) {
                        FileList(
                            files = visibleFiles,
                            offlineAvailability = offlineAvailability,
                            offlineStorageSupported = services.supportsFileOfflineStorage,
                            fileSharing = fileSharing,
                            externalHandoffCapability = externalHandoffCapability,
                            onOpenFolder = onOpenFolder,
                            onOpenFile = { onOpenFile(it, visibleFiles) },
                            onAction = { file, action -> dispatchFileAction(file, action, loadedFiles) },
                        )
                    } else {
                        FileGrid(
                            files = visibleFiles,
                            offlineAvailability = offlineAvailability,
                            offlineStorageSupported = services.supportsFileOfflineStorage,
                            fileSharing = fileSharing,
                            externalHandoffCapability = externalHandoffCapability,
                            services = services,
                            session = session,
                            onOpenFolder = onOpenFolder,
                            onOpenFile = { onOpenFile(it, visibleFiles) },
                            onAction = { file, action -> dispatchFileAction(file, action, loadedFiles) },
                        )
                    }
                }
            }
        }
    }

    creationKind?.let { selectedKind ->
        val creationPlan = runCatching { planFileCreation(selectedKind, path, creationName) }.getOrNull()
        val validationError = if (creationName.isBlank()) null else runCatching {
            planFileCreation(selectedKind, path, creationName)
        }.exceptionOrNull()?.message
        AlertDialog(
            onDismissRequest = {
                if (!creationRunning) {
                    creationKind = null
                    creationError = null
                }
            },
            title = { Text("Create in ${if (path.isBlank()) "All files" else path.substringAfterLast('/')}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "Create a folder or start a native Markdown/text document. Existing items are never overwritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        FileCreationKind.entries.forEach { kind ->
                            FilterChip(
                                selected = selectedKind == kind,
                                enabled = !creationRunning,
                                onClick = {
                                    creationKind = kind
                                    creationError = null
                                },
                                label = {
                                    Text(
                                        when (kind) {
                                            FileCreationKind.Folder -> "Folder"
                                            FileCreationKind.Markdown -> "Markdown"
                                            FileCreationKind.Text -> "Text"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = creationName,
                        onValueChange = {
                            creationName = it
                            creationError = null
                        },
                        label = {
                            Text(if (selectedKind == FileCreationKind.Folder) "Folder name" else "Document name")
                        },
                        supportingText = creationPlan?.takeIf { it.name != creationName.trim() }?.let { plan ->
                            { Text("Will be created as ${plan.name}") }
                        },
                        singleLine = true,
                        enabled = !creationRunning,
                        isError = creationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (creationError ?: validationError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !creationRunning,
                    onClick = {
                        creationKind = null
                        creationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = creationPlan != null && !creationRunning && userId != null,
                    onClick = {
                        val plan = creationPlan ?: return@Button
                        val loadedFiles = files.orEmpty()
                        creationRunning = true
                        creationError = null
                        scope.launch {
                            runCatching {
                                when (plan.kind) {
                                    FileCreationKind.Folder -> {
                                        check(services.createDirectoryIfAbsent(session, requireNotNull(userId), plan.path)) {
                                            "An item named ${plan.name} already exists."
                                        }
                                        null
                                    }
                                    FileCreationKind.Markdown, FileCreationKind.Text -> {
                                        val result = services.createTextFileIfAbsent(
                                            session,
                                            requireNotNull(userId),
                                            plan.path,
                                            "",
                                        )
                                        check(result.wasCreated) { "An item named ${plan.name} already exists." }
                                        NextcloudFile(
                                            path = plan.path,
                                            name = plan.name,
                                            isDirectory = false,
                                            mimeType = if (plan.kind == FileCreationKind.Markdown) {
                                                "text/markdown"
                                            } else {
                                                "text/plain"
                                            },
                                            size = 0,
                                            lastModified = null,
                                            fileId = null,
                                            hasPreview = false,
                                            etag = result.etag,
                                            permissions = "WDNVR",
                                        )
                                    }
                                }
                            }.onSuccess { createdDocument ->
                                creationKind = null
                                creationName = ""
                                mutationNotice = "Created ${plan.name}"
                                if (createdDocument == null) {
                                    files = null
                                    loadAttempt += 1
                                } else {
                                    onOpenFile(createdDocument, loadedFiles + createdDocument)
                                }
                            }.onFailure {
                                creationError = it.message ?: "Could not create ${plan.name}."
                            }
                            creationRunning = false
                        }
                    },
                ) {
                    if (creationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (creationRunning) "Creating…" else "Create")
                }
            },
        )
    }

    renameTarget?.let { target ->
        val validationError = fileRenameValidationError(target, renameValue)
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    renameTarget = null
                    mutationError = null
                }
            },
            title = { Text("Rename ${if (target.isDirectory) "folder" else "file"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "The rename is protected by the version currently shown. If the item changed on the server, it will stop instead of overwriting it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = {
                            renameValue = it
                            mutationError = null
                        },
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !mutationRunning,
                        isError = mutationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (mutationError ?: validationError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        renameTarget = null
                        mutationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = validationError == null && !mutationRunning,
                    onClick = {
                        val etag = target.etag?.takeIf(String::isNotBlank)
                        if (etag == null) {
                            mutationError = "Refresh the folder before renaming this item."
                            return@Button
                        }
                        mutationRunning = true
                        mutationError = null
                        scope.launch {
                            runCatching {
                                services.executeFileMutation(
                                    session,
                                    requireNotNull(userId),
                                    NextcloudFileMutation.Rename(
                                        target.path,
                                        renameValue,
                                        etag,
                                        sourceIsDirectory = target.isDirectory,
                                    ),
                                )
                            }.onSuccess {
                                renameTarget = null
                                mutationNotice = "Renamed to $renameValue"
                                files = null
                                loadAttempt += 1
                            }.onFailure {
                                mutationError = it.message ?: "Could not rename this item."
                            }
                            mutationRunning = false
                        }
                    },
                ) {
                    if (mutationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (mutationRunning) "Renaming…" else "Rename")
                }
            },
        )
    }

    transferTarget?.let { (target, action) ->
        val moving = action == FileMenuAction.Move
        val verb = if (moving) "Move" else "Copy"
        val validationError = fileTransferValidationError(target, transferDirectory, transferName)
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    transferTarget = null
                    mutationError = null
                }
            },
            title = { Text("$verb ${target.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "Choose a folder relative to your Nextcloud root. Leave the folder blank for the root.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = transferDirectory,
                        onValueChange = {
                            transferDirectory = it
                            mutationError = null
                        },
                        label = { Text("Destination folder") },
                        placeholder = { Text("Photos/Edited") },
                        singleLine = true,
                        enabled = !mutationRunning,
                        isError = mutationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = transferName,
                        onValueChange = {
                            transferName = it
                            mutationError = null
                        },
                        label = { Text("Name at destination") },
                        singleLine = true,
                        enabled = !mutationRunning,
                        isError = mutationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "An existing item is never overwritten. The source ETag is checked before the operation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (mutationError ?: validationError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        transferTarget = null
                        mutationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = validationError == null && !mutationRunning,
                    onClick = {
                        val etag = target.etag?.takeIf(String::isNotBlank)
                        if (etag == null) {
                            mutationError = "Refresh the folder before changing this item."
                            return@Button
                        }
                        mutationRunning = true
                        mutationError = null
                        scope.launch {
                            val mutation = if (moving) {
                                NextcloudFileMutation.Move(
                                    target.path,
                                    transferDirectory,
                                    transferName,
                                    etag,
                                    sourceIsDirectory = target.isDirectory,
                                )
                            } else {
                                NextcloudFileMutation.Copy(
                                    target.path,
                                    transferDirectory,
                                    transferName,
                                    etag,
                                    sourceIsDirectory = target.isDirectory,
                                )
                            }
                            runCatching {
                                services.executeFileMutation(session, requireNotNull(userId), mutation)
                            }.onSuccess {
                                transferTarget = null
                                mutationNotice = if (moving) {
                                    "Moved ${target.name}"
                                } else {
                                    "Copied ${target.name}"
                                }
                                files = null
                                loadAttempt += 1
                            }.onFailure {
                                mutationError = it.message ?: "Could not ${verb.lowercase()} this item."
                            }
                            mutationRunning = false
                        }
                    },
                ) {
                    if (mutationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (mutationRunning) "${verb}ing…" else verb)
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    deleteTarget = null
                    mutationError = null
                }
            },
            title = { Text("Delete ${target.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        if (target.isDirectory) {
                            "This removes the folder and everything inside it from Nextcloud. This cannot be undone here."
                        } else {
                            "This removes the file from Nextcloud. This cannot be undone here."
                        },
                    )
                    Text(
                        "The delete is ETag-protected and will stop if the item changed since this folder was loaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        deleteTarget = null
                        mutationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = !mutationRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val etag = target.etag?.takeIf(String::isNotBlank)
                        if (etag == null) {
                            mutationError = "Refresh the folder before deleting this item."
                            return@Button
                        }
                        mutationRunning = true
                        mutationError = null
                        scope.launch {
                            runCatching {
                                services.executeFileMutation(
                                    session,
                                    requireNotNull(userId),
                                    NextcloudFileMutation.Delete(
                                        target.path,
                                        etag,
                                        sourceIsDirectory = target.isDirectory,
                                    ),
                                )
                            }.onSuccess {
                                deleteTarget = null
                                mutationNotice = "Deleted ${target.name}"
                                files = null
                                loadAttempt += 1
                            }.onFailure {
                                mutationError = it.message ?: "Could not delete this item."
                            }
                            mutationRunning = false
                        }
                    },
                ) {
                    if (mutationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (mutationRunning) "Deleting…" else "Delete")
                }
            },
        )
    }

    shareTarget?.let { target ->
        val supportedTargets = FileShareTarget.entries.filter { targetType ->
            when (targetType) {
                FileShareTarget.PublicLink -> fileSharing.publicLinks
                FileShareTarget.User -> fileSharing.userShares
                FileShareTarget.Group -> fileSharing.groupShares
            }
        }
        val requestedPermissions = FileSharePermissions(
            read = true,
            update = shareAllowsEditing,
            create = shareAllowsEditing && target.isDirectory,
            delete = shareAllowsEditing && target.isDirectory,
        )
        val creationPlan = planFileShareCreation(
            file = target,
            target = shareType,
            recipient = shareRecipient.takeUnless { shareType == FileShareTarget.PublicLink },
            permissions = requestedPermissions,
            capabilities = fileSharing,
        )
        AlertDialog(
            onDismissRequest = {
                if (!shareRunning) {
                    shareTarget = null
                    shareError = null
                    shareNotice = null
                }
            },
            title = { Text("Share ${target.name}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Text(
                        if (target.isDirectory) {
                            "Manage access to this folder on your Nextcloud server."
                        } else {
                            "Manage access to this file on your Nextcloud server."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Existing access", style = MaterialTheme.typography.titleSmall)
                    when (val loadedShares = fileShares) {
                        null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else -> if (loadedShares.isEmpty()) {
                            Text(
                                "Not shared yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            loadedShares.take(12).forEach { existing ->
                                ExistingFileShareManager(
                                    share = existing,
                                    sourceIsDirectory = target.isDirectory,
                                    session = session,
                                    services = services,
                                    onChanged = { changed ->
                                        fileShares = fileShares.orEmpty().map {
                                            if (it.id == changed.id) changed else it
                                        }
                                    },
                                    onRevoked = { revoked ->
                                        fileShares = fileShares.orEmpty().filterNot { it.id == revoked.id }
                                        shareNotice = "Access revoked"
                                    },
                                )
                            }
                            if (loadedShares.size > 12) {
                                Text(
                                    "${loadedShares.size - 12} more shares are active.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (supportedTargets.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Create access", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            supportedTargets.forEach { targetType ->
                                FilterChip(
                                    selected = shareType == targetType,
                                    enabled = !shareRunning,
                                    onClick = {
                                        shareType = targetType
                                        shareRecipient = ""
                                        shareError = null
                                    },
                                    label = { Text(targetType.fileShareTargetLabel()) },
                                )
                            }
                        }
                        if (shareType != FileShareTarget.PublicLink) {
                            FileShareRecipientPicker(
                                session = session,
                                services = services,
                                target = shareType,
                                selectedRecipient = shareRecipient,
                                enabled = !shareRunning,
                                onSelected = {
                                    shareRecipient = it?.id.orEmpty()
                                    shareError = null
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            FilterChip(
                                selected = !shareAllowsEditing,
                                enabled = !shareRunning,
                                onClick = { shareAllowsEditing = false },
                                label = { Text("Can view") },
                            )
                            FilterChip(
                                selected = shareAllowsEditing,
                                enabled = !shareRunning,
                                onClick = { shareAllowsEditing = true },
                                label = { Text("Can edit") },
                            )
                        }
                        (creationPlan as? FileShareCreationPlan.Blocked)?.let {
                            Text(
                                it.reason,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    shareNotice?.let {
                        Text(it, color = NextcloudTheme.colors.success, style = MaterialTheme.typography.bodySmall)
                    }
                    shareError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !shareRunning,
                    onClick = {
                        shareTarget = null
                        shareError = null
                        shareNotice = null
                    },
                ) { Text("Close") }
            },
            confirmButton = {
                Button(
                    enabled = creationPlan is FileShareCreationPlan.Ready && !shareRunning,
                    onClick = {
                        val ready = creationPlan as? FileShareCreationPlan.Ready ?: return@Button
                        shareRunning = true
                        shareError = null
                        shareNotice = null
                        scope.launch {
                            runCatching { services.createFileShare(session, ready.request) }
                                .onSuccess { created ->
                                    val safeUrl = safeFileShareUrl(session, created)
                                    val copied = safeUrl != null &&
                                        services.copyTextToClipboard("Nextcloud share link", safeUrl)
                                    shareNotice = if (copied) "Share created and link copied" else "Share created"
                                    fileShares = runCatching {
                                        services.listFileShares(session, target.path)
                                    }.getOrElse { current -> fileShares.orEmpty() + created }
                                    shareRecipient = ""
                                }
                                .onFailure {
                                    shareError = it.message ?: "Could not create the share."
                                }
                            shareRunning = false
                        }
                    },
                ) {
                    if (shareRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (shareRunning) "Creating…" else "Create")
                }
            },
        )
    }
}

private fun FileShareTarget.fileShareTargetLabel(): String = when (this) {
    FileShareTarget.PublicLink -> "Public link"
    FileShareTarget.User -> "User"
    FileShareTarget.Group -> "Group"
}

@Composable
private fun FileList(
    files: List<NextcloudFile>,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NextcloudSpacing.XXLarge)) {
        listItems(files, key = NextcloudFile::path) { file ->
            var menuExpanded by remember(file.path) { mutableStateOf(false) }
            val availability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly
            Row(
                modifier = Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClickLabel = primaryFileActionLabel(file),
                        onLongClickLabel = "Show actions for ${file.name}",
                        onClick = { if (file.isDirectory) onOpenFolder(file.path) else onOpenFile(file) },
                        onLongClick = { menuExpanded = true },
                    )
                    .padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(10.dp)) {
                    Icon(
                        if (file.isDirectory) NextcloudIcons.Folder else fileIcon(file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(9.dp).size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        availability.readableStatus()
                            ?: if (file.isDirectory) "Folder" else formatBytes(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                    }
                    FileActionMenu(
                        file = file,
                        offlineAvailability = availability,
                        offlineStorageSupported = offlineStorageSupported,
                        fileSharing = fileSharing,
                        externalHandoffCapability = externalHandoffCapability,
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onAction = { onAction(file, it) },
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun FileGrid(
    files: List<NextcloudFile>,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
) {
    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Adaptive(128.dp),
        contentPadding = PaddingValues(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        items(files, key = NextcloudFile::path) { file ->
            FileGridTile(
                file = file,
                offlineAvailability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly,
                offlineStorageSupported = offlineStorageSupported,
                fileSharing = fileSharing,
                externalHandoffCapability = externalHandoffCapability,
                services = services,
                session = session,
                onClick = { if (file.isDirectory) onOpenFolder(file.path) else onOpenFile(file) },
                onAction = { onAction(file, it) },
            )
        }
    }
}

@Composable
private fun FileGridTile(
    file: NextcloudFile,
    offlineAvailability: FileOfflineAvailability,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onClick: () -> Unit,
    onAction: (FileMenuAction) -> Unit,
) {
    var menuExpanded by remember(file.path) { mutableStateOf(false) }
    var preview by remember(file.fileId, file.etag) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.fileId, file.etag, file.hasPreview) {
        file.fileId ?: return@LaunchedEffect
        if (!file.hasPreview || file.isDirectory) return@LaunchedEffect
        preview = runCatching {
            decodePlatformImage(services.loadPreviewCached(session, file, width = 320, height = 320))
        }.getOrNull()
    }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClickLabel = primaryFileActionLabel(file),
            onLongClickLabel = "Show actions for ${file.name}",
            onClick = onClick,
            onLongClick = { menuExpanded = true },
        ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)
                .background(NextcloudTheme.colors.appIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(
                if (file.isDirectory) NextcloudIcons.Folder else fileIcon(file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(NextcloudSpacing.Small)) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shape = CircleShape,
                ) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(38.dp)) {
                        Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                    }
                }
                FileActionMenu(
                    file = file,
                    offlineAvailability = offlineAvailability,
                    offlineStorageSupported = offlineStorageSupported,
                    fileSharing = fileSharing,
                    externalHandoffCapability = externalHandoffCapability,
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onAction = onAction,
                )
            }
        }
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(
                offlineAvailability.readableStatus()
                    ?: if (file.isDirectory) "Folder" else formatBytes(file.size),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileActionMenu(
    file: NextcloudFile,
    offlineAvailability: FileOfflineAvailability,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (FileMenuAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        planFilesScreenActions(
            file = file,
            support = FileActionSupport(
                sharing = fileSharing.apiEnabled,
                externalSharing = ExternalFileHandoffAction.Share in
                    externalHandoffCapability?.supportedActions.orEmpty(),
                offlineStorage = offlineStorageSupported,
                platformViewer = ExternalFileHandoffAction.OpenWith in externalHandoffCapability?.supportedActions.orEmpty(),
                maximumExternalFileBytes = externalHandoffCapability?.maximumFileBytes,
            ),
            offlineState = offlineAvailability.toFileActionOfflineState(),
        ).actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(action.label)
                        action.disabledReason?.let { reason ->
                            Text(
                                reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = fileActionIcon(action.action),
                        contentDescription = null,
                        tint = if (action.tone == FileActionTone.Destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                enabled = action.enabled,
                onClick = {
                    onDismiss()
                    onAction(action.action)
                },
            )
        }
    }
}

private fun fileActionIcon(action: FileMenuAction): ImageVector = when (action) {
    FileMenuAction.Open -> NextcloudIcons.FolderOpen
    FileMenuAction.Preview -> NextcloudIcons.Image
    FileMenuAction.OpenWith -> NextcloudIcons.File
    FileMenuAction.EditText, FileMenuAction.EditWith, FileMenuAction.Rename -> NextcloudIcons.Edit
    FileMenuAction.Details -> NextcloudIcons.Info
    FileMenuAction.VersionHistory -> NextcloudIcons.Refresh
    FileMenuAction.Download -> NextcloudIcons.Cloud
    FileMenuAction.Move -> NextcloudIcons.FolderOpen
    FileMenuAction.Copy -> NextcloudIcons.File
    FileMenuAction.Share -> NextcloudIcons.People
    FileMenuAction.SendCopy -> NextcloudIcons.Cloud
    FileMenuAction.MakeAvailableOffline, FileMenuAction.RemoveOffline -> NextcloudIcons.CheckCircle
    FileMenuAction.Delete -> NextcloudIcons.Error
}

@Composable
private fun MediaScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    mode: MediaMode,
    collectionState: MediaCollectionsUiState,
    collectionGridState: LazyGridState,
    onModeChanged: (MediaMode) -> Unit,
    onBack: () -> Unit,
    onOpenMedia: (NextcloudFile, List<NextcloudFile>) -> Unit,
    onOpenPerson: (NextcloudPerson) -> Unit,
) {
    var media by remember(userId) { mutableStateOf<List<NextcloudFile>?>(null) }
    var mediaBackupStatuses by remember(userId) {
        mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())
    }
    val peopleByBackend = remember(userId) {
        mutableStateMapOf<NextcloudPeopleBackend, List<NextcloudPerson>>()
    }
    var peopleBackend by rememberSaveable(userId, stateSaver = enumSaver<NextcloudPeopleBackend>()) {
        mutableStateOf(NextcloudPeopleBackend.Recognize)
    }
    var peopleNameFilter by rememberSaveable(userId, stateSaver = enumSaver<PeopleNameFilter>()) {
        mutableStateOf(PeopleNameFilter.All)
    }
    var mediaError by remember(userId) { mutableStateOf<String?>(null) }
    var peopleError by remember(userId) { mutableStateOf<String?>(null) }
    var peopleSearch by rememberSaveable(userId) { mutableStateOf("") }
    var mediaLoadAttempt by remember(userId) { mutableStateOf(0) }
    var peopleLoadAttempt by remember(userId) { mutableStateOf(0) }
    val collectionService = remember(services) { NativeMediaCollectionReadService(services) }
    val collectionMutationService = remember(services) { NativeMediaCollectionMutationService(services) }
    with(collectionState) {
    DisposableEffect(collectionState) {
        onDispose {
            requestGeneration += 1
            loading = false
        }
    }
    LaunchedEffect(userId, mediaLoadAttempt) {
        if (userId == null) return@LaunchedEffect
        media = null
        mediaBackupStatuses = emptyMap()
        mediaError = null
        runCatching {
            val loaded = services.listMedia(session, userId)
            val statuses = runCatching {
                services.loadMediaBackupStatuses(session, userId, loaded)
            }.getOrDefault(emptyMap())
            loaded to statuses
        }
            .onSuccess { (loaded, statuses) ->
                media = loaded
                mediaBackupStatuses = statuses
            }
            .onFailure { mediaError = it.message ?: "Could not load media." }
    }
    LaunchedEffect(userId, services) {
        if (userId == null) return@LaunchedEffect
        services.observeMediaBackupStatusChanges(session).collectLatest {
            val visibleFiles = (media.orEmpty() + resolvedFiles.values)
                .distinctBy { file -> file.path.trim('/') }
            if (visibleFiles.isNotEmpty()) {
                val statuses = runCatching {
                    services.loadMediaBackupStatuses(session, userId, visibleFiles)
                }.getOrDefault(emptyMap())
                mediaBackupStatuses = mediaBackupStatuses + statuses
                backupStatuses = backupStatuses + statuses
            }
        }
    }
    LaunchedEffect(mode, peopleBackend, peopleLoadAttempt) {
        if (mode != MediaMode.People || peopleBackend in peopleByBackend) return@LaunchedEffect
        peopleError = null
        runCatching { services.listPeople(session, peopleBackend.apiValue) }
            .onSuccess { peopleByBackend[peopleBackend] = it }
            .onFailure { peopleError = it.message ?: "Could not load people from Memories." }
    }

    LaunchedEffect(mode, loadAttempt) {
        if (mode != MediaMode.Collections || catalog != null) return@LaunchedEffect
        error = null
        runCatching { collectionService.loadCatalog(session) }
            .onSuccess { catalog = it }
            .onFailure { error = it.message ?: "Could not load albums and tags." }
    }

    suspend fun loadCollectionPage(collection: NativeMediaCollection, reset: Boolean) {
        val generation = ++requestGeneration
        loading = true
        error = null
        val result = runCatching {
            val index = if (reset || dayIndex?.collectionKey != collection.key) {
                collectionService.loadDayIndex(session, collection)
            } else {
                requireNotNull(dayIndex)
            }
            val page = collectionService.loadPage(
                session = session,
                collection = collection,
                index = index,
                cursor = if (reset) null else cursor,
            )
            val resolved = if (userId == null || page.items.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.resolveFilesById(session, userId, page.items.map(NativeMediaItem::fileId))
                }.getOrDefault(emptyMap())
            }
            val statuses = if (userId == null || resolved.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.loadMediaBackupStatuses(session, userId, resolved.values)
                }.getOrDefault(emptyMap())
            }
            Triple(index, page, resolved) to statuses
        }
        if (generation != requestGeneration || selectedCollection?.key != collection.key) return
        result.onSuccess { (loaded, statuses) ->
            val (index, page, resolved) = loaded
            dayIndex = index
            collectionItems = if (reset) page.items else (collectionItems + page.items).distinctBy { it.fileId }
            resolvedFiles = if (reset) resolved else resolvedFiles + resolved
            backupStatuses = if (reset) statuses else backupStatuses + statuses
            cursor = page.nextCursor
        }.onFailure {
            error = it.message ?: "Could not load this collection."
        }
        loading = false
    }

    val scope = rememberCoroutineScope()
    selectedCollection?.let { collection ->
        fun closeCollection() {
            requestGeneration += 1
            selectedCollection = null
            dayIndex = null
            collectionItems = emptyList()
            resolvedFiles = emptyMap()
            backupStatuses = emptyMap()
            cursor = null
            loading = false
            error = null
            pendingAction = null
            mutationRunning = false
            mutationError = null
        }
        PlatformBackHandler(enabled = true, onBack = ::closeCollection)
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ScreenHeader(
                collection.name,
                when (collection.type) {
                    NativeMediaCollectionType.Album -> "Album"
                    NativeMediaCollectionType.SystemTag -> "Tagged media"
                },
                onBack = ::closeCollection,
            )
            when {
                error != null && collectionItems.isEmpty() -> ErrorMessage(requireNotNull(error)) {
                    scope.launch { loadCollectionPage(collection, reset = true) }
                }
                loading && collectionItems.isEmpty() -> LoadingMessage("Loading ${collection.name}…")
                collectionItems.isEmpty() && dayIndex != null -> EmptyMessage("This collection has no indexed media.")
                else -> NativeMediaCollectionContent(
                    collection = collection,
                    items = collectionItems,
                    resolvedFiles = resolvedFiles,
                    backupStatuses = backupStatuses,
                    services = services,
                    session = session,
                    loadingMore = loading,
                    canLoadMore = cursor != null,
                    loadMoreError = error,
                    onOpenMedia = onOpenMedia,
                    onLongPressMedia = if (collection.type == NativeMediaCollectionType.Album) {
                        { item ->
                            pendingAction = planRemoveItemFromMediaCollection(
                                collection = collection,
                                item = item,
                                currentUserId = userId,
                            )
                            mutationError = null
                        }
                    } else {
                        null
                    },
                    onLoadMore = { scope.launch { loadCollectionPage(collection, reset = false) } },
                    gridState = collectionGridState,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        pendingAction?.let { plan ->
            AlertDialog(
                onDismissRequest = {
                    if (!mutationRunning) {
                        pendingAction = null
                        mutationError = null
                    }
                },
                title = { Text(plan.confirmation.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                        Text(plan.confirmation.message)
                        Text(
                            plan.disabledReason
                                ?: mutationError
                                ?: "This changes album membership only. The original file is not deleted.",
                            color = when {
                                plan.disabledReason != null || mutationError != null ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (mutationRunning) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text("Updating album…")
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !mutationRunning,
                        onClick = {
                            pendingAction = null
                            mutationError = null
                        },
                    ) {
                        Text(if (plan.enabled) "Cancel" else "Close")
                    }
                },
                confirmButton = {
                    if (plan.enabled) {
                        Button(
                            enabled = !mutationRunning,
                            onClick = {
                                mutationRunning = true
                                mutationError = null
                                scope.launch {
                                    runCatching {
                                        collectionMutationService.executeConfirmed(
                                            session = session,
                                            plan = plan,
                                            confirmed = true,
                                        )
                                    }.onSuccess { result ->
                                        collectionItems = collectionItems.filterNot {
                                            it.fileId == result.removedFileId
                                        }
                                        val updatedCollection = collection.copy(
                                            itemCount = collection.itemCount?.let { count ->
                                                (count - 1).coerceAtLeast(0)
                                            },
                                        )
                                        selectedCollection = updatedCollection
                                        catalog = catalog?.let { currentCatalog ->
                                            currentCatalog.copy(
                                                albums = currentCatalog.albums.map { album ->
                                                    if (album.key == updatedCollection.key) {
                                                        updatedCollection
                                                    } else {
                                                        album
                                                    }
                                                },
                                            )
                                        }
                                        pendingAction = null
                                        mutationRunning = false
                                        mutationError = null
                                    }.onFailure { failure ->
                                        mutationRunning = false
                                        mutationError = failure.message
                                            ?: "Could not remove this item from the album."
                                    }
                                }
                            },
                        ) {
                            Text(plan.confirmation.confirmLabel)
                        }
                    }
                },
            )
        }
        return
    }

    val createAlbumPlan = remember(createAlbumName, userId, catalog) {
        planCreateMediaAlbum(
            name = createAlbumName,
            currentUserId = userId,
            existingAlbums = catalog?.albums.orEmpty(),
        )
    }
    if (createAlbumVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    createAlbumVisible = false
                    createAlbumName = ""
                }
            },
            title = { Text("New album") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    OutlinedTextField(
                        value = createAlbumName,
                        onValueChange = { createAlbumName = it },
                        label = { Text("Album name") },
                        singleLine = true,
                        isError = createAlbumName.isNotEmpty() && createAlbumPlan.disabledReason != null,
                    )
                    Text(
                        createAlbumPlan.disabledReason
                            ?: "The album starts empty. No files will be moved, copied, or deleted.",
                        color = if (createAlbumPlan.disabledReason != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        createAlbumVisible = false
                        createAlbumName = ""
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = createAlbumPlan.enabled,
                    onClick = {
                        pendingAction = createAlbumPlan
                        createAlbumVisible = false
                        mutationError = null
                    },
                ) { Text("Review") }
            },
        )
    }

    mediaToAdd?.takeIf { pendingAction == null }?.let { file ->
        val albums = catalog?.albums.orEmpty()
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    mediaToAdd = null
                    mutationError = null
                }
            },
            title = { Text("Add ${file.name} to album") },
            text = {
                when {
                    mutationError != null && catalog == null -> Text(
                        requireNotNull(mutationError),
                        color = MaterialTheme.colorScheme.error,
                    )
                    catalog == null -> Row(
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Loading albums…")
                    }
                    albums.isEmpty() -> Text("Create an album first.")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        listItems(albums, key = NativeMediaCollection::key) { album ->
                            val plan = planAddFileToMediaCollection(album, file, userId)
                            FilledTonalButton(
                                enabled = plan.enabled,
                                onClick = {
                                    pendingAction = plan
                                    mutationError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(album.name)
                                    if (!plan.enabled) {
                                        Text(
                                            requireNotNull(plan.disabledReason),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mediaToAdd = null }) { Text("Close") }
            },
        )
    }

    pendingAction?.let { plan ->
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    pendingAction = null
                    mutationError = null
                }
            },
            title = { Text(plan.confirmation.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(plan.confirmation.message)
                    Text(
                        plan.disabledReason
                            ?: mutationError
                            ?: when (plan.risk) {
                                NativeMediaCollectionActionRisk.CollectionStructure ->
                                    "This creates an empty Photos album only."
                                NativeMediaCollectionActionRisk.CollectionMembership ->
                                    "This changes album membership only. The original file is not deleted."
                            },
                        color = when {
                            plan.disabledReason != null || mutationError != null ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (mutationRunning) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("Updating album…")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        pendingAction = null
                        mutationError = null
                    },
                ) { Text(if (plan.enabled) "Cancel" else "Close") }
            },
            confirmButton = {
                if (plan.enabled) {
                    Button(
                        enabled = !mutationRunning,
                        onClick = {
                            mutationRunning = true
                            mutationError = null
                            scope.launch {
                                runCatching {
                                    collectionMutationService.executeConfirmed(
                                        session = session,
                                        plan = plan,
                                        confirmed = true,
                                    )
                                }.onSuccess { result ->
                                    when (result.action) {
                                        NativeMediaCollectionAction.CreateCollection -> {
                                            createAlbumName = ""
                                            catalog = null
                                            loadAttempt += 1
                                        }
                                        NativeMediaCollectionAction.AddItem -> {
                                            catalog = catalog?.let { currentCatalog ->
                                                currentCatalog.copy(
                                                    albums = currentCatalog.albums.map { album ->
                                                        if (album.key == plan.collectionKey && !result.alreadyPresent) {
                                                            album.copy(itemCount = album.itemCount?.plus(1))
                                                        } else {
                                                            album
                                                        }
                                                    },
                                                )
                                            }
                                            mediaToAdd = null
                                        }
                                        NativeMediaCollectionAction.RemoveItem -> Unit
                                    }
                                    pendingAction = null
                                    mutationRunning = false
                                    mutationError = null
                                }.onFailure { failure ->
                                    mutationRunning = false
                                    mutationError = failure.message ?: "Could not update this album."
                                }
                            }
                        },
                    ) { Text(plan.confirmation.confirmLabel) }
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            "Photos & Memories",
            when (mode) {
                MediaMode.Timeline -> "Recent server media"
                MediaMode.Collections -> "Albums and tags"
                MediaMode.People -> "Recognized people"
            },
            onBack,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = NextcloudSpacing.XLarge, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            item {
                FilterChip(
                    selected = mode == MediaMode.Timeline,
                    onClick = { onModeChanged(MediaMode.Timeline) },
                    label = { Text("Timeline") },
                    leadingIcon = { Icon(NextcloudIcons.Photo, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            item {
                FilterChip(
                    selected = mode == MediaMode.Collections,
                    onClick = { onModeChanged(MediaMode.Collections) },
                    label = { Text("Albums & tags") },
                    leadingIcon = { Icon(NextcloudIcons.Tag, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            item {
                FilterChip(
                    selected = mode == MediaMode.People,
                    onClick = { onModeChanged(MediaMode.People) },
                    label = { Text("People") },
                    leadingIcon = { Icon(NextcloudIcons.People, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        if (mode == MediaMode.Timeline) {
            when {
                mediaError != null -> ErrorMessage(requireNotNull(mediaError)) { mediaLoadAttempt += 1 }
                media == null -> LoadingMessage("Finding photos and RAW previews…")
                media?.isEmpty() == true -> EmptyMessage("No previewable media was found.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val loadedMedia = requireNotNull(media)
                    val stacks = stackMediaFiles(loadedMedia)
                    val viewerSequence = stacks.flatMap(MediaStack::members)
                    items(stacks, key = MediaStack::id) { stack ->
                        MediaTile(
                            services = services,
                            session = session,
                            file = stack.cover,
                            badge = stack.badge,
                            backupStatus = mediaBackupStatuses[stack.cover.path.trim('/')],
                            onClick = { onOpenMedia(stack.cover, viewerSequence) },
                            onLongClick = {
                                mediaToAdd = stack.cover
                                mutationError = null
                                if (catalog == null) {
                                    scope.launch {
                                        runCatching { collectionService.loadCatalog(session) }
                                            .onSuccess { catalog = it }
                                            .onFailure {
                                                mutationError = it.message ?: "Could not load albums."
                                            }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        } else if (mode == MediaMode.Collections) {
            when {
                error != null -> ErrorMessage(requireNotNull(error)) {
                    catalog = null
                    loadAttempt += 1
                }
                catalog == null -> LoadingMessage("Loading albums and tags…")
                else -> NativeMediaCollectionBrowser(
                    catalog = requireNotNull(catalog),
                    state = browserState,
                    services = services,
                    session = session,
                    onStateChange = { browserState = it },
                    onCreateAlbum = {
                        createAlbumName = ""
                        createAlbumVisible = true
                        mutationError = null
                    },
                    onOpenCollection = { collection ->
                        requestGeneration += 1
                        selectedCollection = collection
                        dayIndex = null
                        collectionItems = emptyList()
                        resolvedFiles = emptyMap()
                        backupStatuses = emptyMap()
                        cursor = null
                        loading = true
                        error = null
                        scope.launch { loadCollectionPage(collection, reset = true) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            val people = peopleByBackend[peopleBackend]
            val gallery = buildPeopleGalleryPresentation(
                people = people.orEmpty(),
                backend = peopleBackend,
                query = peopleSearch,
                nameFilter = peopleNameFilter,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                item {
                    FilterChip(
                        selected = peopleBackend == NextcloudPeopleBackend.Recognize,
                        onClick = {
                            peopleBackend = NextcloudPeopleBackend.Recognize
                            peopleError = null
                        },
                        label = { Text("Recognize") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleBackend == NextcloudPeopleBackend.FaceRecognition,
                        onClick = {
                            peopleBackend = NextcloudPeopleBackend.FaceRecognition
                            peopleError = null
                        },
                        label = { Text("Face Recognition") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleNameFilter == PeopleNameFilter.All,
                        onClick = { peopleNameFilter = PeopleNameFilter.All },
                        label = { Text("All ${gallery.totalCount}") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleNameFilter == PeopleNameFilter.Named,
                        onClick = { peopleNameFilter = PeopleNameFilter.Named },
                        label = { Text("Named ${gallery.namedCount}") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleNameFilter == PeopleNameFilter.Unnamed,
                        onClick = { peopleNameFilter = PeopleNameFilter.Unnamed },
                        label = { Text("Unnamed ${gallery.unnamedCount}") },
                    )
                }
            }
            if (people != null) {
                OutlinedTextField(
                    value = peopleSearch,
                    onValueChange = { peopleSearch = it },
                    modifier = Modifier.fillMaxWidth().padding(
                        start = NextcloudSpacing.Large,
                        end = NextcloudSpacing.Large,
                        bottom = NextcloudSpacing.Small,
                    ),
                    label = { Text("Find a person") },
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    singleLine = true,
                )
            }
            val visiblePeople = gallery.people
            when {
                peopleError != null -> ErrorMessage(requireNotNull(peopleError)) {
                    peopleByBackend.remove(peopleBackend)
                    peopleLoadAttempt += 1
                }
                people == null -> LoadingMessage("Loading recognized people…")
                people.isEmpty() -> EmptyMessage("Memories has not returned any recognized people yet.")
                visiblePeople.isEmpty() -> EmptyMessage("No recognized person matches your search.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    items(visiblePeople, key = NextcloudPerson::id) { person ->
                        PersonTile(
                            services = services,
                            session = session,
                            person = person,
                            onClick = { onOpenPerson(person) },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun PersonTile(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    person: NextcloudPerson,
    onClick: () -> Unit,
) {
    var image by remember(person.id, person.coverFileId, person.coverEtag) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(person.id, person.coverFileId, person.coverEtag) {
        if (person.coverFileId == null) return@LaunchedEffect
        image = runCatching { decodePlatformImage(services.loadPersonCoverCached(session, person)) }.getOrNull()
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(NextcloudTheme.colors.appIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            image?.let {
                Image(it, person.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(
                NextcloudIcons.People,
                contentDescription = person.name,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
            Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                "${person.count} ${if (person.count == 1) "photo" else "photos"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonMediaScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    currentUserId: String,
    recognizeBridge: RecognizeBridgeDiscovery,
    person: NextcloudPerson,
    onBack: () -> Unit,
    onOpenMedia: (NextcloudFile, List<NextcloudFile>) -> Unit,
) {
    var mediaItems by remember(person.id, person.backend) { mutableStateOf<List<NativeMediaItem>?>(null) }
    var resolvedMediaFiles by remember(person.id, person.backend) {
        mutableStateOf<Map<Long, NextcloudFile>>(emptyMap())
    }
    var mediaBackupStatuses by remember(person.id, person.backend) {
        mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())
    }
    var mediaDayIndex by remember(person.id, person.backend) { mutableStateOf<PersonMediaDayIndex?>(null) }
    var mediaCursor by remember(person.id, person.backend) { mutableStateOf<NativeMediaDayCursor?>(null) }
    var mediaLoadingMore by remember(person.id, person.backend) { mutableStateOf(false) }
    var mediaLoadMoreError by remember(person.id, person.backend) { mutableStateOf<String?>(null) }
    var mediaRequestGeneration by remember(person.id, person.backend) { mutableStateOf(0) }
    var error by remember(person.id, person.backend) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(person.id, person.backend) { mutableStateOf(0) }
    var actionMenuExpanded by remember(person.id) { mutableStateOf(false) }
    var showFaceRectangles by rememberSaveable(currentUserId, person.id, person.backend) { mutableStateOf(false) }
    var photoSelectionMode by remember(person.id) { mutableStateOf<PersonPhotoSelectionMode?>(null) }
    var renameDialogVisible by remember(person.id) { mutableStateOf(false) }
    var renameDraft by remember(person.id) { mutableStateOf(person.name) }
    var renameValidationError by remember(person.id) { mutableStateOf<String?>(null) }
    var mergePickerVisible by remember(person.id) { mutableStateOf(false) }
    var mergeTargets by remember(person.id) { mutableStateOf<List<NextcloudPerson>?>(null) }
    var mergeTargetsError by remember(person.id) { mutableStateOf<String?>(null) }
    var mergeSearch by remember(person.id) { mutableStateOf("") }
    var mergePreparing by remember(person.id) { mutableStateOf(false) }
    var mergePrepareError by remember(person.id) { mutableStateOf<String?>(null) }
    var pendingMergeWorkflow by remember(person.id) { mutableStateOf<PersonMergeWorkflow?>(null) }
    var pendingPlan by remember(person.id) { mutableStateOf<PeopleActionPlan?>(null) }
    var recognizedFaces by remember(person.id) { mutableStateOf<List<RecognizedFaceMedia>?>(null) }
    var recognizedFaceDayIndex by remember(person.id) { mutableStateOf<PersonMediaDayIndex?>(null) }
    var recognizedFaceCursor by remember(person.id) { mutableStateOf<NativeMediaDayCursor?>(null) }
    var recognizedFacesLoadingMore by remember(person.id) { mutableStateOf(false) }
    var recognizedFacesLoadMoreError by remember(person.id) { mutableStateOf<String?>(null) }
    var recognizedFacesRequestGeneration by remember(person.id) { mutableStateOf(0) }
    var recognizedFacesError by remember(person.id) { mutableStateOf<String?>(null) }
    var recognizedFacesLoadAttempt by remember(person.id) { mutableStateOf(0) }
    val personReference = remember(person) { person.toMediaReference() }
    val personMediaReadService = remember(services) { NextcloudPersonMediaReadService(services) }
    val recognizedFaceReadService = remember(services) { RecognizedFaceReadService(services) }
    val mutationService = remember(services, session.serverUrl, session.loginName) {
        PeopleMutationService(services)
    }
    val peopleMergeService = remember(recognizedFaceReadService, mutationService) {
        PeopleMergeService(recognizedFaceReadService, mutationService)
    }
    val scope = rememberCoroutineScope()
    val mediaGridState = rememberLazyGridState()
    val mediaFiles = remember(personReference, mediaItems, resolvedMediaFiles) {
        mediaItems?.map { item ->
            resolvedMediaFiles[item.fileId] ?: item.toPersonMediaFile(personReference)
        }
    }
    val actionSupport = remember(currentUserId, person.id, person.backend, recognizeBridge) {
        PeopleActionSupport(
            currentUserId = currentUserId,
            memoriesPeopleApiAvailable = true,
            recognizeDavAvailable = true,
            recognizeApiKeyRequired = true,
            recognizeApiKeyAvailable = recognizeBridge is RecognizeBridgeDiscovery.Available,
        )
    }

    suspend fun loadPersonMediaPage(reset: Boolean) {
        val generation = ++mediaRequestGeneration
        mediaLoadingMore = true
        if (reset) {
            error = null
            mediaLoadMoreError = null
        } else {
            mediaLoadMoreError = null
        }
        val result = runCatching {
            val index = if (reset || mediaDayIndex?.person != personReference) {
                personMediaReadService.loadDayIndex(session, personReference)
            } else {
                requireNotNull(mediaDayIndex)
            }
            val page = personMediaReadService.loadPage(
                session = session,
                person = personReference,
                index = index,
                cursor = if (reset) null else mediaCursor,
            )
            val resolved = if (page.items.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.resolveFilesById(
                        session = session,
                        userId = currentUserId,
                        fileIds = page.items.map(NativeMediaItem::fileId),
                    )
                }.getOrDefault(emptyMap())
            }
            val statuses = if (resolved.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.loadMediaBackupStatuses(session, currentUserId, resolved.values)
                }.getOrDefault(emptyMap())
            }
            Triple(index, page, resolved) to statuses
        }
        if (generation != mediaRequestGeneration) return
        result.onSuccess { (loaded, statuses) ->
            val (index, page, resolved) = loaded
            mediaDayIndex = index
            mediaItems = if (reset) {
                page.items
            } else {
                (mediaItems.orEmpty() + page.items).distinctBy(NativeMediaItem::fileId)
            }
            resolvedMediaFiles = if (reset) resolved else resolvedMediaFiles + resolved
            mediaBackupStatuses = if (reset) statuses else mediaBackupStatuses + statuses
            mediaCursor = page.nextCursor
        }.onFailure { failure ->
            val message = failure.message ?: "Could not load photos for this person."
            if (reset || mediaItems.isNullOrEmpty()) error = message else mediaLoadMoreError = message
        }
        mediaLoadingMore = false
    }

    suspend fun loadRecognizedFacePage(reset: Boolean) {
        val generation = ++recognizedFacesRequestGeneration
        recognizedFacesLoadingMore = true
        if (reset) {
            recognizedFacesError = null
            recognizedFacesLoadMoreError = null
        } else {
            recognizedFacesLoadMoreError = null
        }
        val result = runCatching {
            val index = if (reset || recognizedFaceDayIndex?.person != personReference) {
                recognizedFaceReadService.loadDayIndex(session, personReference)
            } else {
                requireNotNull(recognizedFaceDayIndex)
            }
            val page = recognizedFaceReadService.loadPage(
                session = session,
                person = personReference,
                index = index,
                cursor = if (reset) null else recognizedFaceCursor,
            )
            index to page
        }
        if (
            generation != recognizedFacesRequestGeneration ||
            photoSelectionMode != PersonPhotoSelectionMode.RemoveFace
        ) return
        result.onSuccess { (index, page) ->
            recognizedFaceDayIndex = index
            recognizedFaces = if (reset) {
                page.faces
            } else {
                (recognizedFaces.orEmpty() + page.faces).distinctBy(RecognizedFaceMedia::detectionId)
            }
            recognizedFaceCursor = page.nextCursor
        }.onFailure { failure ->
            val message = failure.message ?: "Could not load exact face assignments."
            if (reset || recognizedFaces.isNullOrEmpty()) {
                recognizedFacesError = message
            } else {
                recognizedFacesLoadMoreError = message
            }
        }
        recognizedFacesLoadingMore = false
    }

    fun closePhotoSelection() {
        if (photoSelectionMode == PersonPhotoSelectionMode.RemoveFace) {
            recognizedFacesRequestGeneration += 1
            recognizedFacesLoadingMore = false
        }
        photoSelectionMode = null
    }

    DisposableEffect(person.id, person.backend) {
        onDispose {
            mediaRequestGeneration += 1
            mediaLoadingMore = false
        }
    }
    LaunchedEffect(person.id, person.backend, loadAttempt) {
        mediaItems = null
        resolvedMediaFiles = emptyMap()
        mediaDayIndex = null
        mediaCursor = null
        mediaLoadMoreError = null
        error = null
        loadPersonMediaPage(reset = true)
    }
    LaunchedEffect(person.id, person.backend, services) {
        services.observeMediaBackupStatusChanges(session).collectLatest {
            val visibleFiles = resolvedMediaFiles.values
            if (visibleFiles.isNotEmpty()) {
                val statuses = runCatching {
                    services.loadMediaBackupStatuses(session, currentUserId, visibleFiles)
                }.getOrDefault(emptyMap())
                mediaBackupStatuses = mediaBackupStatuses + statuses
            }
        }
    }
    LaunchedEffect(mergePickerVisible, person.id, person.backend) {
        if (!mergePickerVisible || mergeTargets != null) return@LaunchedEffect
        mergeTargetsError = null
        runCatching { services.listPeople(session, person.backend) }
            .onSuccess { people ->
                mergeTargets = people.filter { candidate ->
                    candidate.id != person.id && candidate.userId == person.userId
                }
            }
            .onFailure { mergeTargetsError = it.message ?: "Could not load merge targets." }
    }
    LaunchedEffect(photoSelectionMode, person.id, recognizedFacesLoadAttempt) {
        if (photoSelectionMode != PersonPhotoSelectionMode.RemoveFace) return@LaunchedEffect
        recognizedFaces = null
        recognizedFaceDayIndex = null
        recognizedFaceCursor = null
        recognizedFacesLoadMoreError = null
        recognizedFacesError = null
        loadRecognizedFacePage(reset = true)
    }

    val menuItems = personActionMenuItems(
        person = personReference,
        support = actionSupport,
        hasSelectablePhoto = mediaFiles.orEmpty().any { !it.isDirectory && it.fileId != null },
        hasDirectFaceReferences =
            NextcloudPeopleBackend.fromApiValue(person.backend) == NextcloudPeopleBackend.Recognize,
    )
    val faceRectanglesAvailable = remember(mediaItems) {
        mediaItems.orEmpty().any { item -> item.faceOutlineGeometryOrNull() != null }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            person.name,
            when (photoSelectionMode) {
                PersonPhotoSelectionMode.Cover -> "Choose a new cover photo"
                PersonPhotoSelectionMode.RemoveFace -> "Choose a face to remove"
                null -> mediaFiles?.let { "${it.size} loaded · ${person.count} recognized" } ?: "Recognized photos"
            },
            onBack = {
                if (photoSelectionMode != null) closePhotoSelection() else onBack()
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { actionMenuExpanded = true }) {
                        Icon(NextcloudIcons.More, contentDescription = "Person actions")
                    }
                    DropdownMenu(
                        expanded = actionMenuExpanded,
                        onDismissRequest = { actionMenuExpanded = false },
                        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    ) {
                        if (faceRectanglesAvailable) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(if (showFaceRectangles) "Hide face outlines" else "Show face outlines")
                                        Text(
                                            "See which recognized face matched each photo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(NextcloudIcons.People, contentDescription = null)
                                },
                                onClick = {
                                    showFaceRectangles = !showFaceRectangles
                                    actionMenuExpanded = false
                                },
                            )
                        }
                        menuItems.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(item.label)
                                        item.disabledReason?.let { reason ->
                                            Text(
                                                reason,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                enabled = item.enabled,
                                onClick = {
                                    actionMenuExpanded = false
                                    when (item.action) {
                                        PeopleAction.RenamePerson -> {
                                            renameDraft = person.name
                                            renameValidationError = null
                                            renameDialogVisible = true
                                        }
                                        PeopleAction.MergePerson -> {
                                            mergeSearch = ""
                                            mergeTargets = null
                                            mergeTargetsError = null
                                            mergePickerVisible = true
                                        }
                                        PeopleAction.SetCover -> photoSelectionMode = PersonPhotoSelectionMode.Cover
                                        PeopleAction.RemoveFace -> photoSelectionMode = PersonPhotoSelectionMode.RemoveFace
                                        PeopleAction.DeletePerson -> pendingPlan = planDeletePerson(
                                            person = personReference,
                                            personDisplayName = person.name,
                                            support = actionSupport,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
        photoSelectionMode?.takeIf { it == PersonPhotoSelectionMode.Cover }?.let { mode ->
            PersonPhotoSelectionBanner(
                title = when (mode) {
                    PersonPhotoSelectionMode.Cover -> "Choose one photo to use as ${person.name}’s cover."
                    PersonPhotoSelectionMode.RemoveFace ->
                        "Choose the exact face to remove. The source photo will stay in Files."
                },
                onCancel = ::closePhotoSelection,
            )
        }
        when {
            photoSelectionMode == PersonPhotoSelectionMode.RemoveFace -> when {
                recognizedFacesError != null -> ErrorMessage(requireNotNull(recognizedFacesError)) {
                    recognizedFacesLoadAttempt += 1
                }
                recognizedFaces == null -> LoadingMessage("Loading exact face assignments…")
                else -> RecognizedFaceRemovalPicker(
                    services = services,
                    session = session,
                    person = personReference,
                    personDisplayName = person.name,
                    faces = requireNotNull(recognizedFaces),
                    support = actionSupport,
                    loadingMore = recognizedFacesLoadingMore,
                    canLoadMore = recognizedFaceCursor != null,
                    loadMoreError = recognizedFacesLoadMoreError,
                    onLoadMore = {
                        if (!recognizedFacesLoadingMore && recognizedFaceCursor != null) {
                            scope.launch { loadRecognizedFacePage(reset = false) }
                        }
                    },
                    onPlanSelected = { plan ->
                        pendingPlan = plan
                        closePhotoSelection()
                    },
                    onCancel = ::closePhotoSelection,
                )
            }
            error != null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
            mediaItems == null -> LoadingMessage("Loading ${person.name}…")
            mediaItems?.isEmpty() == true -> EmptyMessage("No photos were returned for this person.")
            else -> {
                val loadedItems = requireNotNull(mediaItems)
                val loadedFiles = requireNotNull(mediaFiles)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    state = mediaGridState,
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(loadedItems, key = NativeMediaItem::fileId) { item ->
                        val file = resolvedMediaFiles[item.fileId] ?: item.toPersonMediaFile(personReference)
                        val selectable = !file.isDirectory && file.fileId != null
                        MediaTile(
                            services = services,
                            session = session,
                            file = file,
                            badge = if (photoSelectionMode != null && selectable) "Choose" else null,
                            backupStatus = mediaBackupStatuses[file.path.trim('/')],
                            faceRectangle = item.faceRectangle.takeIf { showFaceRectangles },
                            sourceWidth = item.width,
                            sourceHeight = item.height,
                            onClick = {
                                when (photoSelectionMode) {
                                    PersonPhotoSelectionMode.Cover -> if (selectable) {
                                        pendingPlan = planSetPersonCover(
                                            person = personReference,
                                            personDisplayName = person.name,
                                            sourceFile = file,
                                            support = actionSupport,
                                        )
                                        photoSelectionMode = null
                                    }
                                    PersonPhotoSelectionMode.RemoveFace -> Unit
                                    null -> onOpenMedia(file, loadedFiles)
                                }
                            },
                        )
                    }
                    loadMoreItem(
                        loadingMore = mediaLoadingMore,
                        canLoadMore = mediaCursor != null,
                        error = mediaLoadMoreError,
                        onLoadMore = {
                            if (!mediaLoadingMore && mediaCursor != null) {
                                scope.launch { loadPersonMediaPage(reset = false) }
                            }
                        },
                    )
                }
            }
        }
    }

    if (renameDialogVisible) {
        val validationMessage = validatePersonRename(personReference, renameDraft)
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text("Rename ${person.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = {
                            renameDraft = it
                            renameValidationError = null
                        },
                        label = { Text("Person name") },
                        singleLine = true,
                        isError = validationMessage != null || renameValidationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (renameValidationError ?: validationMessage)?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { renameDialogVisible = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    enabled = validationMessage == null,
                    onClick = {
                        runCatching {
                            planRenamePerson(
                                person = personReference,
                                currentDisplayName = person.name,
                                requestedName = renameDraft,
                                support = actionSupport,
                            )
                        }.onSuccess { plan ->
                            renameDialogVisible = false
                            pendingPlan = plan
                        }.onFailure { failure ->
                            renameValidationError = failure.message ?: "This name cannot be used."
                        }
                    },
                ) { Text("Review") }
            },
        )
    }

    if (mergePickerVisible) {
        val visibleTargets = mergeTargets.orEmpty().filter { target ->
            mergeSearch.isBlank() || target.name.contains(mergeSearch.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { mergePickerVisible = false },
            title = { Text("Merge ${person.name} into…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "Choose the person who should remain. Nothing changes while you review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = mergeSearch,
                        onValueChange = { mergeSearch = it },
                        label = { Text("Search people") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        mergeTargetsError != null -> Text(
                            requireNotNull(mergeTargetsError),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        mergeTargets == null -> Row(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Loading people…")
                        }
                        visibleTargets.isEmpty() -> Text(
                            "No other recognized person matches.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            listItems(visibleTargets, key = NextcloudPerson::id) { target ->
                                Surface(
                                    onClick = {
                                        val targetReference = target.toMediaReference()
                                        val plan = planMergePeople(
                                            source = personReference,
                                            sourceDisplayName = person.name,
                                            target = targetReference,
                                            targetDisplayName = target.name,
                                            support = actionSupport,
                                        )
                                        mergePickerVisible = false
                                        mergePreparing = true
                                        mergePrepareError = null
                                        pendingMergeWorkflow = null
                                        scope.launch {
                                            runCatching {
                                                peopleMergeService.prepare(
                                                    session = session,
                                                    source = personReference,
                                                    target = targetReference,
                                                )
                                            }.onSuccess { workflow ->
                                                pendingMergeWorkflow = workflow
                                                pendingPlan = plan
                                            }.onFailure { failure ->
                                                mergePrepareError = failure.message
                                                    ?: "Could not build a complete face inventory."
                                            }
                                            mergePreparing = false
                                        }
                                    },
                                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                                    color = NextcloudTheme.colors.appTile,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(NextcloudIcons.People, contentDescription = null)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(target.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${target.count} ${if (target.count == 1) "photo" else "photos"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(NextcloudIcons.ChevronRight, contentDescription = null)
                                    }
                                }
                                Spacer(Modifier.size(NextcloudSpacing.Small))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergePickerVisible = false }) { Text("Cancel") } },
        )
    }

    if (mergePreparing || mergePrepareError != null) {
        AlertDialog(
            onDismissRequest = {
                if (!mergePreparing) mergePrepareError = null
            },
            title = { Text("Preparing safe merge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    if (mergePreparing) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Loading every face assigned to ${person.name}…")
                        }
                        Text(
                            "No faces are changed during this inventory step.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            requireNotNull(mergePrepareError),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                if (!mergePreparing) {
                    TextButton(onClick = { mergePrepareError = null }) { Text("Close") }
                }
            },
        )
    }

    pendingPlan?.let { plan ->
        PeopleActionPlanReviewDialog(
            plan = plan,
            session = session,
            recognizeBridge = recognizeBridge,
            mutationService = mutationService,
            mergeService = peopleMergeService,
            initialMergeWorkflow = pendingMergeWorkflow,
            onDismiss = {
                pendingPlan = null
                pendingMergeWorkflow = null
            },
            onSucceeded = {
                pendingPlan = null
                pendingMergeWorkflow = null
                when (plan.action) {
                    PeopleAction.SetCover,
                    PeopleAction.RemoveFace,
                    -> loadAttempt += 1
                    PeopleAction.RenamePerson,
                    PeopleAction.MergePerson,
                    PeopleAction.DeletePerson,
                    -> onBack()
                }
            },
        )
    }
}

@Composable
private fun PersonPhotoSelectionBanner(title: String, onCancel: () -> Unit) {
    Surface(color = NextcloudTheme.colors.appTile) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Large,
                vertical = NextcloudSpacing.Medium,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NextcloudIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun PeopleActionPlanReviewDialog(
    plan: PeopleActionPlan,
    session: NextcloudSession,
    recognizeBridge: RecognizeBridgeDiscovery,
    mutationService: PeopleMutationService,
    mergeService: PeopleMergeService,
    initialMergeWorkflow: PersonMergeWorkflow?,
    onDismiss: () -> Unit,
    onSucceeded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember(plan) { mutableStateOf(false) }
    var resultMessage by remember(plan) { mutableStateOf<String?>(null) }
    var resultIsError by remember(plan) { mutableStateOf(false) }
    var mergeWorkflow by remember(plan, initialMergeWorkflow) { mutableStateOf(initialMergeWorkflow) }
    var mergePaused by remember(plan) { mutableStateOf(false) }
    val needsBridge = PeopleActionAuthRequirement.ShortLivedRecognizeApiKey in plan.authRequirements
    val bridgeAvailable = recognizeBridge is RecognizeBridgeDiscovery.Available
    val isMerge = plan.action == PeopleAction.MergePerson
    val executable = plan.enabled && (!needsBridge || bridgeAvailable) && (!isMerge || mergeWorkflow != null)

    fun runMerge(refreshBeforeResume: Boolean) {
        val workflow = mergeWorkflow ?: return
        running = true
        resultMessage = null
        resultIsError = false
        scope.launch {
            val reconciliation = if (refreshBeforeResume) {
                runCatching { mergeService.reconcileAfterRefresh(session, workflow) }
                    .getOrElse { failure ->
                        running = false
                        resultIsError = true
                        resultMessage = failure.message ?: "Could not refresh both people."
                        return@launch
                    }
            } else {
                null
            }
            val result = mergeService.runConfirmed(
                session = session,
                bridgeDiscovery = recognizeBridge,
                plan = plan,
                initialWorkflow = reconciliation?.workflow ?: workflow,
                initialReconciliation = reconciliation,
                onProgress = { updated -> mergeWorkflow = updated },
            )
            running = false
            when (result) {
                is PeopleMergeRunResult.Completed -> onSucceeded()
                is PeopleMergeRunResult.Paused -> {
                    mergeWorkflow = result.workflow
                    mergePaused = true
                    resultIsError = true
                    resultMessage = if (result.outcomeUnknown) {
                        "A face move has an unknown result. Refresh both people before deciding whether to resume."
                    } else {
                        "The server rejected a face move. Refresh both people before resuming."
                    }
                }
                is PeopleMergeRunResult.Unavailable -> {
                    resultIsError = true
                    resultMessage = result.message
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(plan.confirmation.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(plan.confirmation.message)
                Surface(
                    color = if (resultIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                ) {
                    Text(
                        text = resultMessage
                            ?: plan.disabledReason
                            ?: when {
                                needsBridge && !bridgeAvailable ->
                                    "Install and enable the Obiente Native Bridge to make this Recognize change."
                                isMerge && mergeWorkflow != null -> {
                                    val progress = requireNotNull(mergeWorkflow).progress
                                    if (running) {
                                        "Moved ${progress.succeeded} of ${progress.total} faces…"
                                    } else {
                                        "Complete inventory ready: ${progress.total} faces. The merge stops on the first rejected or uncertain move."
                                    }
                                }
                                isMerge -> "A complete face inventory is required before merging."
                                else -> "Ready. Nothing changes until you press ${plan.confirmation.confirmLabel}."
                            },
                        modifier = Modifier.padding(NextcloudSpacing.Medium),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resultIsError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = executable && !running,
                onClick = {
                    if (isMerge) {
                        runMerge(refreshBeforeResume = mergePaused)
                        return@Button
                    }
                    running = true
                    resultMessage = null
                    resultIsError = false
                    scope.launch {
                        when (
                            val result = mutationService.execute(
                                session = session,
                                bridgeDiscovery = recognizeBridge,
                                plan = plan,
                                confirmed = true,
                            )
                        ) {
                            is PeopleMutationServiceResult.Outcome -> when (val outcome = result.outcome) {
                                is PeopleMutationExecutionOutcome.SingleSucceeded -> {
                                    running = false
                                    onSucceeded()
                                }
                                is PeopleMutationExecutionOutcome.SingleRejected -> {
                                    running = false
                                    resultIsError = true
                                    resultMessage = "The server rejected this change (HTTP ${outcome.status}). Nothing was retried."
                                }
                                is PeopleMutationExecutionOutcome.SingleOutcomeUnknown -> {
                                    running = false
                                    resultIsError = true
                                    resultMessage = "${outcome.reason} Refresh before trying again."
                                }
                                is PeopleMutationExecutionOutcome.MergeAdvanced,
                                is PeopleMutationExecutionOutcome.MergePaused,
                                is PeopleMutationExecutionOutcome.MergeCompleted,
                                -> {
                                    running = false
                                    resultIsError = true
                                    resultMessage = "Merge progress is not connected to this dialog yet."
                                }
                            }
                            is PeopleMutationServiceResult.TokenUnavailable -> {
                                running = false
                                resultIsError = true
                                resultMessage = result.message
                            }
                            is PeopleMutationServiceResult.Planning -> {
                                running = false
                                resultIsError = true
                                resultMessage = result.result.peoplePlanningMessage()
                            }
                        }
                    }
                },
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (mergePaused) "Refresh and resume" else plan.confirmation.confirmLabel)
                }
            }
        },
    )
}

private fun PeopleExecutionPlanningResult.peoplePlanningMessage(): String = when (this) {
    is PeopleExecutionPlanningResult.Disabled -> reason
    is PeopleExecutionPlanningResult.ConfirmationRequired -> "Confirm this change before continuing."
    PeopleExecutionPlanningResult.FaceInventoryRequired -> "Refresh the face inventory before merging."
    is PeopleExecutionPlanningResult.BridgeTokenRequired -> "A fresh Recognize key is required."
    is PeopleExecutionPlanningResult.ReconciliationRequired -> reason
    is PeopleExecutionPlanningResult.Ready -> "The change is ready but was not sent."
    is PeopleExecutionPlanningResult.Completed -> "The merge is already complete."
    is PeopleExecutionPlanningResult.Invalid -> reason
}

private fun validatePersonRename(person: PersonMediaReference, value: String): String? {
    val name = value.trim()
    return when {
        name.isEmpty() -> "Enter a name."
        '/' in name -> "Names cannot contain a slash."
        name.toLongOrNull() != null -> "Recognize does not allow a number-only name."
        name == person.lookupName -> "Enter a different name."
        else -> null
    }
}

@Composable
private fun MediaTile(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    file: NextcloudFile,
    badge: String? = null,
    backupStatus: MediaBackupStatus? = null,
    faceRectangle: NativeFaceRectangle? = null,
    sourceWidth: Int? = null,
    sourceHeight: Int? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var image by remember(file.fileId) { mutableStateOf<ImageBitmap?>(null) }
    val faceOutlineGeometry = remember(faceRectangle, sourceWidth, sourceHeight) {
        nativeFaceOutlineGeometryOrNull(faceRectangle, sourceWidth, sourceHeight)
    }
    LaunchedEffect(file.fileId) {
        file.fileId ?: return@LaunchedEffect
        if (!file.hasPreview) return@LaunchedEffect
        image = runCatching { decodePlatformImage(services.loadPreviewCached(session, file)) }.getOrNull()
    }
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).then(
            if (onLongClick == null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            },
        ),
        color = NextcloudTheme.colors.appTile,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    // A face outline must map to the complete source image. Switching to Fit while
                    // it is visible avoids drawing a plausible-looking box over a cropped preview.
                    contentScale = if (faceOutlineGeometry == null) ContentScale.Crop else ContentScale.Fit,
                )
                FaceRectangleOverlay(
                    geometry = faceOutlineGeometry,
                    color = MaterialTheme.colorScheme.primary,
                )
            } ?: Icon(
                NextcloudIcons.Image,
                contentDescription = file.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            badge?.let { label ->
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            backupStatus?.let { status ->
                MediaBackupStatusIndicator(
                    status = status,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun FileInfoScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    file: NextcloudFile,
    onBack: () -> Unit,
    showVersions: Boolean,
    onVersionRestored: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(file.name, if (file.isDirectory) "Folder details" else "File details", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
        ) {
            item {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(NextcloudRadii.Medium)) {
                    Icon(
                        if (file.isDirectory) NextcloudIcons.Folder else fileIcon(file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(18.dp).size(38.dp),
                    )
                }
            }
            item {
                Text(file.name, style = MaterialTheme.typography.headlineMedium)
                Text(
                    file.path,
                    modifier = Modifier.padding(top = NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        FileMetadataLine("Type", if (file.isDirectory) "Folder" else file.mimeType ?: "Unknown")
                        if (!file.isDirectory) FileMetadataLine("Size", formatBytes(file.size))
                        file.lastModified?.let { FileMetadataLine("Modified", it) }
                        if (!file.isDirectory) {
                            FileMetadataLine("Preview", if (file.hasPreview) "Available" else "Unavailable")
                        }
                    }
                }
            }
            if (!file.isDirectory && file.fileId != null) {
                item {
                    FileVersionHistorySection(
                        services = services,
                        session = session,
                        userId = userId,
                        file = file,
                        initiallyExpanded = showVersions,
                        onVersionRestored = onVersionRestored,
                    )
                }
            }
            onEdit?.let { edit ->
                item {
                    Button(onClick = edit) {
                        Icon(NextcloudIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Edit text")
                    }
                }
            }
        }
    }
}

@Composable
private fun FileMetadataLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.padding(start = NextcloudSpacing.Large),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TextEditorScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    file: NextcloudFile,
    onBack: () -> Unit,
) {
    val descriptor = remember(file) { describeDocument(file) }
    val isMarkdown = descriptor.kind == DocumentKind.Markdown
    var originalText by remember(file.path) { mutableStateOf<String?>(null) }
    var draft by remember(file.path) { mutableStateOf("") }
    var etag by remember(file.path) { mutableStateOf(file.etag) }
    var loadingError by remember(file.path) { mutableStateOf<String?>(null) }
    var saveError by remember(file.path) { mutableStateOf<String?>(null) }
    var saving by remember(file.path) { mutableStateOf(false) }
    var confirmSave by remember(file.path) { mutableStateOf(false) }
    var confirmDiscard by remember(file.path) { mutableStateOf(false) }
    var savedMessage by remember(file.path) { mutableStateOf<String?>(null) }
    var markdownViewMode by rememberSaveable(file.path) {
        mutableStateOf(
            if (
                isMarkdown &&
                (file.size == null || file.size <= MAX_RENDERED_MARKDOWN_PREVIEW_BYTES)
            ) {
                MarkdownFileViewMode.Preview
            } else {
                MarkdownFileViewMode.Edit
            },
        )
    }
    val scope = rememberCoroutineScope()
    val dirty = originalText != null && draft != originalText
    val textPresentation = remember(descriptor, draft) {
        planNativeTextPresentation(descriptor, draft.utf8Size())
    }
    val markdownPreviewAvailable = textPresentation == NativeTextPresentation.RenderedMarkdown

    LaunchedEffect(file.path, userId) {
        if (userId.isBlank()) return@LaunchedEffect
        loadingError = null
        runCatching {
            services.downloadFile(
                session = session,
                userId = userId,
                path = file.path,
                maxBytes = MAX_EDITABLE_TEXT_BYTES,
            )
        }.onSuccess { downloaded ->
            runCatching { downloaded.bytes.decodeToString(throwOnInvalidSequence = true) }
                .onSuccess { text ->
                    originalText = text
                    draft = text
                    etag = downloaded.etag ?: file.etag
                }
                .onFailure { loadingError = "This file is not valid UTF-8 text." }
        }.onFailure { loadingError = it.message ?: "Could not download this file." }
    }

    fun requestBack() {
        if (dirty) confirmDiscard = true else onBack()
    }
    PlatformBackHandler(enabled = true, onBack = ::requestBack)

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(file.name, if (dirty) "Unsaved changes" else "Text editor", ::requestBack)
        when {
            loadingError != null -> ErrorMessage(requireNotNull(loadingError))
            originalText == null -> LoadingMessage("Opening ${file.name}…")
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        savedMessage ?: saveError ?: if (etag.isNullOrBlank()) {
                            "Saving is disabled until the server version is verified."
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            saveError != null -> MaterialTheme.colorScheme.error
                            etag.isNullOrBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> NextcloudTheme.colors.success
                        },
                    )
                    Button(
                        enabled = dirty && !saving && !etag.isNullOrBlank(),
                        onClick = { confirmSave = true },
                    ) {
                        Icon(NextcloudIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (saving) "Saving…" else "Save")
                    }
                }
                if (isMarkdown) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Medium,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = markdownViewMode == MarkdownFileViewMode.Preview,
                            onClick = { markdownViewMode = MarkdownFileViewMode.Preview },
                            enabled = markdownPreviewAvailable,
                            label = { Text("Preview") },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.File,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        FilterChip(
                            selected = markdownViewMode == MarkdownFileViewMode.Edit,
                            onClick = { markdownViewMode = MarkdownFileViewMode.Edit },
                            label = { Text("Edit source") },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        if (!markdownPreviewAvailable) {
                            Text(
                                "Rendered preview is limited to " +
                                    "${MAX_RENDERED_MARKDOWN_PREVIEW_BYTES / 1024} KiB.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (isMarkdown && markdownViewMode == MarkdownFileViewMode.Preview) {
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Large,
                        ),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        when {
                            !markdownPreviewAvailable -> Box(
                                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Switch to Edit source to continue.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            draft.isBlank() -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "This document is empty.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> Markdown(
                                content = draft,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(NextcloudSpacing.Large),
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            saveError = null
                            savedMessage = null
                        },
                        modifier = Modifier.fillMaxSize().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Large,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        enabled = !saving,
                    )
                }
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save changes to Nextcloud?") },
            text = { Text("This updates ${file.name} on the server. A conflict will stop the save instead of overwriting newer changes.") },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    confirmSave = false
                    saving = true
                    saveError = null
                    scope.launch {
                        runCatching {
                            services.saveTextFile(
                                session,
                                userId,
                                file.path,
                                draft,
                                requireNotNull(etag?.takeIf(String::isNotBlank)),
                            )
                        }.onSuccess { saved ->
                            originalText = draft
                            etag = saved.etag ?: etag
                            savedMessage = "Saved to Nextcloud"
                        }.onFailure { saveError = it.message ?: "Could not save this file." }
                        saving = false
                    }
                }) { Text("Save") }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your local edits to ${file.name} have not been saved.") },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
            confirmButton = {
                Button(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("Discard") }
            },
        )
    }
}

private enum class MarkdownFileViewMode {
    Preview,
    Edit,
}

@Composable
private fun TalkScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
    onOpenRoom: (TalkRoom) -> Unit,
) {
    var rooms by remember { mutableStateOf<List<TalkRoom>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(loadAttempt) {
        rooms = null
        error = null
        runCatching { services.listTalkRooms(session) }
            .onSuccess { rooms = it }
            .onFailure { error = it.message ?: "Could not load Talk conversations." }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Talk", "Conversations", onBack)
        when {
            error != null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
            rooms == null -> LoadingMessage("Loading conversations…")
            rooms?.isEmpty() == true -> EmptyMessage("No Talk conversations yet.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = NextcloudSpacing.XXLarge)) {
                listItems(requireNotNull(rooms), key = TalkRoom::token) { room ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenRoom(room) }
                            .padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.app("talk"),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(10.dp).size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(room.displayName, fontWeight = FontWeight.SemiBold)
                            room.lastMessage?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (room.unreadMessages > 0) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                                Text(
                                    room.unreadMessages.toString(),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${room.displayName}")
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 84.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    room: TalkRoom,
    onBack: () -> Unit,
    onOpenAttachment: (NextcloudFile) -> Unit,
) {
    var messages by remember(room.token) { mutableStateOf<List<TalkMessage>?>(null) }
    var olderCursor by remember(room.token) { mutableStateOf<Long?>(null) }
    var hasMoreHistory by remember(room.token) { mutableStateOf(false) }
    var loadingEarlier by remember(room.token) { mutableStateOf(false) }
    var historyError by remember(room.token) { mutableStateOf<String?>(null) }
    var draft by remember(room.token) { mutableStateOf("") }
    var error by remember(room.token) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var loadAttempt by remember(room.token) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()
    val orderedMessages = remember(messages) { messages?.sortedBy(TalkMessage::id) }

    suspend fun refresh() {
        val page = services.listTalkMessagePage(session, room.token)
        messages = page.messages
        olderCursor = page.olderCursor
        hasMoreHistory = page.hasMoreHistory
    }
    LaunchedEffect(room.token, loadAttempt) {
        messages = null
        error = null
        runCatching { refresh() }.onFailure { error = it.message ?: "Could not load messages." }
    }
    LaunchedEffect(orderedMessages?.lastOrNull()?.id) {
        val lastIndex = orderedMessages?.lastIndex ?: return@LaunchedEffect
        messageListState.scrollToItem(lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(room.displayName, "Talk", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
                messages == null -> LoadingMessage("Loading messages…")
                messages?.isEmpty() == true -> EmptyMessage("No messages in this conversation yet.")
                else -> LazyColumn(
                    state = messageListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    if ((hasMoreHistory && olderCursor != null) || historyError != null) {
                        item(key = "talk-load-earlier") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                historyError?.let { message ->
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (hasMoreHistory && olderCursor != null) {
                                    TextButton(
                                        enabled = !loadingEarlier,
                                        onClick = {
                                            val cursor = olderCursor ?: return@TextButton
                                            loadingEarlier = true
                                            historyError = null
                                            scope.launch {
                                                runCatching {
                                                    services.listTalkMessagePage(
                                                        session = session,
                                                        token = room.token,
                                                        olderCursor = cursor,
                                                    )
                                                }.onSuccess { page ->
                                                    messages = mergeTalkMessageHistory(
                                                        messages.orEmpty(),
                                                        page.messages,
                                                    )
                                                    olderCursor = page.olderCursor
                                                    hasMoreHistory = page.hasMoreHistory
                                                }.onFailure { failure ->
                                                    historyError =
                                                        failure.message ?: "Could not load earlier messages."
                                                }
                                                loadingEarlier = false
                                            }
                                        },
                                    ) {
                                        if (loadingEarlier) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            Text("Load earlier messages")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    listItems(requireNotNull(orderedMessages), key = TalkMessage::id) { message ->
                        TalkMessageCard(
                            services = services,
                            session = session,
                            message = message,
                            mine = message.actorId == userId,
                            onOpenAttachment = { attachment ->
                                onOpenAttachment(attachment.asNextcloudFile())
                            },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = !sending,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            )
            IconButton(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    val message = draft.trim()
                    sending = true
                    scope.launch {
                        runCatching {
                            services.sendTalkMessage(session, room.token, message)
                            draft = ""
                            refresh()
                        }.onFailure { error = it.message ?: "Could not send message." }
                        sending = false
                    }
                },
            ) { Icon(NextcloudIcons.Send, contentDescription = "Send message") }
        }
    }
}

@Composable
private fun SettingsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    serverInfo: NextcloudServerInfo?,
    themePreference: ThemePreference,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onAdminApps: () -> Unit,
    onOfflineCenter: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    var capabilityRefresh by remember { mutableStateOf(0) }
    val platformCapabilities = remember(services, capabilityRefresh) { services.platformCapabilities() }
    Column(modifier = Modifier.fillMaxSize()) {
        ProductHeader(title = "Settings", showSettings = false)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
        ) {
            item {
                SectionTitle("Appearance")
                Row(
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    ThemePreference.entries.forEach { preference ->
                        FilterChip(
                            selected = themePreference == preference,
                            onClick = { onThemePreferenceChanged(preference) },
                            label = { Text(preference.name) },
                            leadingIcon = {
                                Icon(
                                    when (preference) {
                                        ThemePreference.System -> NextcloudIcons.SystemMode
                                        ThemePreference.Light -> NextcloudIcons.LightMode
                                        ThemePreference.Dark -> NextcloudIcons.DarkMode
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
            item {
                SectionTitle("Account")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Profile,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(serverInfo?.displayName ?: session.loginName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                session.serverUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            serverInfo?.version?.let {
                                Text(
                                    "Nextcloud $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("Files")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onOfflineCenter,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Cloud,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync & offline", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (services.supportsFileOfflineStorage) {
                                    if (services.supportsRecursiveFileOfflineStorage) {
                                        "Folder sync, offline files, conflicts, and storage"
                                    } else {
                                        "Pinned files, downloads, conflicts, and device storage"
                                    }
                                } else {
                                    "Review this platform’s offline file support and limitations"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open Sync & offline",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (platformCapabilities.isNotEmpty()) {
                item {
                    SectionTitle("Device features")
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        platformCapabilities.forEach { status ->
                            Surface(
                                color = NextcloudTheme.colors.appTile,
                                shape = RoundedCornerShape(NextcloudRadii.Card),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        NextcloudIcons.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(status.label, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            status.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    when (status.state) {
                                        PlatformCapabilityState.NeedsPermission,
                                        PlatformCapabilityState.Blocked,
                                        -> TextButton(
                                            onClick = {
                                                services.requestPlatformCapability(status.capability)
                                                capabilityRefresh += 1
                                            },
                                        ) {
                                            Text(if (status.state == PlatformCapabilityState.Blocked) "Settings" else "Enable")
                                        }
                                        PlatformCapabilityState.Granted -> Text("Enabled", color = NextcloudTheme.colors.success)
                                        PlatformCapabilityState.AvailableWithoutPermission -> Text("Available")
                                        PlatformCapabilityState.Unsupported -> Text("Unavailable")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("Administration")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onAdminApps,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Apps,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Server apps", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Install, update, enable, or disable apps as an administrator",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open server app management",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    enabled = !loggingOut,
                    onClick = {
                        loggingOut = true
                        scope.launch {
                            runCatching { services.revokeSession(session) }
                            onLoggedOut()
                        }
                    },
                ) {
                    Icon(NextcloudIcons.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (loggingOut) "Signing out…" else "Sign out and revoke access")
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(
    title: String,
    onSettings: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    showSettings: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = NextcloudSpacing.XLarge),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (showSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onSearch?.let {
                    IconButton(onClick = it) { Icon(NextcloudIcons.Search, contentDescription = "Search Nextcloud") }
                }
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                    Icon(
                        NextcloudIcons.Profile,
                        contentDescription = "Account",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp).size(22.dp),
                    )
                }
                onSettings?.let {
                    IconButton(onClick = it) { Icon(NextcloudIcons.Settings, contentDescription = "Settings") }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun UnifiedSearchSelection.nativeFileParentPathOrNull(): String? {
    if (provider.appId != "files" && !provider.id.startsWith("files")) return null
    val candidate = entry.attributes["path"]
        ?: entry.attributes["filePath"]
        ?: entry.subline?.takeIf { it.startsWith('/') }
        ?: return null
    val segments = candidate.substringBefore('?').trim('/').split('/').filter(String::isNotBlank)
    if (segments.any { it == "." || it == ".." || it.any(Char::isISOControl) }) return null
    val path = segments.joinToString("/")
    return if (segments.lastOrNull() == entry.title) path.substringBeforeLast('/', "") else path
}

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    compact: Boolean = false,
    trailingContent: @Composable () -> Unit = {},
) {
    val workspace = LocalNextcloudWorkspaceCapabilities.current
    val desktop = workspace.isDesktop
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(
                min = when {
                    desktop -> 62.dp
                    compact -> 54.dp
                    else -> 76.dp
                },
            )
            .padding(horizontal = if (desktop) NextcloudSpacing.Large else NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(if (desktop) NextcloudSpacing.Medium else NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (desktop) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        NextcloudIcons.Back,
                        contentDescription = "Back",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            IconButton(onClick = onBack) { Icon(NextcloudIcons.Back, contentDescription = "Back") }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = when {
                    desktop -> MaterialTheme.typography.titleLarge
                    compact -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.headlineSmall
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LoadingMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(top = NextcloudSpacing.Large))
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, modifier = Modifier.padding(NextcloudSpacing.XLarge), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(modifier = Modifier.padding(NextcloudSpacing.XLarge), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(NextcloudIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.error)
        onRetry?.let { retry -> OutlinedButton(onClick = retry) { Text("Try again") } }
    }
}

private fun fileIcon(file: NextcloudFile): ImageVector = when {
    file.mimeType?.startsWith("image/") == true -> NextcloudIcons.Image
    file.mimeType?.startsWith("video/") == true -> NextcloudIcons.Video
    else -> NextcloudIcons.File
}

private fun nativeSubtitle(appId: String): String = when (appId) {
    "files" -> "Browse your server files"
    "photos", "memories" -> "Photos, videos and RAW previews"
    "spreed", "talk" -> "Continue your conversations"
    "activity" -> "See recent changes across your cloud"
    "notes" -> "Write and organize Markdown notes"
    "dashboard" -> "See your cloud at a glance"
    "user_status" -> "Manage your presence and status message"
    else -> "Open native experience"
}

private fun nativeFamily(appId: String): String = when (appId.lowercase()) {
    "dashboard", "github" -> "dashboard and timeline"
    "activity" -> "activity timeline"
    "mail" -> "mailbox and composer"
    "contacts" -> "contact list"
    "calendar" -> "calendar and agenda"
    "cospend", "budget", "money" -> "collection, totals and form"
    "notes", "office", "richdocuments", "collectives" -> "document editor"
    "music", "audioplayer" -> "media library"
    "deck" -> "board and cards"
    "tasks", "chores" -> "task list"
    "tables" -> "typed data table"
    "cookbook" -> "recipe collection"
    else -> "adaptive collection"
}

private fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "File"
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
    else -> "${bytes / 1_073_741_824} GB"
}
