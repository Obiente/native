package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncRunFailureKind
import dev.obiente.nextcloudnative.app.encodeFileSyncCoordinatorSnapshot
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidFileSyncRunFailureTest {
    @Test fun failureSurvivesReloadWithoutPersistingExceptionText() = runBlocking<Unit> {
        withStore { store ->
            seed(store)
            val original = IllegalStateException("https://fixture.invalid/private?token=synthetic-secret")
            val thrown = assertFailsWith<IllegalStateException> {
                withRecordedAndroidFileSyncFailure(store, "account", "pair") { throw original }
            }
            assertSame(original, thrown)
            val restored = store.load().coordinator.pairs.single()
            assertEquals(FileSyncRunFailureKind.Unknown, restored.runFailure?.kind)
            assertTrue(requireNotNull(restored.runFailure).occurredAtEpochMillis > 0)
            assertNull(restored.lastScanEpochMillis)
            val encoded = encodeFileSyncCoordinatorSnapshot(store.load().coordinator).decodeToString()
            assertFalse(encoded.contains("synthetic-secret"))
            assertFalse(encoded.contains("fixture.invalid"))
        }
    }

    @Test fun folderAndTransportFailuresHaveTypedRecovery() {
        assertEquals(FileSyncRunFailureKind.FolderAccess, androidFileSyncRunFailure(AndroidFileSyncFolderAccessException(), 1).kind)
        assertEquals(FileSyncRunFailureKind.Network, androidFileSyncRunFailure(SocketTimeoutException(), 1).kind)
        for ((status, kind) in listOf(401 to FileSyncRunFailureKind.SignInRequired, 403 to FileSyncRunFailureKind.PermissionDenied,
            404 to FileSyncRunFailureKind.RemoteFolderMissing, 429 to FileSyncRunFailureKind.Throttled,
            503 to FileSyncRunFailureKind.ServerUnavailable)) {
            assertEquals(kind, androidFileSyncRunFailure(DocumentWebDavException(DocumentWebDavError.Server, status, "Synthetic failure"), 1).kind)
        }
    }

    @Test fun cancellationDoesNotBecomeAPersistedFailure() = runBlocking<Unit> {
        withStore { store ->
            seed(store)
            val before = store.load()
            val cancelled = CancellationException("Stopped")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                withRecordedAndroidFileSyncFailure(store, "account", "pair") { throw cancelled }
            })
            assertEquals(before, store.load())
        }
    }

    @Test fun staleAccountCannotRecordFailureOnAnotherAccountsPair() = runBlocking<Unit> {
        withStore { store ->
            seed(store)
            assertFailsWith<IllegalStateException> {
                withRecordedAndroidFileSyncFailure(store, "other-account", "pair") { error("Synthetic failure") }
            }
            assertNull(store.load().coordinator.pairs.single().runFailure)
        }
    }

    @Test fun failureToRecordPreservesOriginalFailureAndExistingSnapshot() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("sync-failure-bound-").toFile()
        try {
            val initial = state()
            val store = AndroidFileSyncStore(File(directory, "state.bin"), encodeFileSyncCoordinatorSnapshot(initial.coordinator).size)
            store.save(initial)
            val original = IllegalStateException("Original failure")
            assertSame(original, assertFailsWith<IllegalStateException> {
                withRecordedAndroidFileSyncFailure(store, "account", "pair") { throw original }
            })
            assertTrue(original.suppressed.isNotEmpty())
            assertEquals(initial, store.load())
        } finally { directory.deleteRecursively() }
    }

    private fun state() = AndroidFileSyncPersistedState(FileSyncCoordinatorState(listOf(FileSyncPair(
        "pair", "account", "synthetic-root", "Documents", FileSyncConfiguration(deviceLabel = "Test device"),
    ))), mapOf("pair" to "Documents"))
    private fun seed(store: AndroidFileSyncStore) = store.save(state())
    private inline fun withStore(block: (AndroidFileSyncStore) -> Unit) {
        val directory = Files.createTempDirectory("sync-run-failure-").toFile()
        try { block(AndroidFileSyncStore(File(directory, "state.bin"))) } finally { directory.deleteRecursively() }
    }
}
