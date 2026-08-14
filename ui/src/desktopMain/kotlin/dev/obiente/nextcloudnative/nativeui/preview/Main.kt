package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.DesktopNextcloudServices
import dev.obiente.nextcloudnative.app.DesktopActivationKind
import dev.obiente.nextcloudnative.app.DesktopSingleInstance
import dev.obiente.nextcloudnative.app.DesktopSingleInstanceStart
import dev.obiente.nextcloudnative.app.DesktopTrayRegistration
import dev.obiente.nextcloudnative.app.DesktopTrayAction
import dev.obiente.nextcloudnative.app.DesktopTrayActionFeedback
import dev.obiente.nextcloudnative.app.DesktopFileSyncTrayPhase
import dev.obiente.nextcloudnative.app.DesktopFileSyncTrayPopup
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.NextcloudNativeApp
import dev.obiente.nextcloudnative.app.NextcloudNativeNavigationRequest
import dev.obiente.nextcloudnative.app.NextcloudNativeRoute
import dev.obiente.nextcloudnative.app.ThemePreference
import dev.obiente.nextcloudnative.app.applyDesktopNativeWindowFrame
import dev.obiente.nextcloudnative.app.createDesktopSupportDiagnostics
import dev.obiente.nextcloudnative.app.desktopSupportDiagnosticsDirectory
import dev.obiente.nextcloudnative.app.desktopUpdateHandoffActive
import dev.obiente.nextcloudnative.app.handoffLinuxAutostartToUserService
import dev.obiente.nextcloudnative.app.handoffLinuxForegroundLaunchToUserService
import dev.obiente.nextcloudnative.app.installDesktopBootstrapUncaughtDiagnosticHandler
import dev.obiente.nextcloudnative.app.installDesktopUncaughtDiagnosticHandler
import dev.obiente.nextcloudnative.app.registerDesktopTray
import dev.obiente.nextcloudnative.app.stopLinuxUserServiceForExplicitQuit
import dev.obiente.nextcloudnative.app.tooltip
import dev.obiente.nextcloudnative.app.unregisterWindowsCloudFilesRootForUninstall
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import java.awt.Frame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun main(arguments: Array<String>) {
    val supportDiagnosticsRoot = desktopSupportDiagnosticsDirectory()
    installDesktopBootstrapUncaughtDiagnosticHandler(supportDiagnosticsRoot)
    if (arguments.contentEquals(arrayOf("--unregister-windows-sync-root"))) {
        unregisterWindowsCloudFilesRootForUninstall()
        return
    }
    if (desktopUpdateHandoffActive()) {
        JOptionPane.showMessageDialog(
            null,
            "Nextcloud Native is updating and will reopen when installation finishes.",
            "Nextcloud Native update in progress",
            JOptionPane.INFORMATION_MESSAGE,
        )
        return
    }
    val autostartLaunch = arguments.contains("--autostart")
    val serviceLaunch = arguments.contains("--service")
    if (autostartLaunch && handoffLinuxAutostartToUserService()) return
    val backgroundLaunch = autostartLaunch || arguments.contains("--background")
    val updateHandoffFailed = arguments.contains("--update-handoff-failed")
    val activationKind = when {
        updateHandoffFailed -> DesktopActivationKind.UpdateHandoffFailed
        backgroundLaunch -> DesktopActivationKind.Background
        else -> DesktopActivationKind.ShowWindow
    }
    if (
        !backgroundLaunch &&
        handoffLinuxForegroundLaunchToUserService(
            activationForwarder = {
                DesktopSingleInstance.forwardToExisting(activationKind)
            },
        )
    ) return
    val singleInstance = when (val start = DesktopSingleInstance.acquire(activationKind = activationKind)) {
        is DesktopSingleInstanceStart.Primary -> start.instance
        DesktopSingleInstanceStart.Forwarded -> {
            if (!serviceLaunch) return
            DesktopSingleInstance.waitForPrimary()?.instance ?: return
        }
        DesktopSingleInstanceStart.Failed -> {
            JOptionPane.showMessageDialog(
                null,
                "Nextcloud Native could not activate its existing desktop process.",
                "Nextcloud Native",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }
    }
    val supportDiagnostics = createDesktopSupportDiagnostics(supportDiagnosticsRoot)
    installDesktopUncaughtDiagnosticHandler(supportDiagnostics)
    singleInstance.use {
    application {
    val themePreference = remember { mutableStateOf(ThemePreference.System) }
    val keepRunningInBackground = remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val updaterExitRequested = remember { mutableStateOf(false) }
    val services = remember {
        DesktopNextcloudServices(
            onThemePreferenceChanged = { preference -> themePreference.value = preference },
            onKeepRunningInBackgroundChanged = { enabled -> keepRunningInBackground.value = enabled },
            onDesktopUpdateInstallerOpened = { platform ->
                if (platform == "windows") {
                    scope.launch { updaterExitRequested.value = true }
                }
            },
            providedSupportDiagnostics = supportDiagnostics,
            supportIntakeRoot = supportDiagnosticsRoot.resolve("support-submissions"),
        ).also {
            themePreference.value = it.loadThemePreference()
            keepRunningInBackground.value = it.loadKeepRunningInBackgroundPreference()
        }
    }
    val darkTheme = when (themePreference.value) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val background = if (darkTheme) DarkWindowBackground else LightWindowBackground
    val traySnapshot = services.fileSyncTraySnapshot.collectAsState().value
    val trayAvailable = remember { mutableStateOf(false) }
    val trayRegistrationResolved = remember { mutableStateOf(false) }
    val windowVisible = remember { mutableStateOf(!backgroundLaunch) }
    val trayPopupVisible = remember { mutableStateOf(false) }
    val trayActionFeedback = remember { mutableStateOf<DesktopTrayActionFeedback?>(null) }
    val trayPopupWindow = remember { mutableStateOf<java.awt.Window?>(null) }
    val desktopTrayRegistration = remember { mutableStateOf<DesktopTrayRegistration?>(null) }
    val mainWindow = remember { mutableStateOf<java.awt.Window?>(null) }
    val mainWindowState = rememberWindowState(width = 1_280.dp, height = 820.dp)
    val focusRequestSequence = remember { mutableStateOf(0L) }
    val navigationSequence = remember { mutableStateOf(0L) }
    val navigationRequest = remember { mutableStateOf<NextcloudNativeNavigationRequest?>(null) }
    val updateFailureSequence = remember { mutableStateOf(if (updateHandoffFailed) 1L else 0L) }
    val shownUpdateFailureSequence = remember { mutableStateOf(0L) }
    val appIcon = painterResource("nextcloud-native.png")
    LaunchedEffect(services) {
        runCatching { services.refreshFileSyncTraySnapshot() }
        runCatching { services.restoreVirtualFileProviderIfEnabled() }
        services.startDesktopSyncLifecycle()
    }
    LaunchedEffect(backgroundLaunch, trayRegistrationResolved.value, trayAvailable.value) {
        if (backgroundLaunch && trayRegistrationResolved.value && !trayAvailable.value) {
            windowVisible.value = true
            mainWindowState.isMinimized = true
        }
    }
    LaunchedEffect(updaterExitRequested.value) {
        if (updaterExitRequested.value) exitApplication()
    }
    DisposableEffect(services) {
        onDispose(services::close)
    }

    fun toggleTrayPopup() {
        runOnAwtEventThread {
            val visible = trayPopupWindow.value?.isVisible != true
            trayPopupVisible.value = visible
            trayPopupWindow.value?.let { popup ->
                popup.isVisible = visible
                if (visible) {
                    popup.toFront()
                    popup.requestFocus()
                }
            }
        }
    }

    fun showMainWindow() {
        trayPopupVisible.value = false
        trayPopupWindow.value?.isVisible = false
        mainWindowState.isMinimized = false
        windowVisible.value = true
        focusRequestSequence.value = nextDesktopFocusRequestSequence(focusRequestSequence.value)
    }

    fun activateMainWindow(route: NextcloudNativeRoute) {
        navigationSequence.value += 1L
        navigationRequest.value = NextcloudNativeNavigationRequest(navigationSequence.value, route)
        showMainWindow()
    }

    fun quitDesktopApp() {
        stopLinuxUserServiceForExplicitQuit()
        exitApplication()
    }

    DisposableEffect(Unit) {
        val registration = registerDesktopTray(
            tooltip = traySnapshot.tooltip(),
            onAction = { action ->
                SwingUtilities.invokeLater {
                    when (action) {
                        DesktopTrayAction.ShowActivity -> toggleTrayPopup()
                        DesktopTrayAction.OpenApp -> showMainWindow()
                        DesktopTrayAction.Quit -> quitDesktopApp()
                    }
                }
            },
        )
        desktopTrayRegistration.value = registration
        trayAvailable.value = registration != null
        trayRegistrationResolved.value = true
        onDispose {
            trayAvailable.value = false
            desktopTrayRegistration.value = null
            registration?.close()
        }
    }
    SideEffect {
        desktopTrayRegistration.value?.updateTooltip(traySnapshot.tooltip())
    }

    LaunchedEffect(singleInstance) {
        singleInstance.activations.collect { externalActivation ->
            when (externalActivation.kind) {
                DesktopActivationKind.Background -> Unit
                DesktopActivationKind.ShowWindow -> showMainWindow()
                DesktopActivationKind.UpdateHandoffFailed -> {
                    showMainWindow()
                    updateFailureSequence.value += 1L
                }
            }
        }
    }

    LaunchedEffect(
        windowVisible.value,
        navigationRequest.value?.sequence,
        focusRequestSequence.value,
        mainWindow.value,
    ) {
        if (!windowVisible.value) return@LaunchedEffect
        mainWindow.value?.let { window ->
            if (window is Frame) window.extendedState = restoredDesktopFrameState(window.extendedState)
            window.toFront()
            window.requestFocus()
        }
    }
    LaunchedEffect(updateFailureSequence.value, mainWindow.value) {
        if (
            updateFailureSequence.value > shownUpdateFailureSequence.value &&
            mainWindow.value != null
        ) {
            shownUpdateFailureSequence.value = updateFailureSequence.value
            SwingUtilities.invokeLater {
                JOptionPane.showMessageDialog(
                    mainWindow.value,
                    "The Windows update did not complete. Nextcloud Native is still available.",
                    "Update did not complete",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }

    Window(
        visible = trayAvailable.value && trayPopupVisible.value,
        onCloseRequest = {
            trayPopupVisible.value = false
            trayPopupWindow.value?.isVisible = false
        },
        title = "Nextcloud Native sync activity",
        icon = appIcon,
        state = rememberWindowState(
            position = WindowPosition(Alignment.BottomEnd),
            width = 430.dp,
            height = 560.dp,
        ),
        undecorated = true,
        transparent = false,
        resizable = false,
        alwaysOnTop = true,
    ) {
        DisposableEffect(window) {
            trayPopupWindow.value = window
            window.requestFocus()
            onDispose {
                if (trayPopupWindow.value === window) trayPopupWindow.value = null
            }
        }
        NextcloudNativeTheme(darkTheme = darkTheme) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                DesktopFileSyncTrayPopup(
                    snapshot = traySnapshot,
                    onOpenApp = ::showMainWindow,
                    onOpenSettings = { activateMainWindow(NextcloudNativeRoute.Settings) },
                    onOpenSyncCenter = { activateMainWindow(NextcloudNativeRoute.SyncCenter) },
                    onSyncNow = {
                        scope.launch {
                            val result = services.syncAllFileSyncPairsFromTray()
                            trayActionFeedback.value = DesktopTrayActionFeedback(
                                message = result.trayMessage(),
                                error = result is FileSyncCenterActionResult.Rejected ||
                                    result is FileSyncCenterActionResult.Unsupported,
                            )
                            desktopTrayRegistration.value?.showMessage(
                                "Folder sync",
                                result.trayMessage(),
                                result is FileSyncCenterActionResult.Rejected,
                            )
                        }
                    },
                    onTogglePaused = {
                        services.setFileSyncPaused(
                            traySnapshot.phase != DesktopFileSyncTrayPhase.Paused,
                        )
                    },
                    onQuit = ::quitDesktopApp,
                    actionFeedback = trayActionFeedback.value,
                )
            }
        }
    }

    Window(
        onCloseRequest = {
            if (shouldKeepDesktopProcessRunningOnWindowClose(keepRunningInBackground.value)) {
                windowVisible.value = false
            } else {
                quitDesktopApp()
            }
        },
        visible = windowVisible.value,
        title = "Nextcloud Native",
        icon = appIcon,
        state = mainWindowState,
    ) {
        DisposableEffect(window) {
            mainWindow.value = window
            onDispose {
                if (mainWindow.value === window) mainWindow.value = null
            }
        }
        SideEffect {
            applyDesktopNativeWindowFrame(window, darkTheme)
            window.background = java.awt.Color(background.toArgb(), true)
            window.minimumSize = java.awt.Dimension(960, 640)
        }
        Box(Modifier.fillMaxSize().background(background)) {
            NextcloudNativeApp(
                services = services,
                presentation = NextcloudPresentation.Desktop,
                navigationRequest = navigationRequest.value,
                onNavigationRequestHandled = { sequence ->
                    if (shouldClearDesktopNavigationRequest(navigationRequest.value, sequence)) {
                        navigationRequest.value = null
                    }
                },
            )
        }
    }
    }
    }
}

internal fun restoredDesktopFrameState(currentState: Int): Int =
    currentState and Frame.ICONIFIED.inv()

internal fun shouldClearDesktopNavigationRequest(
    currentRequest: NextcloudNativeNavigationRequest?,
    handledSequence: Long,
): Boolean = currentRequest?.sequence == handledSequence

internal fun nextDesktopFocusRequestSequence(current: Long): Long =
    if (current == Long.MAX_VALUE) 0L else current + 1L

internal fun shouldKeepDesktopProcessRunningOnWindowClose(
    keepRunningInBackground: Boolean,
): Boolean = keepRunningInBackground

internal fun runOnAwtEventThread(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        action()
    } else {
        SwingUtilities.invokeLater(action)
    }
}

private fun FileSyncCenterActionResult.trayMessage(): String = when (this) {
    is FileSyncCenterActionResult.Completed -> message
    is FileSyncCenterActionResult.Rejected -> reason
    is FileSyncCenterActionResult.Unsupported -> reason
}

private val DarkWindowBackground = Color(0xFF0D0F13)
private val LightWindowBackground = Color(0xFFF7F6FA)
