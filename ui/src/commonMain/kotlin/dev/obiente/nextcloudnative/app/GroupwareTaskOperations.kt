package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes durable task storage, server requests, and recovery reads for one Tasks screen. */
internal class GroupwareTaskOperations {
    private val mutex = Mutex()
    var busy by mutableStateOf(false)
        private set
    var mutationRunning by mutableStateOf(false)
        private set

    // Duplicate/stale UI events must not queue another write behind an active operation.
    suspend fun mutate(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        busy = true
        mutationRunning = true
        try {
            block()
        } finally {
            mutationRunning = false
            busy = false
            mutex.unlock()
        }
    }

    suspend fun recover(block: suspend () -> Unit) = mutex.withLock {
        busy = true
        try {
            block()
        } finally {
            busy = false
        }
    }
}
