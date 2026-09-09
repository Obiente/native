package dev.obiente.nextcloudnative.app

import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.types.UInt32

internal enum class DesktopTrayAction {
    ShowActivity,
    OpenApp,
    Quit,
}

internal interface DesktopTrayRegistration : AutoCloseable {
    fun updateTooltip(tooltip: String)
    fun showMessage(title: String, message: String, error: Boolean)
}

internal fun registerDesktopTray(
    tooltip: String,
    onAction: (DesktopTrayAction) -> Unit,
    osName: String = System.getProperty("os.name").orEmpty(),
): DesktopTrayRegistration? {
    if (osName.contains("linux", ignoreCase = true)) {
        LinuxStatusNotifierTray.register(tooltip, onAction)?.let { return it }
    }
    return AwtDesktopTray.register(tooltip, onAction)
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
        fun register(
            tooltip: String,
            onAction: (DesktopTrayAction) -> Unit,
        ): DesktopTrayRegistration? {
            if (!SystemTray.isSupported()) return null
            return runCatching {
                val resource = requireNotNull(
                    Thread.currentThread().contextClassLoader.getResource("nextcloud-native.png"),
                )
                val popup = PopupMenu().apply {
                    MENU_ITEMS.forEach { (id, label) ->
                        add(MenuItem(label).apply {
                            addActionListener { menuAction(id)?.let(onAction) }
                        })
                    }
                }
                val icon = TrayIcon(ImageIO.read(resource), tooltip, popup).apply {
                    isImageAutoSize = true
                }
                val listener = object : MouseAdapter() {
                    override fun mouseReleased(event: MouseEvent) {
                        if (event.button == MouseEvent.BUTTON1 && !event.isPopupTrigger) {
                            onAction(DesktopTrayAction.ShowActivity)
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
@DBusProperty(name = "Menu", type = DBusPath::class)
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
    private val onAction: (DesktopTrayAction) -> Unit,
    private val onContextMenu: (Int, Int) -> Unit = { _, _ -> },
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
        onAction(DesktopTrayAction.ShowActivity)
    }

    override fun SecondaryActivate(x: Int, y: Int) = onAction(DesktopTrayAction.ShowActivity)

    override fun ContextMenu(x: Int, y: Int) = onContextMenu(x, y)

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
            "Menu" to Variant(DBusPath(STATUS_NOTIFIER_MENU_PATH)),
        )
    }
}

@DBusInterfaceName(DBUS_MENU_INTERFACE)
@DBusProperty(name = "Version", type = UInt32::class)
@DBusProperty(name = "TextDirection", type = String::class)
@DBusProperty(name = "Status", type = String::class)
@DBusProperty(name = "IconThemePath", type = Array<String>::class)
internal interface DBusMenu : DBusInterface {
    @Suppress("FunctionName")
    fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<String>,
    ): DBusMenuLayoutResult<UInt32, DBusMenuLayout>

    @Suppress("FunctionName")
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<DBusMenuItemProperties>

    @Suppress("FunctionName")
    fun GetProperty(id: Int, name: String): Variant<*>

    @Suppress("FunctionName")
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    @Suppress("FunctionName")
    fun EventGroup(events: List<DBusMenuEvent>): List<Int>

    @Suppress("FunctionName")
    fun AboutToShow(id: Int): Boolean

    @Suppress("FunctionName")
    fun AboutToShowGroup(ids: List<Int>): DBusMenuAboutToShowResult<List<Int>, List<Int>>
}

internal class DBusMenuLayout(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
    @field:Position(2) @JvmField val children: List<Variant<DBusMenuLayout>>,
) : Struct()

internal class DBusMenuLayoutResult<A, B>(
    @field:Position(0) @JvmField val revision: A,
    @field:Position(1) @JvmField val layout: B,
) : Tuple()

internal class DBusMenuItemProperties(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

internal class DBusMenuEvent(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()

internal class DBusMenuAboutToShowResult<A, B>(
    @field:Position(0) @JvmField val updatesNeeded: A,
    @field:Position(1) @JvmField val idErrors: B,
) : Tuple()

internal class LinuxDBusMenu(
    private val onAction: (DesktopTrayAction) -> Unit,
) : DBusMenu, Properties {
    override fun getObjectPath(): String = STATUS_NOTIFIER_MENU_PATH

    override fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<String>,
    ): DBusMenuLayoutResult<UInt32, DBusMenuLayout> = DBusMenuLayoutResult(
        UInt32(1),
        menuLayout(parentId, recursionDepth, propertyNames),
    )

    override fun GetGroupProperties(
        ids: List<Int>,
        propertyNames: List<String>,
    ): List<DBusMenuItemProperties> = (ids.ifEmpty { allMenuIds }).mapNotNull { id ->
        menuItemProperties(id, propertyNames)?.let { DBusMenuItemProperties(id, it) }
    }

    override fun GetProperty(id: Int, name: String): Variant<*> =
        requireNotNull(menuItemProperties(id, listOf(name))?.get(name)) {
            "Unknown D-Bus menu property: $id/$name"
        }

    override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
        if (eventId != "clicked") return
        menuAction(id)?.let(onAction)
    }

    override fun EventGroup(events: List<DBusMenuEvent>): List<Int> {
        events.forEach { event -> Event(event.id, event.eventId, event.data, event.timestamp) }
        return events.mapNotNull { event -> event.id.takeUnless(::isKnownMenuId) }
    }

    override fun AboutToShow(id: Int): Boolean = false

    override fun AboutToShowGroup(
        ids: List<Int>,
    ): DBusMenuAboutToShowResult<List<Int>, List<Int>> = DBusMenuAboutToShowResult(
        updatesNeeded = emptyList(),
        idErrors = ids.filterNot(::isKnownMenuId),
    )

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        requireNotNull(menuProperties(interfaceName)[propertyName]) {
            "Unknown D-Bus menu property: $propertyName"
        }.value as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        error("D-Bus menu properties are read-only.")
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = menuProperties(interfaceName)

    private fun menuProperties(interfaceName: String): Map<String, Variant<*>> {
        if (interfaceName != DBUS_MENU_INTERFACE) return emptyMap()
        return linkedMapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyArray<String>()),
        )
    }

    private fun menuLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<String>,
    ): DBusMenuLayout {
        if (parentId != MENU_ROOT_ID) {
            val properties = requireNotNull(menuItemProperties(parentId, propertyNames))
            return DBusMenuLayout(parentId, properties, emptyList())
        }
        val children = if (recursionDepth == 0) {
            emptyList()
        } else {
            MENU_ITEMS.map { (id, _) ->
                Variant(DBusMenuLayout(id, menuItemProperties(id, propertyNames).orEmpty(), emptyList()))
            }
        }
        return DBusMenuLayout(
            MENU_ROOT_ID,
            requireNotNull(menuItemProperties(MENU_ROOT_ID, propertyNames)),
            children,
        )
    }

    private fun menuItemProperties(id: Int, propertyNames: List<String>): Map<String, Variant<*>>? =
        when (id) {
            MENU_ROOT_ID -> filterMenuProperties(
                mapOf("children-display" to Variant("submenu")),
                propertyNames,
            )
            else -> MENU_ITEMS.firstOrNull { it.first == id }?.let { (_, label) ->
                filterMenuProperties(mapOf("label" to Variant(label)), propertyNames)
            }
        }

    private fun filterMenuProperties(
        properties: Map<String, Variant<*>>,
        requested: List<String>,
    ): Map<String, Variant<*>> = if (requested.isEmpty()) {
        properties
    } else {
        properties.filterKeys(requested::contains)
    }

    private fun isKnownMenuId(id: Int): Boolean = id == MENU_ROOT_ID || MENU_ITEMS.any {
        it.first == id
    }
}

