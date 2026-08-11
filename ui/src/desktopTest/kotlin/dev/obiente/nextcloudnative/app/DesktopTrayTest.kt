package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
            activated::countDown,
        )

        assertNotNull(registration)
        try {
            val serviceName = "org.freedesktop.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
            val activation = ProcessBuilder(
                "busctl",
                "--user",
                "call",
                serviceName,
                "/StatusNotifierItem",
                "org.kde.StatusNotifierItem",
                "Activate",
                "ii",
                "0",
                "0",
            ).redirectErrorStream(true).start()
            activation.inputStream.bufferedReader().use { it.readText() }
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
        val item = LinuxStatusNotifierItem(
            initialTooltip = "All files are synced",
            onActivated = { activated = true },
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

        assertEquals(true, activated)
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
