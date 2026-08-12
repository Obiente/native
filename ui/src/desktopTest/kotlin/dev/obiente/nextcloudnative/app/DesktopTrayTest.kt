package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue

class DesktopTrayTest {
    @Test
    fun linuxStatusNotifierRegistersWithTheAvailableDesktopHost() {
        assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true))
        assumeTrue(System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank().not())
        assumeTrue(
            runCatching {
                ProcessBuilder(
                    "busctl",
                    "--user",
                    "status",
                    "org.kde.StatusNotifierWatcher",
                ).redirectErrorStream(true).start().also { process ->
                    process.inputStream.bufferedReader().use { it.readText() }
                }.waitFor() == 0
            }.getOrDefault(false),
        )
        val activated = CountDownLatch(1)
        val registration = registerDesktopTray(
            "Nextcloud Native - all files are synced",
            { action ->
                if (action == DesktopTrayAction.ShowActivity) activated.countDown()
            },
        )

        assertNotNull(registration)
        try {
            val serviceName = "org.freedesktop.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
            val menuPath = ProcessBuilder(
                "busctl",
                "--user",
                "get-property",
                serviceName,
                "/StatusNotifierItem",
                "org.kde.StatusNotifierItem",
                "Menu",
            ).redirectErrorStream(true).start().let { process ->
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), output)
                output
            }
            assertTrue(menuPath.contains("/Menu"), menuPath)

            val layout = ProcessBuilder(
                "busctl",
                "--user",
                "call",
                serviceName,
                "/Menu",
                "com.canonical.dbusmenu",
                "GetLayout",
                "iias",
                "0",
                "--",
                "-1",
                "0",
            ).redirectErrorStream(true).start().let { process ->
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), output)
                output
            }
            assertTrue(layout.contains("Show sync activity"), layout)
            assertTrue(layout.contains("Open Nextcloud Native"), layout)
            assertTrue(layout.contains("Quit"), layout)

            val activation = ProcessBuilder(
                "busctl", "--user", "call", serviceName, "/Menu",
                "com.canonical.dbusmenu", "Event", "isvu", "1", "clicked", "i", "0", "0",
            ).redirectErrorStream(true).start().also { process ->
                process.inputStream.bufferedReader().use { it.readText() }
            }
            assertEquals(0, activation.waitFor())
            assertTrue(activated.await(2L, TimeUnit.SECONDS))
        } finally {
            registration.close()
        }
    }

    @Test
    fun statusNotifierItemPublishesIdentityAndActivatesTheCustomPanel() {
        var activated = false
        var titleChanges = 0
        var contextMenuCoordinates: Pair<Int, Int>? = null
        val item = LinuxStatusNotifierItem(
            initialTooltip = "All files are synced",
            onAction = { action -> activated = action == DesktopTrayAction.ShowActivity },
            onContextMenu = { x, y -> contextMenuCoordinates = x to y },
            onTitleChanged = { titleChanges += 1 },
        )

        assertEquals(
            "dev.obiente.nextcloudnative",
            item.Get<String>("org.kde.StatusNotifierItem", "IconName"),
        )
        assertEquals(
            "All files are synced",
            item.Get<String>("org.kde.StatusNotifierItem", "Title"),
        )
        assertEquals(
            false,
            item.Get<Boolean>("org.kde.StatusNotifierItem", "ItemIsMenu"),
        )
        assertFalse(activated)

        item.updateTooltip("Sync needs attention")
        item.updateTooltip("Sync needs attention")

        item.Activate(0, 0)
        item.ContextMenu(24, 48)

        assertEquals(true, activated)
        assertEquals(24 to 48, contextMenuCoordinates)
        assertEquals(1, titleChanges)
        assertEquals(
            "Sync needs attention",
            item.Get<String>("org.kde.StatusNotifierItem", "Title"),
        )
    }

    @Test
    fun statusNotifierReregistersOnlyWhenTheWatcherGetsANewOwner() {
        assertTrue(shouldReregisterStatusNotifier("org.kde.StatusNotifierWatcher", ":1.42"))
        assertFalse(shouldReregisterStatusNotifier("org.kde.StatusNotifierWatcher", ""))
        assertFalse(shouldReregisterStatusNotifier("org.example.OtherService", ":1.42"))
    }

    @Test
    fun linuxTrayMenuPublishesActionsAndDispatchesClicks() {
        val actions = mutableListOf<DesktopTrayAction>()
        val menu = LinuxDBusMenu(actions::add)

        val layout = menu.GetLayout(0, -1, emptyList()).layout
        val iconThemeProperty = DBusMenu::class.java.getAnnotationsByType(DBusProperty::class.java)
            .single { property -> property.name == "IconThemePath" }

        assertEquals(Array<String>::class, iconThemeProperty.type)
        assertContentEquals(
            emptyArray(),
            menu.Get<Array<String>>("com.canonical.dbusmenu", "IconThemePath"),
        )
        assertEquals("submenu", layout.properties.getValue("children-display").value)
        assertEquals("submenu", menu.GetProperty(0, "children-display").value)
        val rootProperties = menu.GetGroupProperties(listOf(0), listOf("children-display")).single()
        assertEquals(0, rootProperties.id)
        assertEquals("submenu", rootProperties.properties.getValue("children-display").value)
        assertEquals(
            listOf(0, 1, 2, 3),
            menu.GetGroupProperties(emptyList(), emptyList()).map(DBusMenuItemProperties::id),
        )
        assertEquals(
            listOf("Show sync activity", "Open Nextcloud Native", "Quit"),
            layout.children.map { child ->
                child.value.properties.getValue("label").value
            },
        )

        menu.Event(1, "clicked", Variant(0), UInt32(0))
        menu.Event(2, "clicked", Variant(0), UInt32(0))
        menu.Event(3, "clicked", Variant(0), UInt32(0))

        assertEquals(
            listOf(
                DesktopTrayAction.ShowActivity,
                DesktopTrayAction.OpenApp,
                DesktopTrayAction.Quit,
            ),
            actions,
        )
    }

    @Test
    fun statusNotifierWaitsForAWatcherWhenInitialRegistrationFails() {
        var attempts = 0
        val registration = StatusNotifierWatcherRegistration {
            attempts += 1
            check(attempts > 1) { "The watcher is not available yet." }
        }

        assertFalse(registration.registerNow())
        registration.ownerChanged("org.example.OtherService", ":1.41")
        assertEquals(1, attempts)

        registration.ownerChanged("org.kde.StatusNotifierWatcher", ":1.42")

        assertEquals(2, attempts)
    }
}
