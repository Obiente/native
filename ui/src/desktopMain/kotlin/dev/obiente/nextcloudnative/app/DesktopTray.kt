package dev.obiente.nextcloudnative.app

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

internal interface DesktopTrayRegistration : AutoCloseable {
    fun updateTooltip(tooltip: String)
    fun showMessage(title: String, message: String, error: Boolean)
}

internal fun registerDesktopTray(
    tooltip: String,
    onActivated: () -> Unit,
    osName: String = System.getProperty("os.name").orEmpty(),
): DesktopTrayRegistration? {
    if (osName.contains("linux", ignoreCase = true)) {
        LinuxStatusNotifierTray.register(tooltip, onActivated)?.let { return it }
    }
    return AwtDesktopTray.register(tooltip, onActivated)
}

private class AwtDesktopTray private constructor(
    private val trayIcon: TrayIcon,
    private val clickListener: MouseAdapter,
) : DesktopTrayRegistration {
    override fun updateTooltip(tooltip: String) {
        trayIcon.toolTip = tooltip
    }

    override fun showMessage(title: String, message: String, error: Boolean) {
        trayIcon.displayMessage(
            title,
            message,
            if (error) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO,
        )
    }

    override fun close() {
        trayIcon.removeMouseListener(clickListener)
        SystemTray.getSystemTray().remove(trayIcon)
    }

    companion object {
        fun register(tooltip: String, onActivated: () -> Unit): DesktopTrayRegistration? {
            if (!SystemTray.isSupported()) return null
            return runCatching {
                val resource = requireNotNull(
                    Thread.currentThread().contextClassLoader.getResource("nextcloud-native.png"),
                )
                val icon = TrayIcon(ImageIO.read(resource), tooltip).apply { isImageAutoSize = true }
                val listener = object : MouseAdapter() {
                    override fun mouseReleased(event: MouseEvent) {
                        if (
                            event.button == MouseEvent.BUTTON1 ||
                            event.button == MouseEvent.BUTTON3 ||
                            event.isPopupTrigger
                        ) {
                            onActivated()
                        }
                    }
                }
                icon.addMouseListener(listener)
                SystemTray.getSystemTray().add(icon)
                AwtDesktopTray(icon, listener)
            }.getOrNull()
        }
    }
}

@DBusInterfaceName(STATUS_NOTIFIER_WATCHER_INTERFACE)
internal interface StatusNotifierWatcher : DBusInterface {
    @Suppress("FunctionName")
    fun RegisterStatusNotifierItem(service: String)
}

@DBusInterfaceName(STATUS_NOTIFIER_ITEM_INTERFACE)
@DBusProperty(name = "Category", type = String::class)
@DBusProperty(name = "Id", type = String::class)
@DBusProperty(name = "Title", type = String::class)
@DBusProperty(name = "Status", type = String::class)
@DBusProperty(name = "IconName", type = String::class)
@DBusProperty(name = "ItemIsMenu", type = Boolean::class)
internal interface StatusNotifierItem : DBusInterface {
    class NewTitle(path: String) : DBusSignal(path)

    @Suppress("FunctionName")
    fun Activate(x: Int, y: Int)

    @Suppress("FunctionName")
    fun SecondaryActivate(x: Int, y: Int)

    @Suppress("FunctionName")
    fun ContextMenu(x: Int, y: Int)

    @Suppress("FunctionName")
    fun Scroll(delta: Int, orientation: String)
}

