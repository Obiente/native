package dev.obiente.nextcloudnative.app

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows system media transport controls (the media flyout, keyboard media keys, and Bluetooth
 * remotes) for the window that plays the video.
 *
 * WinRT is called through its ABI with JNA. Interface IDs, vtable slots, and enum values come from
 * the `windows` crate 0.62.2 bindings for `Windows.Media` and `Win32.System.WinRT`. All calls run
 * on the owning [DesktopSystemMediaSession] thread, which joins the multithreaded apartment.
 */
internal class WindowsSystemMediaControls private constructor(
    private val transportControls: Pointer,
    private val displayUpdater: Pointer,
    private val videoProperties: Pointer,
    private val registrationToken: Long,
    private val handlerTarget: Long,
) : DesktopSystemMediaControls {
    override fun update(snapshot: SystemMediaSnapshot) {
        transportControls.com(SMTC_SET_IS_PREVIOUS_ENABLED, snapshot.canPrevious.abi()).requireSuccess()
        transportControls.com(SMTC_SET_IS_NEXT_ENABLED, snapshot.canNext.abi()).requireSuccess()
        transportControls.com(
            SMTC_SET_PLAYBACK_STATUS,
            if (snapshot.playing) MEDIA_PLAYBACK_STATUS_PLAYING else MEDIA_PLAYBACK_STATUS_PAUSED,
        ).requireSuccess()
        withHString(snapshot.title) { title -> videoProperties.com(VIDEO_SET_TITLE, title).requireSuccess() }
        displayUpdater.com(DISPLAY_UPDATE).requireSuccess()
    }

    override fun close() {
        transportControls.com(SMTC_REMOVE_BUTTON_PRESSED, registrationToken)
        WindowsSmtcButtonHandler.clearTarget(handlerTarget)
        transportControls.com(SMTC_SET_PLAYBACK_STATUS, MEDIA_PLAYBACK_STATUS_CLOSED)
        displayUpdater.com(DISPLAY_CLEAR_ALL)
        displayUpdater.com(DISPLAY_UPDATE)
        transportControls.com(SMTC_SET_IS_ENABLED, false.abi())
        videoProperties.release()
        displayUpdater.release()
        transportControls.release()
    }

    companion object {
        /** Null when the window has no native handle yet or WinRT media controls are unavailable. */
        fun openOrNull(window: Window?, onCommand: (SystemMediaCommand) -> Unit): DesktopSystemMediaControls? {
            val hwnd = window?.takeIf(Window::isDisplayable)?.let(Native::getWindowPointer) ?: return null
            return openForWindow(hwnd, onCommand)
        }

        internal fun openForWindow(hwnd: Pointer, onCommand: (SystemMediaCommand) -> Unit): DesktopSystemMediaControls? {
            WinRt.joinMultithreadedApartment()
            val interop = withHString(SMTC_RUNTIME_CLASS) { className ->
                val factory = PointerByReference()
                if (className == null) return null
                if (WinRt.combase.RoGetActivationFactory(className, IID_SMTC_INTEROP, factory).failed()) return null
                factory.value
            } ?: return null
            val controls = PointerByReference()
            val obtained = interop.com(INTEROP_GET_FOR_WINDOW, hwnd, IID_SMTC, controls)
            interop.release()
            if (obtained.failed()) return null
            val smtc = controls.value ?: return null
            var updater: Pointer? = null
            var video: Pointer? = null
            var target: Long? = null
            var opened = false
            try {
                updater = smtc.outPointer(SMTC_DISPLAY_UPDATER) ?: return null
                updater.com(DISPLAY_SET_TYPE, MEDIA_PLAYBACK_TYPE_VIDEO).requireSuccess()
                video = updater.outPointer(DISPLAY_VIDEO_PROPERTIES) ?: return null
                smtc.com(SMTC_SET_IS_ENABLED, true.abi()).requireSuccess()
                smtc.com(SMTC_SET_IS_PLAY_ENABLED, true.abi()).requireSuccess()
                smtc.com(SMTC_SET_IS_PAUSE_ENABLED, true.abi()).requireSuccess()
                smtc.com(SMTC_SET_IS_STOP_ENABLED, false.abi()).requireSuccess()
                target = WindowsSmtcButtonHandler.setTarget(onCommand)
                val token = LongByReference()
                if (smtc.com(SMTC_ADD_BUTTON_PRESSED, WindowsSmtcButtonHandler.instance, token).failed()) return null
                opened = true
                return WindowsSystemMediaControls(smtc, updater, video, token.value, target)
            } finally {
                // Any failed step leaves nothing enabled, registered, or referenced.
                if (!opened) {
                    target?.let(WindowsSmtcButtonHandler::clearTarget)
                    smtc.com(SMTC_SET_IS_ENABLED, false.abi())
                    video?.release()
                    updater?.release()
                    smtc.release()
                }
            }
        }
    }
}

