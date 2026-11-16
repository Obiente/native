package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncRunFailure
import dev.obiente.nextcloudnative.app.FileSyncRunFailureKind
import java.net.SocketException
import java.net.UnknownHostException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException

internal val activeAndroidFileSyncPairs: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

internal class AndroidFileSyncFolderAccessException : IllegalStateException(
    "Access to the selected local folder has expired. Select it again.",
)

internal fun androidFileSyncRunFailure(failure: Throwable, now: Long): FileSyncRunFailure {
    rethrowAndroidFileSyncCancellation(failure)
    val kind = when (failure) {
        is AndroidFileSyncFolderAccessException, is SecurityException -> FileSyncRunFailureKind.FolderAccess
        is DocumentWebDavException -> when (failure.status) {
            401 -> FileSyncRunFailureKind.SignInRequired
            403 -> FileSyncRunFailureKind.PermissionDenied
            404 -> FileSyncRunFailureKind.RemoteFolderMissing
            408 -> FileSyncRunFailureKind.Network
            429 -> FileSyncRunFailureKind.Throttled
            in 500..599 -> FileSyncRunFailureKind.ServerUnavailable
            else -> FileSyncRunFailureKind.Unknown
        }
        is UnknownHostException, is SocketException, is InterruptedIOException, is SSLException ->
            FileSyncRunFailureKind.Network
        else -> FileSyncRunFailureKind.Unknown
    }
    return FileSyncRunFailure(kind, now)
}

/** Called under the engine lock. Recording must never replace the original failure. */
internal suspend inline fun withRecordedAndroidFileSyncFailure(
    store: AndroidFileSyncStore,
    accountId: String,
    pairId: String,
    action: () -> FileSyncCenterActionResult,
): FileSyncCenterActionResult = try {
    activeAndroidFileSyncPairs.add(pairId)
    action()
} catch (failure: Exception) {
    rethrowAndroidFileSyncCancellation(failure)
    try {
        val current = store.load()
        val pair = current.coordinator.pairs.singleOrNull { it.id == pairId && it.accountId == accountId }
        if (pair != null) {
            val updated = pair.copy(runFailure = androidFileSyncRunFailure(failure, System.currentTimeMillis()))
            store.save(current.copy(coordinator = current.coordinator.copy(
                pairs = current.coordinator.pairs.map { if (it.id == pairId) updated else it },
            )))
        }
    } catch (recordingFailure: Exception) {
        rethrowAndroidFileSyncCancellation(recordingFailure)
        if (recordingFailure !== failure) failure.addSuppressed(recordingFailure)
    }
    throw failure
} finally {
    activeAndroidFileSyncPairs.remove(pairId)
}