internal class LinuxStatusNotifierItem(
    initialTooltip: String,
    private val onActivated: () -> Unit,
    private val onTitleChanged: () -> Unit = {},
) : StatusNotifierItem, Properties {
    @Volatile
    private var tooltip: String = initialTooltip

    fun updateTooltip(updatedTooltip: String) {
        if (updatedTooltip == tooltip) return
        tooltip = updatedTooltip
        onTitleChanged()
    }

    override fun getObjectPath(): String = STATUS_NOTIFIER_ITEM_PATH

    override fun Activate(x: Int, y: Int) {
        onActivated()
    }

    override fun SecondaryActivate(x: Int, y: Int) = onActivated()

    override fun ContextMenu(x: Int, y: Int) = onActivated()

    override fun Scroll(delta: Int, orientation: String) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        requireNotNull(properties(interfaceName)[propertyName]) {
            "Unknown StatusNotifierItem property: $propertyName"
        }.value as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        error("StatusNotifierItem properties are read-only.")
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties(interfaceName)

    private fun properties(interfaceName: String): Map<String, Variant<*>> {
        if (interfaceName != STATUS_NOTIFIER_ITEM_INTERFACE) return emptyMap()
        return linkedMapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("nextcloud-native"),
            "Title" to Variant(tooltip),
            "Status" to Variant("Active"),
            "IconName" to Variant("dev.obiente.nextcloudnative"),
            "ItemIsMenu" to Variant(false),
        )
    }
}

private class LinuxStatusNotifierTray private constructor(
    private val connection: DBusConnection,
    private val serviceName: String,
    private val item: LinuxStatusNotifierItem,
    private val watcherOwnerSubscription: AutoCloseable,
) : DesktopTrayRegistration {
    override fun updateTooltip(tooltip: String) {
        item.updateTooltip(tooltip)
    }

    override fun showMessage(title: String, message: String, error: Boolean) = Unit

    override fun close() {
        runCatching { watcherOwnerSubscription.close() }
        runCatching { connection.releaseBusName(serviceName) }
        runCatching { connection.close() }
    }

    companion object {
        fun register(tooltip: String, onActivated: () -> Unit): DesktopTrayRegistration? =
            runCatching {
                val connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                val serviceName = "org.freedesktop.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
                val item = LinuxStatusNotifierItem(
                    initialTooltip = tooltip,
                    onActivated = onActivated,
                    onTitleChanged = {
                        connection.sendMessage(StatusNotifierItem.NewTitle(STATUS_NOTIFIER_ITEM_PATH))
                    },
                )
                try {
                    connection.requestBusName(serviceName)
                    connection.exportObject(item)
                    val registerWithWatcher = {
                        connection.getRemoteObject(
                            STATUS_NOTIFIER_WATCHER_SERVICE,
                            STATUS_NOTIFIER_WATCHER_PATH,
                            StatusNotifierWatcher::class.java,
                        ).RegisterStatusNotifierItem(serviceName)
                    }
                    val watcherOwnerSubscription = connection.addSigHandler(
                        DBus.NameOwnerChanged::class.java,
                    ) { change ->
                        if (shouldReregisterStatusNotifier(change.name, change.newOwner)) {
                            runCatching(registerWithWatcher)
                        }
                    }
                    try {
                        registerWithWatcher()
                        LinuxStatusNotifierTray(
                            connection,
                            serviceName,
                            item,
                            watcherOwnerSubscription,
                        )
                    } catch (failure: Throwable) {
                        runCatching { watcherOwnerSubscription.close() }
                        throw failure
                    }
                } catch (failure: Throwable) {
                    runCatching { connection.close() }
                    throw failure
                }
            }.onFailure { failure ->
                System.err.println(
                    "Nextcloud Native could not register its Linux tray: " +
                        "${failure::class.simpleName}: ${failure.message.orEmpty()}",
                )
            }.getOrNull()
    }
}

internal fun shouldReregisterStatusNotifier(name: String, newOwner: String): Boolean =
    name == STATUS_NOTIFIER_WATCHER_SERVICE && newOwner.isNotBlank()

private const val STATUS_NOTIFIER_WATCHER_SERVICE = "org.kde.StatusNotifierWatcher"
private const val STATUS_NOTIFIER_WATCHER_PATH = "/StatusNotifierWatcher"
private const val STATUS_NOTIFIER_WATCHER_INTERFACE = "org.kde.StatusNotifierWatcher"
private const val STATUS_NOTIFIER_ITEM_INTERFACE = "org.kde.StatusNotifierItem"
private const val STATUS_NOTIFIER_ITEM_PATH = "/StatusNotifierItem"