/** Maps a `SystemMediaTransportControlsButton` value to a command; record and channel buttons are ignored. */
internal fun windowsSmtcButtonCommand(button: Int): SystemMediaCommand? = when (button) {
    0 -> SystemMediaCommand.Play
    1 -> SystemMediaCommand.Pause
    2 -> SystemMediaCommand.Stop
    6 -> SystemMediaCommand.Next
    7 -> SystemMediaCommand.Previous
    else -> null
}

/**
 * The `TypedEventHandler<SystemMediaTransportControls, ButtonPressedEventArgs>` COM object.
 *
 * One process-wide instance is never freed: the system may keep and release references after the
 * handler is removed. The current session is swapped in as the target, fenced by a generation so a
 * closing session cannot clear a newer one.
 */
internal object WindowsSmtcButtonHandler {
    private interface QueryInterfaceCallback : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int
    }

    private interface RefCountCallback : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?): Int
    }

    private interface InvokeCallback : StdCallLibrary.StdCallCallback {
        fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int
    }

    private val references = AtomicInteger(1)
    private val generation = AtomicLong(0)

    @Volatile
    private var target: Pair<Long, (SystemMediaCommand) -> Unit>? = null

    private val queryInterface = object : QueryInterfaceCallback {
        override fun invoke(self: Pointer?, riid: Pointer?, out: Pointer?): Int {
            if (riid == null || out == null) return E_POINTER
            val requested = Guid.GUID(riid).toGuidString()
            return if (requested in ANSWERED_INTERFACES) {
                out.setPointer(0, instance)
                references.incrementAndGet()
                S_OK
            } else {
                out.setPointer(0, null)
                E_NOINTERFACE
            }
        }
    }
    private val addRef = object : RefCountCallback {
        override fun invoke(self: Pointer?): Int = references.incrementAndGet()
    }
    private val release = object : RefCountCallback {
        // The object is static, so reaching zero frees nothing.
        override fun invoke(self: Pointer?): Int = references.decrementAndGet().coerceAtLeast(1)
    }
    private val invoke = object : InvokeCallback {
        override fun invoke(self: Pointer?, sender: Pointer?, args: Pointer?): Int {
            val button = IntByReference()
            if (args == null || args.com(BUTTON_ARGS_BUTTON, button).failed()) return S_OK
            val command = windowsSmtcButtonCommand(button.value) ?: return S_OK
            target?.second?.invoke(command)
            return S_OK
        }
    }

    private val vtable = Memory(4L * Native.POINTER_SIZE).apply {
        setPointer(0L, com.sun.jna.CallbackReference.getFunctionPointer(queryInterface))
        setPointer(1L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(addRef))
        setPointer(2L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(release))
        setPointer(3L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(invoke))
    }

    /** The COM object: its first field points at the vtable. */
    val instance: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0L, vtable) }

    fun setTarget(onCommand: (SystemMediaCommand) -> Unit): Long {
        val next = generation.incrementAndGet()
        target = next to onCommand
        return next
    }

    fun clearTarget(expected: Long) {
        if (target?.first == expected) target = null
    }

    private val ANSWERED_INTERFACES = setOf(
        IID_SMTC_BUTTON_PRESSED_HANDLER.toGuidString(),
        "{00000000-0000-0000-C000-000000000046}", // IUnknown
        "{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}", // IAgileObject
    )
}

