package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

internal enum class VirtualFileStorageLoadPhase { Loading, Slow, Ready, Failed }

internal class VirtualFileStorageLoadState {
    var snapshot by mutableStateOf<VirtualFileStorageSnapshot?>(null)
        private set
    var phase by mutableStateOf(VirtualFileStorageLoadPhase.Loading)
        private set
    val loading: Boolean get() = phase == VirtualFileStorageLoadPhase.Loading || phase == VirtualFileStorageLoadPhase.Slow
    val message: String? get() = when (phase) {
        VirtualFileStorageLoadPhase.Slow -> "Storage checks are taking longer than expected. You can use other parts of nati.ve while they finish."
        VirtualFileStorageLoadPhase.Failed -> "Could not load storage status. Try checking again."
        else -> null
    }

    suspend fun refresh(
        slowAfterMillis: Long = 10_000L,
        load: suspend () -> VirtualFileStorageSnapshot,
    ) = coroutineScope {
        phase = VirtualFileStorageLoadPhase.Loading
        val slowNotice = launch {
            delay(slowAfterMillis)
            phase = VirtualFileStorageLoadPhase.Slow
        }
        try {
            val loaded = load()
            currentCoroutineContext().ensureActive()
            snapshot = loaded
            phase = VirtualFileStorageLoadPhase.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            phase = VirtualFileStorageLoadPhase.Failed
        } finally {
            slowNotice.cancel()
        }
    }
}

@Composable
internal fun rememberVirtualFileStorageLoadState(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    refreshAttempt: Int,
): VirtualFileStorageLoadState {
    val state = remember(services, session, userId) { VirtualFileStorageLoadState() }
    LaunchedEffect(state, refreshAttempt) {
        if (userId.isBlank() || !services.supportsVirtualFileStorage) return@LaunchedEffect
        while (true) {
            state.refresh { services.loadVirtualFileStorage(session, userId) }
            if (state.phase != VirtualFileStorageLoadPhase.Ready) break
            val pollDelay = virtualStorageHydrationPollDelay(
                requireNotNull(state.snapshot).folderHydrationStatuses,
                nowEpochMillis = Clock.System.now().toEpochMilliseconds().coerceAtLeast(0L),
            ) ?: break
            delay(pollDelay)
        }
    }
    return state
}
