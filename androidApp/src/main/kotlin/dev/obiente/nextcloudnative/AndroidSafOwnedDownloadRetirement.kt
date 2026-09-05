package dev.obiente.nextcloudnative

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.FileSyncPair

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

internal inline fun <Directory> reconcileRecordedAndroidSafDownloadDirectories(
    candidates: List<Directory>,
    hasPendingRecovery: () -> Boolean,
    hasPendingForDirectory: (Directory) -> Boolean,
    reconcileDirectory: (Directory) -> Unit,
): Boolean {
    if (!hasPendingRecovery()) return true
    candidates.distinct().filter(hasPendingForDirectory).forEach(reconcileDirectory)
    return !hasPendingRecovery()
}

internal fun reconcileOwnProviderSafDownloadsBeforePairRemoval(
    context: Context,
    localRootId: String,
    localRecoveryPaths: Set<String>,
    shouldContinue: () -> Boolean,
) {
    val appContext = context.applicationContext
    val treeUri = Uri.parse(localRootId)
    val ownership = createAndroidSafDownloadOwnershipStore(appContext, localRootId)
    val indexedOwnership = ownership.indexed()
    val localTree = AndroidSafFileSyncLocalTree(
        resolver = appContext.contentResolver,
        rootId = localRootId,
        downloadOwnershipStore = ownership,
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
    val candidates = androidSafOwnedDownloadRecoveryDirectories(
        rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri),
        localRecoveryPaths = localRecoveryPaths,
        recordedDocumentIds = recordedDocumentIds,
    ).map { candidate ->
        candidate to DocumentsContract.buildDocumentUriUsingTree(treeUri, candidate.documentId)
    }
    val hasRelevantPendingRecovery = {
        ownership.hasTreeScopedPendingTransactions() || candidates.any { (_, directoryUri) ->
            ownership.hasPendingTransactionsForDirectory(directoryUri.toString())
        }
    }
    check(
        reconcileRecordedAndroidSafDownloadDirectories(
            candidates = candidates,
            hasPendingRecovery = hasRelevantPendingRecovery,
            hasPendingForDirectory = { (_, directoryUri) ->
                ownership.hasPendingTransactionsForDirectory(directoryUri.toString())
            },
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
