package dev.obiente.nextcloudnative

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException

internal data class AndroidSafOwnedDownloadRecoveryDirectory(
    val documentId: String,
    val relativePath: String,
)

internal fun androidSafOwnedDownloadRecoveryPaths(pair: FileSyncPair): Set<String> =
    (pair.baselines.asSequence().map { baseline -> baseline.relativePath } +
        pair.workItems.asSequence().map { work -> work.relativePath })
        .toSet()

internal fun androidSafOwnedDownloadRecoveryDirectories(
    rootDocumentId: String,
    localRecoveryPaths: Set<String>,
    recordedDocumentIds: Set<String> = emptySet(),
): List<AndroidSafOwnedDownloadRecoveryDirectory> {
    val root = NextcloudDocumentIds.parse(rootDocumentId)
    return buildSet {
        add("")
        localRecoveryPaths.mapTo(this, NextcloudDocumentIds::parentPath)
        recordedDocumentIds.forEach { documentId ->
            val reference = runCatching { NextcloudDocumentIds.parse(documentId) }.getOrNull()
                ?: return@forEach
            if (reference.accountKey != root.accountKey) return@forEach
            val parentPath = NextcloudDocumentIds.parentPath(reference.path)
            val relativePath = when {
                root.path.isEmpty() -> parentPath
                parentPath == root.path -> ""
                parentPath.startsWith(root.path + "/") -> parentPath.removePrefix(root.path + "/")
                else -> return@forEach
            }
            add(relativePath)
        }
    }.map { relativePath ->
        val fullPath = listOf(root.path, relativePath).filter(String::isNotBlank).joinToString("/")
        AndroidSafOwnedDownloadRecoveryDirectory(
            documentId = NextcloudDocumentIds.documentId(root.accountKey, fullPath),
            relativePath = relativePath,
        )
    }
}

internal fun androidSafOwnedDownloadRecoveryDirectory(
    rootDocumentId: String,
    directoryDocumentId: String,
): AndroidSafOwnedDownloadRecoveryDirectory? {
    val root = runCatching { NextcloudDocumentIds.parse(rootDocumentId) }.getOrNull() ?: return null
    val directory = runCatching { NextcloudDocumentIds.parse(directoryDocumentId) }.getOrNull() ?: return null
    if (directory.accountKey != root.accountKey) return null
    val relativePath = when {
        root.path.isEmpty() -> directory.path
        directory.path == root.path -> ""
        directory.path.startsWith(root.path + "/") -> directory.path.removePrefix(root.path + "/")
        else -> return null
    }
    return AndroidSafOwnedDownloadRecoveryDirectory(directoryDocumentId, relativePath)
}

internal fun <Directory> reconcileRecordedAndroidSafDownloadDirectories(
    candidates: List<Directory>,
    hasPendingRecovery: () -> Boolean,
    hasPendingForDirectory: (Directory) -> Boolean,
    shouldContinue: () -> Boolean = { true },
    reconcileDirectory: (Directory) -> Unit,
): Boolean {
    if (!hasPendingRecovery()) return true
    candidates.distinct().forEach { candidate ->
        requireAndroidSafRetirementContinuation(shouldContinue)
        if (!hasPendingForDirectory(candidate)) return@forEach
        requireAndroidSafRetirementContinuation(shouldContinue)
        reconcileDirectory(candidate)
    }
    requireAndroidSafRetirementContinuation(shouldContinue)
    return !hasPendingRecovery()
}

