package dev.obiente.nextcloudnative.app

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/**
 * MPRIS 2 on the session bus, which desktop shells, media keys, and Bluetooth remotes use to show
 * and control the playing video.
 *
 * Only what the viewer can honor is advertised: play, pause, and previous/next through the
 * viewer's sequence. Seeking, rate, and volume stay with the in-window controls.
 */
internal class LinuxMprisSystemMediaControls private constructor(
    private val connection: DBusConnection,
    private val busName: String,
    private val player: LinuxMprisPlayer,
    private val closeConnection: Boolean,
) : DesktopSystemMediaControls {
    override fun update(snapshot: SystemMediaSnapshot) {
        val changed = player.publish(snapshot)
        if (changed.isNotEmpty()) {
            connection.sendMessage(
                Properties.PropertiesChanged(MPRIS_OBJECT_PATH, MPRIS_PLAYER_INTERFACE, changed, emptyList()),
            )
        }
    }

    override fun close() {
        try {
            connection.unExportObject(MPRIS_OBJECT_PATH)
            connection.releaseBusName(busName)
        } catch (_: DBusException) {
            // The bus is already gone; nothing remains registered.
        } finally {
            if (closeConnection) connection.close()
        }
    }

    companion object {
        fun openOrNull(onCommand: (SystemMediaCommand) -> Unit): DesktopSystemMediaControls? = try {
            open(DBusConnectionBuilder.forSessionBus().withShared(false).build(), onCommand, closeConnection = true)
        } catch (_: DBusException) {
            null
        } catch (_: RuntimeException) {
            // No session bus, as on a server or in a sandbox without D-Bus access.
            null
        }

        internal fun open(
            connection: DBusConnection,
            onCommand: (SystemMediaCommand) -> Unit,
            closeConnection: Boolean,
            busName: String = "$MPRIS_BUS_NAME_PREFIX.instance${ProcessHandle.current().pid()}",
        ): DesktopSystemMediaControls {
            val player = LinuxMprisPlayer(onCommand)
            try {
                connection.requestBusName(busName)
                connection.exportObject(MPRIS_OBJECT_PATH, player)
            } catch (failure: DBusException) {
                if (closeConnection) connection.close()
                throw failure
            }
            return LinuxMprisSystemMediaControls(connection, busName, player, closeConnection)
        }
    }
}

@DBusInterfaceName(MPRIS_ROOT_INTERFACE)
internal interface MprisMediaPlayer2 : DBusInterface {
    @Suppress("FunctionName")
    fun Raise()

    @Suppress("FunctionName")
    fun Quit()
}

@DBusInterfaceName(MPRIS_PLAYER_INTERFACE)
internal interface MprisMediaPlayer2Player : DBusInterface {
    @Suppress("FunctionName")
    fun Next()

    @Suppress("FunctionName")
    fun Previous()

    @Suppress("FunctionName")
    fun Pause()

    @Suppress("FunctionName")
    fun PlayPause()

    @Suppress("FunctionName")
    fun Stop()

    @Suppress("FunctionName")
    fun Play()

    @Suppress("FunctionName")
    fun Seek(offset: Long)

    @Suppress("FunctionName")
    fun SetPosition(trackId: DBusPath, position: Long)

    @Suppress("FunctionName")
    fun OpenUri(uri: String)
}

internal class LinuxMprisPlayer(
    private val onCommand: (SystemMediaCommand) -> Unit,
) : MprisMediaPlayer2, MprisMediaPlayer2Player, Properties {
    @Volatile
    private var snapshot = SystemMediaSnapshot(title = "", playing = false, canPrevious = false, canNext = false)

    override fun getObjectPath(): String = MPRIS_OBJECT_PATH

    /** Stores [next] and returns the player properties whose values changed. */
    fun publish(next: SystemMediaSnapshot): Map<String, Variant<*>> {
        val before = playerProperties(snapshot)
        snapshot = next
        return playerProperties(next).filter { (name, value) -> before[name]?.value != value.value }
    }

    override fun Raise() = Unit

    override fun Quit() = Unit

    override fun Next() {
        if (snapshot.canNext) onCommand(SystemMediaCommand.Next)
    }

    override fun Previous() {
        if (snapshot.canPrevious) onCommand(SystemMediaCommand.Previous)
    }

    override fun Pause() = onCommand(SystemMediaCommand.Pause)

    override fun PlayPause() = onCommand(SystemMediaCommand.TogglePlayPause)

    override fun Stop() = onCommand(SystemMediaCommand.Stop)

    override fun Play() = onCommand(SystemMediaCommand.Play)

    override fun Seek(offset: Long) = Unit

    override fun SetPosition(trackId: DBusPath, position: Long) = Unit

    override fun OpenUri(uri: String) = Unit

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        requireNotNull(GetAll(interfaceName)[propertyName]) { "Unknown MPRIS property: $propertyName" }.value as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        MPRIS_ROOT_INTERFACE -> mapOf(
            "CanQuit" to Variant(false),
            "CanRaise" to Variant(false),
            "HasTrackList" to Variant(false),
            "Identity" to Variant("nati.ve"),
            "DesktopEntry" to Variant("nextcloudnative-NextcloudNative"),
            "SupportedUriSchemes" to Variant(emptyArray<String>(), "as"),
            "SupportedMimeTypes" to Variant(emptyArray<String>(), "as"),
        )
        MPRIS_PLAYER_INTERFACE -> playerProperties(snapshot)
        else -> emptyMap()
    }
}

private fun playerProperties(snapshot: SystemMediaSnapshot): Map<String, Variant<*>> = mapOf(
    "PlaybackStatus" to Variant(if (snapshot.playing) "Playing" else "Paused"),
    "Rate" to Variant(1.0),
    "MinimumRate" to Variant(1.0),
    "MaximumRate" to Variant(1.0),
    "Volume" to Variant(1.0),
    "Position" to Variant(0L),
    "Metadata" to Variant(
        mapOf<String, Variant<*>>(
            "mpris:trackid" to Variant(DBusPath(MPRIS_TRACK_PATH)),
            "xesam:title" to Variant(snapshot.title),
        ),
        "a{sv}",
    ),
    "CanGoNext" to Variant(snapshot.canNext),
    "CanGoPrevious" to Variant(snapshot.canPrevious),
    "CanPlay" to Variant(true),
    "CanPause" to Variant(true),
    "CanSeek" to Variant(false),
    "CanControl" to Variant(true),
)

internal const val MPRIS_BUS_NAME_PREFIX = "org.mpris.MediaPlayer2.nextcloudnative"
internal const val MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2"
internal const val MPRIS_ROOT_INTERFACE = "org.mpris.MediaPlayer2"
internal const val MPRIS_PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
private const val MPRIS_TRACK_PATH = "/dev/obiente/nextcloudnative/video/current"