private object WinRt {
    interface Combase : Library {
        fun RoInitialize(initType: Int): Int
        fun RoGetActivationFactory(activatableClassId: Pointer, iid: Guid.GUID, factory: PointerByReference): Int
        fun WindowsCreateString(source: WString, length: Int, string: PointerByReference): Int
        fun WindowsDeleteString(string: Pointer?): Int
    }

    val combase: Combase by lazy { Native.load("combase", Combase::class.java) }

    /** S_OK, S_FALSE (already joined), and RPC_E_CHANGED_MODE all leave the thread usable. */
    fun joinMultithreadedApartment() {
        combase.RoInitialize(RO_INIT_MULTITHREADED)
    }
}

/** Runs [use] with a WinRT HSTRING for [value]; an empty string is the null HSTRING. */
private inline fun <T> withHString(value: String, use: (Pointer?) -> T): T? {
    val handle = PointerByReference()
    if (WinRt.combase.WindowsCreateString(WString(value), value.length, handle).failed()) return null
    return try {
        use(handle.value)
    } finally {
        WinRt.combase.WindowsDeleteString(handle.value)
    }
}

/** Calls vtable [slot] of a COM interface pointer; returns its HRESULT. */
internal fun Pointer.com(slot: Int, vararg args: Any?): Int {
    val function = Function.getFunction(getPointer(0L).getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
    return function.invokeInt(arrayOf(this, *args))
}

private fun Pointer.outPointer(slot: Int): Pointer? {
    val out = PointerByReference()
    return if (com(slot, out).failed()) null else out.value
}

private fun Pointer.release() {
    com(COM_RELEASE)
}

private fun Boolean.abi(): Int = if (this) 1 else 0

private fun Int.failed(): Boolean = this < 0

private fun Int.requireSuccess() = check(this >= 0) { "Windows media controls call failed (HRESULT 0x${toUInt().toString(16)})." }

private val IID_SMTC_INTEROP = Guid.GUID.fromString("{ddb0472d-c911-4a1f-86d9-dc3d71a95f5a}")
private val IID_SMTC = Guid.GUID.fromString("{99fa3ff4-1742-42a6-902e-087d41f965ec}")

// pinterface GUID of TypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>.
private val IID_SMTC_BUTTON_PRESSED_HANDLER = Guid.GUID.fromString("{0557e996-7b23-5bae-aa81-ea0d671143a4}")
private const val SMTC_RUNTIME_CLASS = "Windows.Media.SystemMediaTransportControls"

private const val COM_RELEASE = 2
private const val INTEROP_GET_FOR_WINDOW = 6
private const val SMTC_SET_PLAYBACK_STATUS = 7
private const val SMTC_DISPLAY_UPDATER = 8
private const val SMTC_SET_IS_ENABLED = 11
private const val SMTC_SET_IS_PLAY_ENABLED = 13
private const val SMTC_SET_IS_STOP_ENABLED = 15
private const val SMTC_SET_IS_PAUSE_ENABLED = 17
private const val SMTC_SET_IS_PREVIOUS_ENABLED = 25
private const val SMTC_SET_IS_NEXT_ENABLED = 27
private const val SMTC_ADD_BUTTON_PRESSED = 32
private const val SMTC_REMOVE_BUTTON_PRESSED = 33
private const val DISPLAY_SET_TYPE = 7
private const val DISPLAY_VIDEO_PROPERTIES = 13
private const val DISPLAY_CLEAR_ALL = 16
private const val DISPLAY_UPDATE = 17
private const val VIDEO_SET_TITLE = 7
internal const val BUTTON_ARGS_BUTTON = 6

private const val MEDIA_PLAYBACK_STATUS_CLOSED = 0
private const val MEDIA_PLAYBACK_STATUS_PLAYING = 3
private const val MEDIA_PLAYBACK_STATUS_PAUSED = 4
private const val MEDIA_PLAYBACK_TYPE_VIDEO = 2
private const val RO_INIT_MULTITHREADED = 1
private const val S_OK = 0
private const val E_NOINTERFACE = 0x80004002.toInt()
private const val E_POINTER = 0x80004003.toInt()
