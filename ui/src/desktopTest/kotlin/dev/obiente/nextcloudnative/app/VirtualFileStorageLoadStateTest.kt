package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualFileStorageLoadStateTest {
    @Test
    fun `slow status remains live and publishes its eventual result`() = runBlocking {
        val state = VirtualFileStorageLoadState()
        val result = CompletableDeferred<VirtualFileStorageSnapshot>()
        val job = launch { state.refresh(slowAfterMillis = 0) { result.await() } }
        withTimeout(5_000) { while (state.phase != VirtualFileStorageLoadPhase.Slow) yield() }
        assertTrue(state.loading)
        assertTrue(requireNotNull(state.message).contains("other parts"))
        result.complete(defaultVirtualFileStorageSnapshot())
        job.join()
        assertEquals(VirtualFileStorageLoadPhase.Ready, state.phase)
        assertEquals(defaultVirtualFileStorageSnapshot(), state.snapshot)
        assertNull(state.message)
    }

    @Test
    fun `failure preserves previous status and retry clears the error`() = runBlocking {
        val state = VirtualFileStorageLoadState()
        state.refresh { defaultVirtualFileStorageSnapshot() }
        state.refresh { throw IllegalStateException("private backend detail") }
        assertEquals(VirtualFileStorageLoadPhase.Failed, state.phase)
        assertEquals(defaultVirtualFileStorageSnapshot(), state.snapshot)
        assertTrue(requireNotNull(state.message).contains("Try checking again"))
        state.refresh { defaultVirtualFileStorageSnapshot() }
        assertEquals(VirtualFileStorageLoadPhase.Ready, state.phase)
        assertNull(state.message)
    }

    @Test
    fun `cancelled screen rejects a late result even if the loader swallows cancellation`() = runBlocking {
        val state = VirtualFileStorageLoadState()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            state.refresh {
                started.complete(Unit)
                try { CompletableDeferred<Unit>().await() } catch (_: CancellationException) { }
                defaultVirtualFileStorageSnapshot()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertNull(state.snapshot)
        assertTrue(state.phase != VirtualFileStorageLoadPhase.Failed)
    }
}
