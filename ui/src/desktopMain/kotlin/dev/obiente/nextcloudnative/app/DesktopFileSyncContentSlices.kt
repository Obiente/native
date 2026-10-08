package dev.obiente.nextcloudnative.app

import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Verifies one bounded slice, or returns null when the scanned local generation can no longer be
 * read. The candidate then stays unverified, so planning skips it without writing either side.
 */
internal fun verifyDesktopFileSyncContentSlice(
    slice: FileSyncContentVerificationSlice,
    local: DesktopFileSyncLocalTree,
    remote: DesktopFileSyncRemoteTree,
    localContentHash: String,
    shouldContinue: () -> Boolean,
): JvmFileSyncContentSliceOutcome? {
    val candidate = slice.candidate
    val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
    val continueOrStop = {
        if (!shouldContinue()) throw DesktopFileSyncScanStoppedException()
        true
    }
    val localHash = try {
        local.contentRangeHash(
            candidate.relativePath,
            candidate.localRevision,
            expectedBytes,
            slice.offset,
            slice.length,
            continueOrStop,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IOException) {
        return null // Vanished or locked after the scan, for example a rewritten Git index.
    } catch (_: DesktopFileSyncLocalRevisionChangedException) {
        return null // The local revision changed after the scan; unsafe parents still propagate.
    } catch (_: IllegalStateException) {
        return null // The file was truncated while read, or is no longer a regular file or folder.
    }
    val remoteHash = remote.contentRangeHash(
        candidate.relativePath,
        candidate.remoteEtag,
        expectedBytes,
        slice.offset,
        slice.length,
        continueOrStop,
    )
    return completeJvmFileSyncContentSlice(slice, localHash, remoteHash, localContentHash)
}