private class LinuxStatusNotifierTray private constructor(
    private val connection: DBusConnection,
    private val serviceName: String,
    private val item: LinuxStatusNotifierItem,
    private val contextMenu: LinuxTrayContextMenu,
    private val watcherOwnerSubscription: AutoCloseable,
) : DesktopTrayRegistration {
    override fun updateTooltip(tooltip: String) {
        item.updateTooltip(tooltip)
    }

    override fun showMessage(title: String, message: String, error: Boolean) = Unit

    override fun close() {
        runCatching { watcherOwnerSubscription.close() }
        runCatching { contextMenu.close() }
        runCatching { connection.releaseBusName(serviceName) }
        runCatching { connection.close() }
    }

    companion object {
        fun register(
            tooltip: String,
            onAction: (DesktopTrayAction) -> Unit,
        ): DesktopTrayRegistration? =
            runCatching {
                val connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
                val serviceName = "org.freedesktop.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
                val contextMenu = LinuxTrayContextMenu(onAction)
                val item = LinuxStatusNotifierItem(
                    initialTooltip = tooltip,
                    onAction = onAction,
                    onContextMenu = contextMenu::show,
                    onTitleChanged = {
                        connection.sendMessage(StatusNotifierItem.NewTitle(STATUS_NOTIFIER_ITEM_PATH))
                    },
                )
                val menu = LinuxDBusMenu(onAction)
                try {
                    connection.requestBusName(serviceName)
                    connection.exportObject(item)
                    connection.exportObject(menu)
                    val registerWithWatcher = {
                        connection.getRemoteObject(
                            STATUS_NOTIFIER_WATCHER_SERVICE,
                            STATUS_NOTIFIER_WATCHER_PATH,
                            StatusNotifierWatcher::class.java,
                        ).RegisterStatusNotifierItem(serviceName)
                    }
                    val watcherRegistration = StatusNotifierWatcherRegistration(registerWithWatcher)
                    val watcherOwnerSubscription = connection.addSigHandler(
                        DBus.NameOwnerChanged::class.java,
                    ) { change ->
                        watcherRegistration.ownerChanged(change.name, change.newOwner)
                    }
                    watcherRegistration.registerNow()
                    LinuxStatusNotifierTray(
                        connection,
                        serviceName,
                        item,
                        contextMenu,
                        watcherOwnerSubscription,
                    )
                } catch (failure: Throwable) {
                    runCatching { contextMenu.close() }
                    runCatching { connection.close() }
                    throw failure
                }
            }.onFailure { failure ->
                System.err.println(
                    "nati.ve could not register its Linux tray: " +
                        "${failure::class.simpleName}: ${failure.message.orEmpty()}",
                )
            }.getOrNull()
    }
}

