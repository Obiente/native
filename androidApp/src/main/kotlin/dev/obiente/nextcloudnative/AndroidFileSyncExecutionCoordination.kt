package dev.obiente.nextcloudnative

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val ANDROID_FILE_SYNC_MAX_WORK_ITEMS = 10_000
internal const val ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE = 1_000

internal fun supportsAndroidFileSyncDirection(
    localRootId: String,
    direction: FileSyncDirection,
): Boolean =
    !localRootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX) || direction == FileSyncDirection.UploadOnly

internal fun isAndroidFileSyncExecutionAllowed(
    localRootId: String,
    operation: FileSyncOperation,
): Boolean =
    !localRootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX) || operation is FileSyncOperation.Upload

internal suspend fun runWhenFileSyncIdle(
    lock: Mutex,
    action: suspend () -> Unit,
): Boolean {
    if (!lock.tryLock()) return false
    return try {
        action()
        true
    } finally {
        lock.unlock()
    }
}

internal fun deferFileSyncActionUntilIdle(
    lock: Mutex,
    scope: CoroutineScope,
    action: suspend () -> Unit,
): Job = scope.launch {
    lock.withLock {
        action()
    }
}

/**
 * Runs [action] only when both the engine and its WorkManager sources are idle.
 *
 * Source state is inspected while [lock] is held. A running worker is then awaited without the
 * engine lock so it can finish, after which current persisted sources are loaded and checked again.
 */
internal suspend fun runFileSyncActionWhenSourceWorkIdle(
    lock: Mutex,
    runningSourceIds: suspend () -> Set<String>,
    awaitSourcesNotRunning: suspend (Set<String>) -> Unit,
    action: suspend () -> Unit,
) {
    while (true) {
        var completed = false
        val running = lock.withLock {
            runningSourceIds().also { activeSourceIds ->
                if (activeSourceIds.isEmpty()) {
                    action()
                    completed = true
                }
            }
        }
        if (completed) return
        awaitSourcesNotRunning(running)
    }
}

internal fun <T> deferFileSyncSnapshotActionUntilIdle(
    lock: Mutex,
    scope: CoroutineScope,
    load: () -> T,
    onFinished: () -> Unit = {},
    action: (T) -> Unit,
): Job {
    val job = scope.launch {
        lock.withLock {
            action(load())
        }
    }
    job.invokeOnCompletion { onFinished() }
    return job
}

/**
 * Reads a complete atomic snapshot without waiting for active execution.
 *
 * Scheduling is allowed only from a snapshot loaded after acquiring [lock], so a concurrent pair
 * removal cannot be followed by stale work being re-enqueued. A busy read requests a deferred
 * post-idle reload rather than scheduling from the displayed, potentially stale snapshot.
 */
internal fun <T> loadFileSyncPresentationSnapshot(
    lock: Mutex,
    load: () -> T,
    scheduleWhenIdle: (T) -> Unit,
    scheduleAfterIdle: () -> Unit = {},
): T {
    if (!lock.tryLock()) {
        return load().also { scheduleAfterIdle() }
    }
    return try {
        load().also(scheduleWhenIdle)
    } finally {
        lock.unlock()
    }
}

internal suspend fun removeConfiguredFileSyncPair(
    reconcileLocalDownloads: suspend () -> Boolean,
    cleanLedger: suspend () -> Unit,
    persistRemoval: suspend () -> Unit,
    cancelSchedule: suspend () -> Unit,
    releaseLocalGrant: suspend () -> Unit,
): Boolean {
    if (!reconcileLocalDownloads()) return false
    cleanLedger()
    persistRemoval()
    cancelSchedule()
    releaseLocalGrant()
    return true
}

internal fun reconcileSafDownloadsBeforeGrantRelease(
    context: Context,
    localRootId: String,
    releasesLocalGrant: Boolean,
): Boolean {
    if (!releasesLocalGrant) return true
    return try {
        createAndroidFileSyncLocalTree(context, localRootId).reconcileOwnedDownloads()
        true
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }
}

internal fun releaseSafGrantAfterPairRemoval(
    context: Context,
    localRootId: String,
    releasesLocalGrant: Boolean,
) {
    if (!releasesLocalGrant) return
    try {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(localRootId),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        // The pair is gone, so a later picker can release or replace this stale grant.
    }
}
