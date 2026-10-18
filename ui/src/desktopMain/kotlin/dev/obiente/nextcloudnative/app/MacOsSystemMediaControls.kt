package dev.obiente.nextcloudnative.app

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicLong

/**
 * macOS Now Playing (Control Center, the menu bar, media keys, and AirPods) for the playing video.
 *
 * MediaPlayer's `MPRemoteCommandCenter` and `MPNowPlayingInfoCenter` are called through the
 * Objective-C runtime with JNA. Commands reach a small runtime-registered target class through
 * `addTarget:action:`, so no Objective-C blocks are needed. Each batch of calls runs inside an
 * autorelease pool because the owning thread has none.
 */
internal class MacOsSystemMediaControls private constructor(
    private val targetGeneration: Long,
) : DesktopSystemMediaControls {
    override fun update(snapshot: SystemMediaSnapshot): Unit = ObjC.autoreleased {
        val center = ObjC.send(ObjC.cls("MPRemoteCommandCenter"), "sharedCommandCenter")
        ObjC.sendVoid(ObjC.send(center, "previousTrackCommand"), "setEnabled:", snapshot.canPrevious.abi())
        ObjC.sendVoid(ObjC.send(center, "nextTrackCommand"), "setEnabled:", snapshot.canNext.abi())
        val info = ObjC.send(ObjC.cls("NSMutableDictionary"), "dictionary")
        ObjC.sendVoid(info, "setObject:forKey:", ObjC.nsString(snapshot.title), MediaPlayerKeys.title)
        ObjC.sendVoid(
            info,
            "setObject:forKey:",
            ObjC.sendDouble(ObjC.cls("NSNumber"), "numberWithDouble:", if (snapshot.playing) 1.0 else 0.0),
            MediaPlayerKeys.playbackRate,
        )
        ObjC.sendVoid(info, "setObject:forKey:", ObjC.nsNumberInteger(MP_NOW_PLAYING_MEDIA_TYPE_VIDEO), MediaPlayerKeys.mediaType)
        val nowPlaying = ObjC.send(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter")
        ObjC.sendVoid(nowPlaying, "setNowPlayingInfo:", info)
        ObjC.sendLong(
            nowPlaying,
            "setPlaybackState:",
            if (snapshot.playing) MP_NOW_PLAYING_STATE_PLAYING else MP_NOW_PLAYING_STATE_PAUSED,
        )
        Unit
    }

    override fun close(): Unit = ObjC.autoreleased {
        MacOsRemoteCommandTarget.detach(targetGeneration)
        val nowPlaying = ObjC.send(ObjC.cls("MPNowPlayingInfoCenter"), "defaultCenter")
        ObjC.sendVoid(nowPlaying, "setNowPlayingInfo:", null)
        ObjC.sendLong(nowPlaying, "setPlaybackState:", MP_NOW_PLAYING_STATE_STOPPED)
        Unit
    }

    companion object {
        fun openOrNull(onCommand: (SystemMediaCommand) -> Unit): DesktopSystemMediaControls? {
            if (!MediaPlayerKeys.loaded) return null
            val generation = MacOsRemoteCommandTarget.attach(onCommand) ?: return null
            return MacOsSystemMediaControls(generation)
        }
    }
}

/**
 * The process-wide Objective-C target registered with each remote command. It is registered once
 * and never removed, because the command center retains it; sessions swap the receiver.
 */
internal object MacOsRemoteCommandTarget {
    private interface Action : Callback {
        fun invoke(self: Pointer?, selector: Pointer?, event: Pointer?): Long
    }

    private val generation = AtomicLong(0)

    @Volatile
    private var receiver: Pair<Long, (SystemMediaCommand) -> Unit>? = null

    private val actions: Map<String, Pair<SystemMediaCommand, Action>> = MAC_REMOTE_COMMANDS.associate { (selector, command) ->
        selector to (command to object : Action {
            override fun invoke(self: Pointer?, selector: Pointer?, event: Pointer?): Long {
                val current = receiver ?: return MP_REMOTE_COMMAND_NO_SUCH_CONTENT
                current.second(command)
                return MP_REMOTE_COMMAND_SUCCESS
            }
        })
    }

    private val target: Pointer? by lazy { registerTarget() }

    fun attach(onCommand: (SystemMediaCommand) -> Unit): Long? {
        target ?: return null
        val next = generation.incrementAndGet()
        receiver = next to onCommand
        return next
    }

    fun detach(expected: Long) {
        if (receiver?.first == expected) receiver = null
    }

    /** Sends the command a remote [selector] would, for tests of the native dispatch path. */
    internal fun perform(selector: String): Long = ObjC.autoreleased {
        ObjC.sendLong(requireNotNull(target), selector, 0L)
    }

    private fun registerTarget(): Pointer? = ObjC.autoreleased {
        val existing = ObjC.runtime.objc_getClass(TARGET_CLASS_NAME)
        val targetClass = existing ?: ObjC.runtime.objc_allocateClassPair(ObjC.cls("NSObject"), TARGET_CLASS_NAME, 0L)
            ?.also { created ->
                actions.forEach { (selector, action) ->
                    ObjC.runtime.class_addMethod(created, ObjC.selector(selector), action.second, "q@:@")
                }
                ObjC.runtime.objc_registerClassPair(created)
            }
            ?: return@autoreleased null
        val instance = ObjC.send(ObjC.send(targetClass, "alloc"), "init") ?: return@autoreleased null
        val center = ObjC.send(ObjC.cls("MPRemoteCommandCenter"), "sharedCommandCenter")
        MAC_REMOTE_COMMANDS.forEach { (selector, _) ->
            val command = ObjC.send(center, MAC_COMMAND_GETTERS.getValue(selector))
            ObjC.send(command, "addTarget:action:", instance, ObjC.selector(selector))
            ObjC.sendVoid(command, "setEnabled:", 1L)
        }
        instance
    }

    private const val TARGET_CLASS_NAME = "NCNativeRemoteCommandTarget"
}

private object MediaPlayerKeys {
    private val framework: NativeLibrary? = try {
        NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
    } catch (_: UnsatisfiedLinkError) {
        null
    }

    val loaded: Boolean get() = framework != null

    val title: Pointer? by lazy { constant("MPMediaItemPropertyTitle") }
    val playbackRate: Pointer? by lazy { constant("MPNowPlayingInfoPropertyPlaybackRate") }
    val mediaType: Pointer? by lazy { constant("MPNowPlayingInfoPropertyMediaType") }

    private fun constant(name: String): Pointer? = framework?.getGlobalVariableAddress(name)?.getPointer(0L)
}

private object ObjC {
    interface Runtime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer?, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, selector: Pointer, implementation: Callback, types: String): Boolean
        fun objc_autoreleasePoolPush(): Pointer?
        fun objc_autoreleasePoolPop(pool: Pointer?)
    }

    val runtime: Runtime by lazy { Native.load("objc", Runtime::class.java) }
    private val messageSend: Function by lazy { Function.getFunction("objc", "objc_msgSend") }

    fun cls(name: String): Pointer? = runtime.objc_getClass(name)

    fun selector(name: String): Pointer = runtime.sel_registerName(name)

    fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        receiver?.let { messageSend.invokePointer(arrayOf(it, selector(selector), *args)) }

    fun sendVoid(receiver: Pointer?, selector: String, vararg args: Any?) {
        receiver?.let { messageSend.invokeVoid(arrayOf(it, selector(selector), *args)) }
    }

    fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long =
        receiver?.let { messageSend.invokeLong(arrayOf(it, selector(selector), *args)) } ?: 0L

    fun sendDouble(receiver: Pointer?, selector: String, value: Double): Pointer? = send(receiver, selector, value)

    fun nsString(value: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", value)

    fun nsNumberInteger(value: Long): Pointer? = send(cls("NSNumber"), "numberWithInteger:", value)

    inline fun <T> autoreleased(block: () -> T): T {
        val pool = runtime.objc_autoreleasePoolPush()
        try {
            return block()
        } finally {
            runtime.objc_autoreleasePoolPop(pool)
        }
    }
}