private class LinuxTrayContextMenu(
    private val onAction: (DesktopTrayAction) -> Unit,
) : AutoCloseable {
    private var owner: JWindow? = null

    fun show(x: Int, y: Int) {
        SwingUtilities.invokeLater {
            closeNow()
            val window = JWindow().apply {
                isAlwaysOnTop = true
                setLocation(x, y)
                setSize(1, 1)
                isVisible = true
            }
            owner = window
            val popup = JPopupMenu().apply {
                MENU_ITEMS.forEach { (id, label) ->
                    add(JMenuItem(label).apply {
                        addActionListener { menuAction(id)?.let(onAction) }
                    })
                }
                addPopupMenuListener(object : PopupMenuListener {
                    override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
                    override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = closeNow()
                    override fun popupMenuCanceled(event: PopupMenuEvent) = closeNow()
                })
            }
            popup.show(window.contentPane, 0, 0)
        }
    }

    override fun close() {
        SwingUtilities.invokeLater(::closeNow)
    }

    private fun closeNow() {
        val window = owner
        owner = null
        window?.dispose()
    }
}

internal class StatusNotifierWatcherRegistration(
    private val registerWithWatcher: () -> Unit,
) {
    fun registerNow(): Boolean = runCatching(registerWithWatcher).isSuccess

    fun ownerChanged(name: String, newOwner: String) {
        if (shouldReregisterStatusNotifier(name, newOwner)) registerNow()
    }
}

internal fun shouldReregisterStatusNotifier(name: String, newOwner: String): Boolean =
    name == STATUS_NOTIFIER_WATCHER_SERVICE && newOwner.isNotBlank()

private const val STATUS_NOTIFIER_WATCHER_SERVICE = "org.kde.StatusNotifierWatcher"
private const val STATUS_NOTIFIER_WATCHER_PATH = "/StatusNotifierWatcher"
private const val STATUS_NOTIFIER_WATCHER_INTERFACE = "org.kde.StatusNotifierWatcher"
private const val STATUS_NOTIFIER_ITEM_INTERFACE = "org.kde.StatusNotifierItem"
private const val STATUS_NOTIFIER_ITEM_PATH = "/StatusNotifierItem"
private const val STATUS_NOTIFIER_MENU_PATH = "/Menu"
private const val DBUS_MENU_INTERFACE = "com.canonical.dbusmenu"
private const val MENU_ROOT_ID = 0
private const val MENU_SHOW_ACTIVITY_ID = 1
private const val MENU_OPEN_APP_ID = 2
private const val MENU_QUIT_ID = 3
private val MENU_ITEMS = listOf(
    MENU_SHOW_ACTIVITY_ID to "Show sync activity",
    MENU_OPEN_APP_ID to "Open nati.ve",
    MENU_QUIT_ID to "Quit",
)
private val allMenuIds = listOf(MENU_ROOT_ID) + MENU_ITEMS.map(Pair<Int, String>::first)

private fun menuAction(id: Int): DesktopTrayAction? = when (id) {
    MENU_SHOW_ACTIVITY_ID -> DesktopTrayAction.ShowActivity
    MENU_OPEN_APP_ID -> DesktopTrayAction.OpenApp
    MENU_QUIT_ID -> DesktopTrayAction.Quit
    else -> null
}
