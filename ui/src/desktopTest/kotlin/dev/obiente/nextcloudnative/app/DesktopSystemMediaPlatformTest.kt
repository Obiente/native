package dev.obiente.nextcloudnative.app

import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.util.Collections
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.junit.Assume.assumeTrue

/** Each platform integration against its real system service, where the host provides one. */
class DesktopSystemMediaPlatformTest {
    private val snapshot = SystemMediaSnapshot(title = "clip.mp4", playing = true, canPrevious = true, canNext = false)

    @Test
    fun mprisPublishesThePlayerAndRoutesCommandsOverDBus() {
        val directory = Files.createTempDirectory("nc-mpris")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = try {
            EmbeddedDBusDaemon("$address,listen=true").also { daemon ->
                // The transport is found through ServiceLoader; name the loader that has it.
                daemon.setServiceLoaderClassLoader(javaClass.classLoader)
                daemon.startInBackgroundAndWait(20_000L)
            }
        } catch (failure: Exception) {
            assumeTrue("No embedded D-Bus transport on this host: $failure", false)
            return
        }
        val commands = Collections.synchronizedList(mutableListOf<SystemMediaCommand>())
        try {
            val service = DBusConnectionBuilder.forAddress(address).withShared(false).build()
            val controls = LinuxMprisSystemMediaControls.open(
                connection = service,
                onCommand = { commands += it },
                closeConnection = true,
                busName = "$MPRIS_BUS_NAME_PREFIX.test",
            )
            controls.update(snapshot)
            DBusConnectionBuilder.forAddress(address).withShared(false).build().use { client ->
                val player = client.getRemoteObject("$MPRIS_BUS_NAME_PREFIX.test", MPRIS_OBJECT_PATH, MprisMediaPlayer2Player::class.java)
                val properties = client.getRemoteObject("$MPRIS_BUS_NAME_PREFIX.test", MPRIS_OBJECT_PATH, Properties::class.java)
                assertEquals("Playing", properties.Get<String>(MPRIS_PLAYER_INTERFACE, "PlaybackStatus"))
                assertEquals(true, properties.Get<Boolean>(MPRIS_PLAYER_INTERFACE, "CanGoPrevious"))
                assertEquals(false, properties.Get<Boolean>(MPRIS_PLAYER_INTERFACE, "CanGoNext"))
                assertEquals("nati.ve", properties.Get<String>(MPRIS_ROOT_INTERFACE, "Identity"))
                player.PlayPause()
                player.Previous()
                player.Next()
            }
            controls.close()
        } finally {
            daemon.close()
            directory.toFile().deleteRecursively()
        }
        assertEquals(
            listOf(SystemMediaCommand.TogglePlayPause, SystemMediaCommand.Previous),
            commands,
            "Next is ignored while the viewer has no next item.",
        )
    }

    @Test
    fun windowsMediaControlsRegisterForARealWindow() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        assumeTrue(!GraphicsEnvironment.isHeadless())
        var frame: JFrame? = null
        SwingUtilities.invokeAndWait {
            frame = JFrame("nati.ve media controls test").apply {
                setSize(200, 120)
                isVisible = true
            }
        }
        try {
            val controls = assertNotNull(
                WindowsSystemMediaControls.openOrNull(frame) {},
                "Windows system media transport controls are available for a shown window.",
            )
            controls.update(snapshot)
            controls.update(snapshot.copy(playing = false, title = "other.mp4"))
            controls.close()
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }

    @Test
    fun macOsNowPlayingRegistersAndDispatchesRemoteCommands() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
        val commands = Collections.synchronizedList(mutableListOf<SystemMediaCommand>())
        val controls = assertNotNull(MacOsSystemMediaControls.openOrNull { commands += it })
        controls.update(snapshot)
        assertEquals(0L, MacOsRemoteCommandTarget.perform("ncNextTrack:"))
        assertEquals(0L, MacOsRemoteCommandTarget.perform("ncTogglePlayPause:"))
        controls.update(snapshot.copy(playing = false))
        controls.close()
        assertEquals(100L, MacOsRemoteCommandTarget.perform("ncPlay:"), "A closed session reports no content.")
        assertTrue(commands == listOf(SystemMediaCommand.Next, SystemMediaCommand.TogglePlayPause), "$commands")
    }
}
