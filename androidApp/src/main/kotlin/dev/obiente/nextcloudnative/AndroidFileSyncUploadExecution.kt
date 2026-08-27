package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncUploadCheckpoint
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.checkpointFileSyncUpload
import dev.obiente.nextcloudnative.app.jvmResumableNextcloudUpload
import dev.obiente.nextcloudnative.app.releaseCancelledFileSyncOperation
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

internal fun rethrowAndroidFileSyncCancellation(failure: Throwable?) {
    if (failure is CancellationException) throw failure
}

internal fun persistAndRethrowAndroidFileSyncCancellation(
    persisted: AndroidFileSyncPersistedState,
    store: AndroidFileSyncStore,
    pairId: String,
    workId: Long,
    failure: Throwable?,
): AndroidFileSyncPersistedState {
    if (failure !is CancellationException) return persisted
    val released = persisted.copy(
        coordinator = releaseCancelledFileSyncOperation(persisted.coordinator, pairId, workId),
    )
    store.save(released)
    throw failure
}

internal class AndroidFileSyncRunCancellation(
    private val shouldContinue: () -> Boolean,
) : DocumentRequestCancellation {
    private val cancelled = AtomicBoolean(false)
    private val activeCallCancellation = AtomicReference<(() -> Unit)?>(null)

    override fun throwIfCancelled() {
        if (cancelled.get() || !shouldContinue()) {
            cancel()
            throw CancellationException("Sync transfer cancelled.")
        }
    }

    override fun setOnCancelAction(action: (() -> Unit)?) {
        activeCallCancellation.set(action)
        if (action != null && (cancelled.get() || !shouldContinue())) {
            cancel()
            throw CancellationException("Sync transfer cancelled.")
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeCallCancellation.get()?.invoke()
    }
}

internal suspend fun <T> withAndroidFileSyncRunCancellation(
    block: suspend (AndroidFileSyncRunCancellation) -> T,
): T = coroutineScope {
    val parentJob = currentCoroutineContext()[Job]
    val cancellation = AndroidFileSyncRunCancellation {
        parentJob?.isActive != false && !Thread.currentThread().isInterrupted
    }
    val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (parentJob?.isActive == false) cancellation.cancel()
        }
    }
    try {
        block(cancellation)
    } finally {
        monitor.cancelAndJoin()
    }
}

internal class AndroidFileSyncCheckpointPersistence(
    initialState: AndroidFileSyncPersistedState,
    private val store: AndroidFileSyncStore,
    private val pairId: String,
    private val workId: Long,
) {
    var state: AndroidFileSyncPersistedState = initialState
        private set

    fun persist(checkpoint: FileSyncUploadCheckpoint) {
        state = state.copy(
            coordinator = checkpointFileSyncUpload(state.coordinator, pairId, workId, checkpoint),
        )
        store.save(state)
    }
}

internal fun resumeAndroidFileSyncUpload(
    staged: File,
    relativePath: String,
    exactLocal: LocalSyncEntry,
    durableLocalRevision: String,
    expectedRemoteEtag: String?,
    checkpoint: FileSyncUploadCheckpoint?,
    persistCheckpoint: (FileSyncUploadCheckpoint) -> Unit,
    remote: AndroidFileSyncRemoteTree,
    replacingDirectoryEtag: String? = null,
): RemoteSyncEntry = jvmResumableNextcloudUpload(
    source = staged,
    relativePath = relativePath,
    localRevision = durableLocalRevision,
    expectedRemoteEtag = expectedRemoteEtag,
    checkpoint = checkpoint,
    newUploadId = { UUID.randomUUID().toString() },
    persistCheckpoint = persistCheckpoint,
    remote = remote.resumableUploadRemote(replacingDirectoryEtag),
    shouldContinue = remote::shouldContinueTransfer,
    contentRevision = exactLocal.revision,
    contentHash = exactLocal.contentHash,
)
