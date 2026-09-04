package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncWorkItem
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

internal fun executeAndroidFileSyncKeepBoth(
    operation: FileSyncOperation.KeepBoth,
    work: FileSyncWorkItem,
    local: AndroidFileSyncLocalTree,
    remote: AndroidFileSyncRemoteTree,
    stagingRoot: File,
) {
    val localSource = requireNotNull(work.observedLocal)
    val remoteSource = requireNotNull(work.observedRemote)
    require(localSource.kind == SyncEntryKind.File && remoteSource.kind == SyncEntryKind.File) {
        "Keep both currently supports file conflicts only."
    }
    withAndroidFileSyncStagingFile(stagingRoot, "keep-local") { localBytes ->
        withAndroidFileSyncStagingFile(stagingRoot, "keep-remote") { remoteBytes ->
            val stagedLocal = local.stageForUpload(
                operation.relativePath,
                localBytes,
                androidFileSyncStagingTransferLimit(stagingRoot, localSource.size),
                remote::shouldContinueTransfer,
            )
            remote.stageDownload(
                operation.relativePath,
                remoteSource.etag,
                remoteBytes,
                androidFileSyncStagingTransferLimit(stagingRoot, remoteSource.size),
            )
            val localContentHash = requireNotNull(localSource.contentHash) {
                "The local conflict needs exact content evidence."
            }
            require(stagedLocal.contentHash == localContentHash) {
                "The local conflict changed while it was being staged."
            }
            val remoteContentHash = remoteBytes.inputStream().use { input ->
                requireNotNull(
                    sha256SyncContentHash(
                        input = input,
                        expectedBytes = remoteBytes.length(),
                        maximumBytes = remoteBytes.length().coerceAtLeast(1L),
                        shouldContinue = remote::shouldContinueTransfer,
                    ),
                )
            }
            remoteSource.contentHash?.let { expected ->
                require(remoteContentHash == expected) {
                    "The server conflict changed while it was being staged."
                }
            }
            publishAuthenticatedAndroidFileSyncKeepBoth(
                authenticateSource = {
                    local.authenticateFileForReplacement(
                        path = operation.relativePath,
                        expectedLocalRevision = localSource.revision,
                        expectedContentHash = localContentHash,
                        shouldContinue = remote::shouldContinueTransfer,
                    )
                },
                publishConflictCopies = listOf(
                    {
                        ensureExactRemoteAndroidFileSyncConflictCopy(
                            remote,
                            operation.localConflictPath,
                            localBytes,
                        )
                    },
                    {
                        ensureExactLocalAndroidFileSyncConflictCopy(
                            local,
                            operation.localConflictPath,
                            localBytes,
                            localContentHash,
                            remote::shouldContinueTransfer,
                        )
                    },
                    {
                        ensureExactRemoteAndroidFileSyncConflictCopy(
                            remote,
                            operation.remoteConflictPath,
                            remoteBytes,
                        )
                    },
                    {
                        ensureExactLocalAndroidFileSyncConflictCopy(
                            local,
                            operation.remoteConflictPath,
                            remoteBytes,
                            remoteContentHash,
                            remote::shouldContinueTransfer,
                        )
                    },
                ),
                replaceOriginal = {
                    local.writeFileFromStreamForDownload(
                        path = operation.relativePath,
                        expectedLocalRevision = localSource.revision,
                        expectedContentHash = localContentHash,
                        shouldContinue = remote::shouldContinueTransfer,
                    ) { output ->
                        remoteBytes.inputStream().use { input ->
                            copyAndroidFileSyncWithCancellation(
                                input,
                                output,
                                remote::shouldContinueTransfer,
                            )
                        }
                    }
                },
            )
        }
    }
}

private fun ensureExactRemoteAndroidFileSyncConflictCopy(
    remote: AndroidFileSyncRemoteTree,
    path: String,
    source: File,
) = ensureExactAndroidFileSyncConflictCopy(
    exists = { remote.resolve(path) != null },
    create = { remote.writeFile(path, source, expectedRemoteEtag = null) },
    verify = {
        val existing = requireNotNull(remote.resolve(path)) { "The server conflict copy disappeared." }
        remote.verifyDirectUpload(source, path, existing.entry)
    },
)

private fun ensureExactLocalAndroidFileSyncConflictCopy(
    local: AndroidFileSyncLocalTree,
    path: String,
    source: File,
    expectedContentHash: String,
    shouldContinue: () -> Boolean,
) = ensureExactAndroidFileSyncConflictCopy(
    exists = { local.resolve(path) != null },
    create = {
        local.writeFileFromStreamForDownload(
            path = path,
            expectedLocalRevision = null,
            expectedContentHash = null,
            shouldContinue = shouldContinue,
        ) { output ->
            source.inputStream().use { input ->
                copyAndroidFileSyncWithCancellation(input, output, shouldContinue)
            }
        }
    },
    verify = {
        val existing = requireNotNull(local.resolve(path)) { "The local conflict copy disappeared." }
        require(existing.entry.kind == SyncEntryKind.File) { "The local conflict copy changed type." }
        local.authenticateFileForReplacement(
            path = path,
            expectedLocalRevision = existing.entry.revision,
            expectedContentHash = expectedContentHash,
            shouldContinue = shouldContinue,
        )
    },
)

internal inline fun ensureExactAndroidFileSyncConflictCopy(
    exists: () -> Boolean,
    create: () -> Unit,
    verify: () -> Unit,
) {
    if (!exists()) create()
    verify()
}

internal fun copyAndroidFileSyncWithCancellation(
    input: InputStream,
    output: OutputStream,
    shouldContinue: () -> Boolean,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        requireAndroidFileSyncCopyContinuation(shouldContinue)
        val count = input.read(buffer)
        if (count < 0) return
        requireAndroidFileSyncCopyContinuation(shouldContinue)
        output.write(buffer, 0, count)
    }
}

private fun requireAndroidFileSyncCopyContinuation(shouldContinue: () -> Boolean) {
    if (!shouldContinue() || Thread.currentThread().isInterrupted) {
        throw CancellationException("The local conflict copy was cancelled.")
    }
}

internal fun publishAuthenticatedAndroidFileSyncKeepBoth(
    authenticateSource: () -> Unit,
    publishConflictCopies: List<() -> Unit>,
    replaceOriginal: () -> Unit,
) {
    authenticateSource()
    publishConflictCopies.forEach { publish -> publish() }
    replaceOriginal()
}
