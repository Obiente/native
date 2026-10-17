package dev.obiente.nextcloudnative.app

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid
import com.sun.jna.ptr.PointerByReference
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSystemMediaControlsTest {
    private val snapshot = SystemMediaSnapshot(title = "clip.mp4", playing = true, canPrevious = false, canNext = true)

    @Test
    fun theSessionOpensUpdatesAndClosesOffTheCallerThread() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val closed = CountDownLatch(1)
        val callerThread = Thread.currentThread()
        var onCommand: ((SystemMediaCommand) -> Unit)? = null
        val session = DesktopSystemMediaSession(window = null, factory = { _, command ->
            events += "open:${Thread.currentThread() !== callerThread}"
            onCommand = command
            object : DesktopSystemMediaControls {
                override fun update(snapshot: SystemMediaSnapshot) {
                    events += "update:${snapshot.title}:${snapshot.playing}"
                }

                override fun close() {
                    events += "close"
                    closed.countDown()
                }
            }
        }) { command -> events += "command:$command" }

        session.update(snapshot)
        session.update(snapshot)
        session.update(snapshot.copy(playing = false))
        waitFor { events.size >= 3 }
        onCommand?.invoke(SystemMediaCommand.Next)
        session.close()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        onCommand?.invoke(SystemMediaCommand.Play)

        assertEquals(
            listOf("open:true", "update:clip.mp4:true", "update:clip.mp4:false", "command:Next", "close"),
            events,
            "A repeated snapshot is not republished, and commands stop after close.",
        )
    }

    @Test
    fun aReplacedSessionClosesBeforeItsReplacementOpens() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        fun factory(name: String): DesktopSystemMediaControlsFactory = { _, _ ->
            events += "open:$name"
            object : DesktopSystemMediaControls {
                override fun update(snapshot: SystemMediaSnapshot) {
                    events += "update:$name"
                }

                override fun close() {
                    events += "close:$name"
                }
            }
        }
        val first = DesktopSystemMediaSession(null, factory("old")) {}
        first.update(snapshot)
        waitFor { events.size >= 2 }
        first.close()
        val second = DesktopSystemMediaSession(null, factory("new")) {}
        second.update(snapshot)
        waitFor { events.size >= 5 }
        second.close()
        waitFor { events.size >= 6 }

        assertEquals(
            listOf("open:old", "update:old", "close:old", "open:new", "update:new", "close:new"),
            events,
            "The old integration can never undo what the new video published.",
        )
    }

    @Test
    fun aSessionClosedBeforeItOpenedNeverOpens() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val session = DesktopSystemMediaSession(null, { _, _ ->
            events += "open"
            UnavailableDesktopSystemMediaControls
        }) {}
        session.close()
        Thread.sleep(200)
        assertEquals(emptyList(), events, "A video left before its controls opened publishes nothing.")
    }

    @Test
    fun aFailingIntegrationNeverBreaksTheSession() {
        val throwingOpen = DesktopSystemMediaSession(null, { _, _ -> error("synthetic unavailable bus") }) {}
        throwingOpen.update(snapshot)
        throwingOpen.close()

        var updates = 0
        var closes = 0
        val throwingUpdate = DesktopSystemMediaSession(null, { _, _ ->
            object : DesktopSystemMediaControls {
                override fun update(snapshot: SystemMediaSnapshot) {
                    updates += 1
                    error("synthetic native failure")
                }

                override fun close() {
                    closes += 1
                }
            }
        }) {}
        throwingUpdate.update(snapshot)
        throwingUpdate.update(snapshot.copy(title = "other.mp4"))
        Thread.sleep(300)
        throwingUpdate.close()
        Thread.sleep(200)
        assertEquals(1, updates, "After a failure the integration stops instead of retrying native calls.")
        assertEquals(1, closes, "The failed integration is closed once so nothing stays registered.")
    }

    @Test
    fun windowsButtonsMapToCommands() {
        assertEquals(SystemMediaCommand.Play, windowsSmtcButtonCommand(0))
        assertEquals(SystemMediaCommand.Pause, windowsSmtcButtonCommand(1))
        assertEquals(SystemMediaCommand.Stop, windowsSmtcButtonCommand(2))
        assertEquals(SystemMediaCommand.Next, windowsSmtcButtonCommand(6))
        assertEquals(SystemMediaCommand.Previous, windowsSmtcButtonCommand(7))
        for (ignored in listOf(3, 4, 5, 8, 9, 42)) assertNull(windowsSmtcButtonCommand(ignored))
    }

    /** Drives the native COM handler through its vtable with a synthetic event-args object. */
    @Test
    fun theWindowsButtonHandlerAnswersComAndDispatchesTheButton() {
        val handler = WindowsSmtcButtonHandler.instance
        val out = PointerByReference()
        assertEquals(0, handler.com(0, Guid.GUID.fromString("{0557e996-7b23-5bae-aa81-ea0d671143a4}"), out))
        assertEquals(handler.getPointer(0), out.value.getPointer(0), "QueryInterface returns the handler itself.")
        assertEquals(0, handler.com(0, Guid.GUID.fromString("{94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90}"), out))
        assertEquals(0x80004002.toInt(), handler.com(0, Guid.GUID.fromString("{00000003-0000-0000-c000-000000000046}"), out))
        assertNull(out.value, "An unsupported interface yields a null pointer.")

        val received = Collections.synchronizedList(mutableListOf<SystemMediaCommand>())
        val generation = WindowsSmtcButtonHandler.setTarget { received += it }
        val args = SyntheticButtonArgs(button = 6)
        assertEquals(0, handler.com(3, null, args.instance))
        WindowsSmtcButtonHandler.clearTarget(generation)
        assertEquals(0, handler.com(3, null, args.instance))

        assertEquals(listOf(SystemMediaCommand.Next), received, "A cleared target receives nothing.")
    }

    private class SyntheticButtonArgs(button: Int) {
        private interface GetButton : Callback {
            fun invoke(self: Pointer?, out: Pointer?): Int
        }

        private val getButton = object : GetButton {
            override fun invoke(self: Pointer?, out: Pointer?): Int {
                out?.setInt(0L, button)
                return 0
            }
        }
        private val vtable = Memory(7L * Native.POINTER_SIZE).apply {
            for (slot in 0 until 7) setPointer(slot.toLong() * Native.POINTER_SIZE, Pointer.NULL)
            setPointer(BUTTON_ARGS_BUTTON.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(getButton))
        }
        val instance: Pointer = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0L, vtable) }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    }
}
