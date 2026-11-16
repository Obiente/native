package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncSettingsControllerTest {
    @Test fun duplicateSavesAreBlockedAndARejectedSaveKeepsTheEditorOpen() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            var applied = false
            val controller = FileSyncSettingsController(scope) { _, _ ->
                calls++
                release.await()
                FileSyncCenterActionResult.Rejected("Review the saved settings.", FileSyncRejectionScope.Preflight)
            }
            controller.apply(configuration, changed) { applied = true }
            controller.apply(configuration, changed) { applied = true }
            yield()
            assertEquals(1, calls)
            assertTrue(controller.busy)
            release.complete(Unit)
            yield()
            assertFalse(controller.busy)
            assertFalse(applied)
            assertEquals("Review the saved settings.", controller.error)
        } finally { scope.cancel() }
    }

    @Test fun disposedEditorDoesNotPublishCompletionAfterNonCancellableCommit() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val release = CompletableDeferred<Unit>()
            var committed = false
            var applied = false
            val controller = FileSyncSettingsController(scope) { _, _ ->
                withContext(NonCancellable) { release.await(); committed = true }
                FileSyncCenterActionResult.Completed("Saved")
            }
            controller.apply(configuration, changed) { applied = true }
            yield()
            controller.close()
            release.complete(Unit)
            repeat(3) { yield() }
            assertTrue(committed)
            assertFalse(applied)
            assertNull(controller.error)
            assertFalse(controller.busy)
        } finally { scope.cancel() }
    }

    @Test fun exceptionsNeverExposePrivateTextToTheEditor() = runBlocking<Unit> {
        val controller = FileSyncSettingsController(this) { _, _ -> error("synthetic-private-token") }
        controller.apply(configuration, changed) { error("Unexpected success") }
        yield()
        assertFalse(controller.busy)
        assertTrue(controller.error.orEmpty().contains("check which settings were saved"))
        assertFalse(controller.error.orEmpty().contains("synthetic-private-token"))
    }

    private val configuration = FileSyncConfiguration(deviceLabel = "Device")
    private val changed = configuration.copy(deviceLabel = "Travel device")
}
