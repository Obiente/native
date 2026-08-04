package dev.obiente.nextcloudnative.app

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopSwingTest {
    @Test
    fun `desktop chooser work is dispatched to the Swing event thread`() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val onEventThread = worker.submit<Boolean> {
                invokeOnSwingEventThread(SwingUtilities::isEventDispatchThread)
            }.get(2L, TimeUnit.SECONDS)

            assertTrue(onEventThread)
        } finally {
            worker.shutdownNow()
        }
    }
}
