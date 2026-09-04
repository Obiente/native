package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncDecisionReason
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncWorkItem
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.planFileSync
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class AndroidSafReplacementEvidence(
    val entry: LocalSyncEntry,
    val documentIdentity: String,
    val displayName: String,
    val contentHash: String?,
)

internal fun AndroidLocalSyncDocument.androidSafReplacementEvidence(
    contentHash: String? = entry.contentHash,
): AndroidSafReplacementEvidence = AndroidSafReplacementEvidence(
    entry = entry,
    documentIdentity = uri.toString(),
    displayName = displayName,
    contentHash = contentHash,
)

internal fun requireUnchangedAndroidSafReplacement(
    expected: List<AndroidSafReplacementEvidence>?,
    actual: List<AndroidSafReplacementEvidence>?,
) {
    require(actual == expected) { "The local item changed while replacement content was staged." }
}

internal fun androidSafReplacementRevision(
    evidence: List<AndroidSafReplacementEvidence>,
): String {
    require(evidence.isNotEmpty()) { "The local replacement evidence is empty." }
    val digest = MessageDigest.getInstance("SHA-256")
    evidence.sortedBy { it.entry.relativePath }.forEach { item ->
        listOf(
            item.entry.relativePath,
            item.entry.kind.name,
            item.entry.revision,
            item.entry.size?.toString().orEmpty(),
            item.documentIdentity,
            item.displayName,
            item.contentHash.orEmpty(),
        ).forEach { value ->
            digest.update(value.encodeToByteArray())
            digest.update(0.toByte())
        }
    }
    return "saf-tree-sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun requireExpectedAndroidSafReplacement(
    expected: LocalSyncEntry,
    actual: List<AndroidSafReplacementEvidence>,
) {
    val root = requireNotNull(actual.singleOrNull { it.entry.relativePath == expected.relativePath }) {
        "The local replacement root changed after the sync scan."
    }
    require(root.entry.kind == expected.kind && root.entry.size == expected.size) {
        "The local replacement type or size changed after the sync scan."
    }
    when (expected.kind) {
        SyncEntryKind.File -> {
            require(root.entry.revision == expected.revision) {
                "The local file changed after the sync scan."
            }
            requireNotNull(expected.contentHash) {
                "The local file needs exact content verification before replacement."
            }
            require(root.contentHash == expected.contentHash) {
                "The local file content changed after the sync scan."
            }
        }
        SyncEntryKind.Directory -> require(
            androidSafReplacementRevision(actual) == expected.revision,
        ) { "The local folder content changed after the sync scan." }
    }
}

internal fun hashAndroidSafReplacementContent(
    input: InputStream,
    expectedBytes: Long?,
    shouldContinue: () -> Boolean,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var bytesRead = 0L
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (!shouldContinue() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Local replacement verification cancelled.")
        }
        val count = input.read(buffer)
        if (count < 0) break
        bytesRead = Math.addExact(bytesRead, count.toLong())
        digest.update(buffer, 0, count)
    }
    require(expectedBytes == null || expectedBytes == bytesRead) {
        "The local replacement item changed during content verification."
    }
    if (!shouldContinue() || Thread.currentThread().isInterrupted) {
        throw CancellationException("Local replacement verification cancelled.")
    }
    return "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun strengthenAndroidSafReplacementEntries(
    documents: List<AndroidLocalSyncDocument>,
    protectedPaths: Set<String>,
    contentHash: (AndroidLocalSyncDocument) -> String,
): List<AndroidLocalSyncDocument> {
    val scanned = documents.associateBy { it.entry.relativePath }
    val sortedPaths = scanned.keys.sorted()
    val strengthened = scanned.toMutableMap()
    val computedHashes = mutableMapOf<String, String>()
    protectedPaths.sorted().forEach { path ->
        val root = scanned[path] ?: return@forEach
        val scopedPaths = androidSafReplacementScopedPaths(sortedPaths, path, root.entry.kind)
        fun exactHash(document: AndroidLocalSyncDocument): String {
            return document.entry.contentHash ?: computedHashes.getOrPut(document.entry.relativePath) {
                contentHash(document)
            }
        }
        if (root.entry.kind == SyncEntryKind.File) {
            strengthened[path] = root.copy(
                entry = root.entry.copy(contentHash = exactHash(root)),
            )
        } else {
            val evidence = scopedPaths.map { scopedPath ->
                val document = requireNotNull(scanned[scopedPath])
                document.androidSafReplacementEvidence(
                    contentHash = if (document.entry.kind == SyncEntryKind.File) {
                        exactHash(document)
                    } else {
                        null
                    },
                )
            }
            strengthened[path] = root.copy(
                entry = root.entry.copy(revision = androidSafReplacementRevision(evidence)),
            )
        }
    }
    return documents.map { document -> requireNotNull(strengthened[document.entry.relativePath]) }
}

