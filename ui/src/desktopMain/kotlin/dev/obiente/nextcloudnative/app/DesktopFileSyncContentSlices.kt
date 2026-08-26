package dev.obiente.nextcloudnative.app

internal fun verifyDesktopFileSyncContentSlice(
    slice: FileSyncContentVerificationSlice,
    local: DesktopFileSyncLocalTree,
    remote: DesktopFileSyncRemoteTree,
    shouldContinue: () -> Boolean,
): JvmFileSyncContentSliceOutcome {
    val candidate = slice.candidate
    val expectedBytes = requireNotNull(candidate.expectedSizeBytes)
    val continueOrStop = {
        if (!shouldContinue()) throw DesktopFileSyncScanStoppedException()
        true
    }
    val localHash = local.contentRangeHash(
        candidate.relativePath,
        candidate.localRevision,
        expectedBytes,
        slice.offset,
        slice.length,
        continueOrStop,
    )
    val remoteHash = remote.contentRangeHash(
        candidate.relativePath,
        candidate.remoteEtag,
        expectedBytes,
        slice.offset,
        slice.length,
        continueOrStop,
    )
    return completeJvmFileSyncContentSlice(slice, localHash, remoteHash)
}
