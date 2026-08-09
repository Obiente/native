package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.obiente.nextcloudnative.app.installDesktopBootstrapUncaughtDiagnosticHandler
import dev.obiente.nextcloudnative.app.installDesktopUncaughtDiagnosticHandler
import dev.obiente.nextcloudnative.app.tooltip
import dev.obiente.nextcloudnative.app.unregisterWindowsCloudFilesRootForUninstall
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import java.awt.Frame
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO
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
    if (autostartLaunch && handoffLinuxAutostartToUserService()) return
    val backgroundLaunch = autostartLaunch || arguments.contains("--background")
    val updateHandoffFailed = arguments.contains("--update-handoff-failed")
    val activationKind = when {
        updateHandoffFailed -> DesktopActivationKind.UpdateHandoffFailed
        backgroundLaunch -> DesktopActivationKind.Background
        else -> DesktopActivationKind.ShowWindow
    }
    val singleInstance = when (val start = DesktopSingleInstance.acquire(activationKind = activationKind)) {
        is DesktopSingleInstanceStart.Primary -> start.instance
        DesktopSingleInstanceStart.Forwarded -> return
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
    val scope = rememberCoroutineScope()
    val updaterExitRequested = remember { mutableStateOf(false) }
    val services = remember {
        DesktopNextcloudServices(
            onThemePreferenceChanged = { preference -> themePreference.value = preference },
            onDesktopUpdateInstallerOpened = { platform ->
                if (platform == "windows") {
                    scope.launch { updaterExitRequested.value = true }
                }
            },
            providedSupportDiagnostics = supportDiagnostics,
        ).also {
            themePreference.value = it.loadThemePreference()
        }
    }
    val darkTheme = when (themePreference.value) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val background = if (darkTheme) DarkWindowBackground else LightWindowBackground
    val traySnapshot = services.fileSyncTraySnapshot.collectAsState().value
    val systemTraySupported = remember { SystemTray.isSupported() }
    val trayAvailable = remember { mutableStateOf(false) }
    val trayRegistrationResolved = remember { mutableStateOf(!systemTraySupported) }
    val windowVisible = remember { mutableStateOf(!backgroundLaunch || !systemTraySupported) }
    val trayPopupVisible = remember { mutableStateOf(false) }
    val mainWindow = remember { mutableStateOf<java.awt.Window?>(null) }
    val mainWindowState = rememberWindowState(width = 1_280.dp, height = 820.dp)
    val focusRequestSequence = remember { mutableStateOf(0L) }
    val navigationSequence = remember { mutableStateOf(0L) }
    val navigationRequest = remember { mutableStateOf<NextcloudNativeNavigationRequest?>(null) }
    val updateFailureSequence = remember { mutableStateOf(if (updateHandoffFailed) 1L else 0L) }
    val shownUpdateFailureSequence = remember { mutableStateOf(0L) }
    val appIcon = painterResource("nextcloud-native.png")
    val desktopTrayIcon = remember(systemTraySupported) {
        if (!systemTraySupported) {
            null
        } else {
            runCatching {
                val resource = requireNotNull(
                    Thread.currentThread().contextClassLoader.getResource("nextcloud-native.png"),
                )
                TrayIcon(ImageIO.read(resource), traySnapshot.tooltip()).apply {
                    isImageAutoSize = true
                }
            }.getOrNull()
        }
    }

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

    DisposableEffect(desktopTrayIcon) {
        if (desktopTrayIcon == null) {
            trayRegistrationResolved.value = true
            return@DisposableEffect onDispose {}
        }
        val clickListener = object : MouseAdapter() {
            override fun mouseReleased(event: MouseEvent) {
                if (
                    event.button == MouseEvent.BUTTON1 ||
                    event.button == MouseEvent.BUTTON3 ||
                    event.isPopupTrigger
                ) {
                    scope.launch { trayPopupVisible.value = !trayPopupVisible.value }
                }
            }
        }
        desktopTrayIcon.addMouseListener(clickListener)
        val installed = runCatching {
            SystemTray.getSystemTray().add(desktopTrayIcon)
            true
        }.getOrDefault(false)
        trayAvailable.value = installed
        trayRegistrationResolved.value = true
        onDispose {
            trayAvailable.value = false
            desktopTrayIcon.removeMouseListener(clickListener)
            if (installed) SystemTray.getSystemTray().remove(desktopTrayIcon)
        }
    }
    SideEffect {
        desktopTrayIcon?.toolTip = traySnapshot.tooltip()
    }

    fun showMainWindow() {
        trayPopupVisible.value = false
        mainWindowState.isMinimized = false
        windowVisible.value = true
        focusRequestSequence.value = nextDesktopFocusRequestSequence(focusRequestSequence.value)
    }

    fun activateMainWindow(route: NextcloudNativeRoute) {
        navigationSequence.value += 1L
        navigationRequest.value = NextcloudNativeNavigationRequest(navigationSequence.value, route)
        showMainWindow()
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

    if (trayAvailable.value && trayPopupVisible.value) {
        Window(
            onCloseRequest = { trayPopupVisible.value = false },
            title = "Nextcloud Native sync activity",
            icon = appIcon,
            state = rememberWindowState(
                position = WindowPosition(Alignment.BottomEnd),
                width = 430.dp,
                height = 560.dp,
            ),
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
        ) {
            DisposableEffect(window) {
                val focusListener = object : WindowAdapter() {
                    override fun windowLostFocus(event: WindowEvent?) {
                        trayPopupVisible.value = false
                    }
                }
                window.addWindowFocusListener(focusListener)
                window.requestFocus()
                onDispose { window.removeWindowFocusListener(focusListener) }
            }
            NextcloudNativeTheme(darkTheme = darkTheme) {
                DesktopFileSyncTrayPopup(
                    snapshot = traySnapshot,
                    onOpenApp = ::showMainWindow,
                    onOpenSettings = { activateMainWindow(NextcloudNativeRoute.Settings) },
                    onOpenSyncCenter = { activateMainWindow(NextcloudNativeRoute.SyncCenter) },
                    onSyncNow = {
                        scope.launch {
                            val result = services.syncAllFileSyncPairsFromTray()
                            desktopTrayIcon?.displayMessage(
                                "Folder sync",
                                result.trayMessage(),
                                if (result is FileSyncCenterActionResult.Rejected) {
                                    TrayIcon.MessageType.ERROR
                                } else {
                                    TrayIcon.MessageType.INFO
                                },
                            )
                        }
                    },
                    onTogglePaused = {
                        services.setFileSyncPaused(
                            traySnapshot.phase != DesktopFileSyncTrayPhase.Paused,
                        )
                    },
                    onQuit = ::exitApplication,
                )
            }
        }
    }

    Window(
        onCloseRequest = {
            if (trayAvailable.value) {
                windowVisible.value = false
            } else {
                exitApplication()
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

private fun FileSyncCenterActionResult.trayMessage(): String = when (this) {
    is FileSyncCenterActionResult.Completed -> message
    is FileSyncCenterActionResult.Rejected -> reason
    is FileSyncCenterActionResult.Unsupported -> reason
}

private val DarkWindowBackground = Color(0xFF0D0F13)
private val LightWindowBackground = Color(0xFFF7F6FA)