internal fun androidSafReplacementScopedPaths(
    sortedPaths: List<String>,
    rootPath: String,
    rootKind: SyncEntryKind,
): List<String> {
    if (rootKind == SyncEntryKind.File) return listOf(rootPath)
    val prefix = "$rootPath/"
    var low = 0
    var high = sortedPaths.size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (sortedPaths[middle] < prefix) low = middle + 1 else high = middle
    }
    return buildList {
        add(rootPath)
        var index = low
        while (index < sortedPaths.size && sortedPaths[index].startsWith(prefix)) {
            add(sortedPaths[index])
            index += 1
        }
    }
}

internal fun androidFileSyncProtectedReplacementPaths(
    operations: List<FileSyncOperation>,
    localPaths: Set<String>,
): Set<String> = operations.mapNotNullTo(mutableSetOf()) { operation ->
    operation.relativePath.takeIf { path ->
        path in localPaths && when (operation) {
            is FileSyncOperation.Download -> operation.expectedLocalRevision != null
            is FileSyncOperation.DeleteLocal -> true
            is FileSyncOperation.NeedsDecision -> when (operation.reason) {
                FileSyncDecisionReason.FirstSyncCollision,
                FileSyncDecisionReason.SimultaneousEdit,
                FileSyncDecisionReason.TypeChanged,
                FileSyncDecisionReason.RemoteDeletion,
                -> true
                FileSyncDecisionReason.LocalDeletion -> false
            }
            else -> false
        }
    }
}

internal fun strengthenAndroidFileSyncReplacementEntries(
    local: AndroidFileSyncLocalTree,
    documents: List<AndroidLocalSyncDocument>,
    remoteEntries: List<RemoteSyncEntry>,
    baselines: List<FileSyncBaseline>,
    configuration: FileSyncConfiguration,
    shouldContinue: () -> Boolean,
): List<AndroidLocalSyncDocument> {
    val localPaths = documents.mapTo(mutableSetOf()) { it.entry.relativePath }
    val protectedPaths = androidFileSyncProtectedReplacementPaths(
        operations = planFileSync(
            documents.map(AndroidLocalSyncDocument::entry),
            remoteEntries,
            baselines,
            configuration,
        ).operations,
        localPaths = localPaths,
    )
    return local.strengthenReplacementEntries(documents, protectedPaths, shouldContinue)
}

internal fun downloadAndroidFileSyncOperation(
    local: AndroidFileSyncLocalTree,
    remote: AndroidFileSyncRemoteTree,
    operation: FileSyncOperation.Download,
    work: FileSyncWorkItem,
) {
    val source = requireNotNull(work.observedRemote)
    if (source.kind == SyncEntryKind.Directory) {
        local.createDirectoryForDownload(
            path = operation.relativePath,
            expectedLocalRevision = operation.expectedLocalRevision,
            expectedContentHash = work.observedLocal?.contentHash,
            shouldContinue = remote::shouldContinueTransfer,
        )
    } else {
        streamAndroidFileSyncDownload(
            declaredByteCount = source.size,
            writeLocal = { write ->
                local.writeFileFromStreamForDownload(
                    path = operation.relativePath,
                    expectedLocalRevision = operation.expectedLocalRevision,
                    expectedContentHash = work.observedLocal?.contentHash,
                    shouldContinue = remote::shouldContinueTransfer,
                    write = write,
                )
            },
            readRemote = { destination, maximumBytes ->
                remote.streamDownload(operation.relativePath, source.etag, destination, maximumBytes)
            },
        )
    }
}

internal fun deleteAndroidFileSyncOperation(
    local: AndroidFileSyncLocalTree,
    remote: AndroidFileSyncRemoteTree,
    operation: FileSyncOperation.DeleteLocal,
    work: FileSyncWorkItem,
) {
    local.deleteForSync(
        path = operation.relativePath,
        expectedLocalRevision = operation.expectedLocalRevision,
        expectedContentHash = work.observedLocal?.contentHash,
        shouldContinue = remote::shouldContinueTransfer,
    )
}