internal fun <Directory> reconcileRecordedThenDiscoveredAndroidSafDownloadDirectories(
    recordedCandidates: List<Directory>,
    discoverCandidates: () -> List<Directory>,
    hasPendingRecovery: () -> Boolean,
    hasPendingForDirectory: (Directory) -> Boolean,
    shouldContinue: () -> Boolean = { true },
    reconcileDirectory: (Directory) -> Unit,
): Boolean {
    if (
        reconcileRecordedAndroidSafDownloadDirectories(
            candidates = recordedCandidates,
            hasPendingRecovery = hasPendingRecovery,
            hasPendingForDirectory = hasPendingForDirectory,
            shouldContinue = shouldContinue,
            reconcileDirectory = { candidate ->
                try {
                    reconcileDirectory(candidate)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    // A recorded document ID may be stale after the recovery directory was moved.
                }
            },
        )
    ) return true
    return reconcileRecordedAndroidSafDownloadDirectories(
        candidates = discoverCandidates(),
        hasPendingRecovery = hasPendingRecovery,
        hasPendingForDirectory = hasPendingForDirectory,
        shouldContinue = shouldContinue,
        reconcileDirectory = reconcileDirectory,
    )
}

internal fun requireAndroidSafRetirementContinuation(shouldContinue: () -> Boolean) {
    if (!shouldContinue()) throw CancellationException("Folder sync recovery was cancelled.")
}

internal fun reconcileOwnProviderSafDownloadsBeforePairRemoval(
    context: Context,
    localRootId: String,
    localRecoveryPaths: Set<String>,
    shouldContinue: () -> Boolean,
    providerRecoverySession: NextcloudSession?,
) {
    val appContext = context.applicationContext
    val treeUri = Uri.parse(localRootId)
    val ownership = createAndroidSafDownloadOwnershipStore(appContext, localRootId)
    val indexedOwnership = ownership.indexed()
    val localTree = AndroidSafFileSyncLocalTree(
        resolver = appContext.contentResolver,
        rootId = localRootId,
        downloadOwnershipStore = ownership,
        providerRecoverySession = providerRecoverySession,
    )
    val recordedDocumentIds = ownership.pendingTransactions().asSequence()
        .flatMap { transaction ->
            sequenceOf(transaction.stageDocumentIdentity, transaction.backupDocumentIdentity)
        }
        .filterNotNull()
        .mapNotNull { identity ->
            runCatching {
                identity.takeIf {
                    androidPickerUriRejection(it, appContext.packageName) ==
                        AndroidPickerUriRejection.OwnDocumentsProvider
                }?.let { DocumentsContract.getDocumentId(Uri.parse(it)) }
            }.getOrNull()
        }
        .toSet()
    val recordedCandidates = androidSafOwnedDownloadRecoveryDirectories(
        rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri),
        localRecoveryPaths = localRecoveryPaths,
        recordedDocumentIds = recordedDocumentIds,
    ).map { candidate ->
        candidate to DocumentsContract.buildDocumentUriUsingTree(treeUri, candidate.documentId)
    }
    val hasRelevantPendingRecovery = indexedOwnership::hasPendingTransactions
    check(
        reconcileRecordedThenDiscoveredAndroidSafDownloadDirectories(
            recordedCandidates = recordedCandidates,
            discoverCandidates = {
                localTree.indexRecoveryLocationsIfNeeded(indexedOwnership, shouldContinue)
                indexedOwnership.observedPendingDirectoryIdentities().mapNotNull { identity ->
                    val directoryUri = runCatching { Uri.parse(identity) }.getOrNull()
                        ?: return@mapNotNull null
                    val documentId = runCatching { DocumentsContract.getDocumentId(directoryUri) }.getOrNull()
                        ?: return@mapNotNull null
                    androidSafOwnedDownloadRecoveryDirectory(
                        rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri),
                        directoryDocumentId = documentId,
                    )?.let { candidate -> candidate to directoryUri }
                }
            },
            hasPendingRecovery = hasRelevantPendingRecovery,
            hasPendingForDirectory = { (_, directoryUri) ->
                indexedOwnership.hasPendingTransactionsForDirectory(directoryUri.toString())
            },
            shouldContinue = shouldContinue,
            reconcileDirectory = { (candidate, directoryUri) ->
                localTree.downloadPublisher(
                    parentUri = directoryUri,
                    parentPath = candidate.relativePath,
                    shouldContinue = shouldContinue,
                    ownershipDirectory = indexedOwnership,
                ).reconcileForSync()
            },
        ),
    ) { "A local download still needs safe recovery." }
}