private fun Boolean.abi(): Long = if (this) 1L else 0L

/** Remote command action selectors and the commands they send. */
private val MAC_REMOTE_COMMANDS = listOf(
    "ncPlay:" to SystemMediaCommand.Play,
    "ncPause:" to SystemMediaCommand.Pause,
    "ncTogglePlayPause:" to SystemMediaCommand.TogglePlayPause,
    "ncStop:" to SystemMediaCommand.Stop,
    "ncNextTrack:" to SystemMediaCommand.Next,
    "ncPreviousTrack:" to SystemMediaCommand.Previous,
)

private val MAC_COMMAND_GETTERS = mapOf(
    "ncPlay:" to "playCommand",
    "ncPause:" to "pauseCommand",
    "ncTogglePlayPause:" to "togglePlayPauseCommand",
    "ncStop:" to "stopCommand",
    "ncNextTrack:" to "nextTrackCommand",
    "ncPreviousTrack:" to "previousTrackCommand",
)

private const val MP_REMOTE_COMMAND_SUCCESS = 0L
private const val MP_REMOTE_COMMAND_NO_SUCH_CONTENT = 100L
private const val MP_NOW_PLAYING_STATE_PLAYING = 1L
private const val MP_NOW_PLAYING_STATE_PAUSED = 2L
private const val MP_NOW_PLAYING_STATE_STOPPED = 3L
private const val MP_NOW_PLAYING_MEDIA_TYPE_VIDEO = 2L
