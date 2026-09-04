package dev.obiente.nextcloudnative

import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal data class AndroidSafPublicationDocument<Document>(
    val document: Document,
    val displayName: String,
    val documentIdentity: String = document.toString(),
)

internal interface AndroidSafPublicationDirectory<Document> {
    fun documents(): List<AndroidSafPublicationDocument<Document>>
    fun createFile(displayName: String): Document
    fun createDirectory(displayName: String): Document
    fun writeFile(document: Document, write: (OutputStream) -> Unit)
    fun rename(document: Document, displayName: String): Document?
    fun delete(document: Document): Boolean
}

internal interface AndroidSafDownloadOwnership {
    fun transactions(observedNames: Set<String> = emptySet()): List<AndroidSafOwnedDownloadTransaction>
    fun add(transaction: AndroidSafOwnedDownloadTransaction)
    fun replace(transaction: AndroidSafOwnedDownloadTransaction)
    fun remove(transaction: AndroidSafOwnedDownloadTransaction)
}

/**
 * Owns the recoverable rename sequence for one SAF parent directory.
 *
 * A completed stage is protected by a token-matched backup before publication. Internal stage and
 * backup names stay out of normal scans. A later adapter instance can therefore finish cleanup or
 * restore the protected item without presenting recovery files as user content.
 */
internal class AndroidSafDownloadPublisher<Document>(
    private val directory: AndroidSafPublicationDirectory<Document>,
    private val ownership: AndroidSafDownloadOwnership,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    fun reconcile() {
        val observedNames = directory.documents().mapTo(mutableSetOf()) { it.displayName }
        ownership.transactions(observedNames).forEach { originalTransaction ->
            var transaction = originalTransaction
            val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
            val stage = ownedDocument(transaction.stageName)
            val backup = backupDocument(transaction)
            if (
                backup != null &&
                (!transaction.backupProtected || backup.displayName != transaction.backupName) &&
                !transaction.publicationCompleted
            ) {
                transaction = transaction.copy(
                    backupDisplayName = backup.displayName.takeIf { it != transaction.backupName }
                        ?: transaction.backupDisplayName,
                    backupProtected = true,
                )
                ownership.replace(transaction)
            }
            if (backup == null && originalDocumentIsFinal(transaction, final)) {
                transaction = clearRestoredBackupName(transaction)
            }
            when {
                transaction.publicationCompleted && backup != null && stage == null ->
                    deleteBestEffort(backup.document)
                backup != null && stage != null && final == null -> {
                    transaction = restoreBackup(transaction, backup)
                    deleteBestEffort(stage.document)
                }
                backup != null && stage == null && final == null && !transaction.publicationAttempted ->
                    transaction = restoreBackup(transaction, backup)
                backup == null && stage != null && !transaction.backupProtected ->
                    deleteBestEffort(stage.document)
            }
            retireRecoveredOwnershipBestEffort(transaction)
        }
    }

    fun reconcileForSync() {
        reconcile()
        require(!hasPendingRecovery()) {
            "A local download still needs safe recovery."
        }
    }

    fun publish(
        finalName: String,
        currentDocument: Document?,
        revalidateCurrent: () -> Unit = {},
        write: (OutputStream) -> Unit,
    ) = publish(
        finalName = finalName,
        currentDocument = currentDocument,
        createStage = directory::createFile,
        revalidateCurrent = revalidateCurrent,
        prepareStage = { stage -> directory.writeFile(stage, write) },
    )

    fun publish(
        finalName: String,
        currentDocument: Document?,
        createStage: (displayName: String) -> Document,
        revalidateCurrent: () -> Unit = {},
        prepareStage: (Document) -> Unit,
    ) {
        require(finalName.isNotBlank() && '/' !in finalName && finalName.none(Char::isISOControl))
        reconcileForSync()
        val currentIdentity = currentDocument?.let { current ->
            requireNotNull(directory.documents().singleOrNull { it.document == current }) {
                "The local item identity could not be resolved before replacement."
            }.documentIdentity
        }
        var transaction = AndroidSafOwnedDownloadTransaction(
            finalName = finalName,
            token = requireValidToken(newToken()),
            backupDocumentIdentity = currentIdentity,
        )
        ownership.add(transaction)
        val stage = try {
            createStage(transaction.stageName)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            retireRecoveredOwnershipBestEffort(transaction)
            throw failure
        }
        if (!isOwnedDocument(transaction.stageName, stage)) {
            deleteBestEffort(stage)
            retireRecoveredOwnershipBestEffort(transaction)
            error("The local file provider changed the recovery stage name.")
        }

        try {
            prepareStage(stage)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            deleteBestEffort(stage)
            retireRecoveredOwnershipBestEffort(transaction)
            throw failure
        }

        try {
            revalidateCurrent()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            deleteBestEffort(stage)
            retireRecoveredOwnershipBestEffort(transaction)
            throw failure
        }

        val backup = currentDocument?.let { current ->
            try {
                transaction = transaction.copy(backupProtected = true)
                ownership.replace(transaction)
                val renamed = requireNotNull(directory.rename(current, transaction.backupName)) {
                    "The existing local item could not be protected before replacement."
                }
                val protected = requireNotNull(
                    directory.documents().singleOrNull { document -> document.document == renamed },
                ) { "The protected local item could not be resolved after rename." }
                transaction = transaction.copy(
                    backupDisplayName = protected.displayName.takeIf { it != transaction.backupName },
                    backupProtected = true,
                )
                ownership.replace(transaction)
                require(ownedDocument(transaction.backupName)?.document == renamed) {
                    "The local file provider changed the protected item identity."
                }
                renamed
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                recoverBeforePublicationOrSuppress(transaction, failure)
                    ?.let(::retireRecoveredOwnershipBestEffort)
                throw failure
            }
        }
        transaction = markPublicationAttempted(transaction)

        try {
            val publishedDocument = requireNotNull(directory.rename(stage, finalName)) {
                "The staged local file could not be published."
            }
            val final = directory.documents().singleOrNull { it.displayName == finalName }
            val remainingStage = ownedDocument(transaction.stageName)
            require(final?.document == publishedDocument && remainingStage == null) {
                "The staged local file could not be published."
            }
            transaction = markPublished(transaction)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (isPublished(transaction, stage)) {
                transaction = markPublished(transaction)
                backup?.let(::deleteBestEffort)
                retireRecoveredOwnershipBestEffort(transaction)
                return
            }
            recoverBeforePublicationOrSuppress(transaction, failure)
                ?.let(::retireRecoveredOwnershipBestEffort)
            throw failure
        }
        backup?.let(::deleteBestEffort)
        retireRecoveredOwnershipBestEffort(transaction)
    }

    fun visibleDocuments(
        documents: List<AndroidSafPublicationDocument<Document>> = directory.documents(),
    ): List<AndroidSafPublicationDocument<Document>> {
        val observedNames = documents.mapTo(mutableSetOf()) { it.displayName }
        val ownedNames = ownership.transactions(observedNames).flatMapTo(mutableSetOf()) { transaction ->
            listOf(transaction.stageName, transaction.backupName)
        }
        return documents.filterNot { it.displayName in ownedNames }
    }

    private fun recoverBeforePublicationOrSuppress(
        transaction: AndroidSafOwnedDownloadTransaction,
        originalFailure: Throwable,
    ): AndroidSafOwnedDownloadTransaction? =
        try {
            recoverBeforePublication(transaction)
        } catch (cancelled: CancellationException) {
            cancelled.addSuppressed(originalFailure)
            throw cancelled
        } catch (recoveryFailure: Throwable) {
            originalFailure.addSuppressed(recoveryFailure)
            null
        }

    fun hasPendingRecovery(): Boolean {
        val observedNames = directory.documents().mapTo(mutableSetOf()) { it.displayName }
        return ownership.transactions(observedNames).isNotEmpty()
    }

    private fun ownedDocument(name: String): AndroidSafPublicationDocument<Document>? =
        directory.documents().singleOrNull { it.displayName == name }

    private fun backupDocument(
        transaction: AndroidSafOwnedDownloadTransaction,
    ): AndroidSafPublicationDocument<Document>? = ownedDocument(transaction.backupName)
        ?: transaction.backupDocumentIdentity?.let { identity ->
            directory.documents().singleOrNull { document ->
                document.documentIdentity == identity && document.displayName != transaction.finalName
            }
        }
        ?: directory.documents().singleOrNull { document ->
            transaction.token in document.displayName &&
                document.displayName != transaction.stageName &&
                document.displayName != transaction.finalName
        }

    private fun originalDocumentIsFinal(
        transaction: AndroidSafOwnedDownloadTransaction,
        final: AndroidSafPublicationDocument<Document>?,
    ): Boolean = transaction.backupDocumentIdentity != null &&
        final?.documentIdentity == transaction.backupDocumentIdentity

    private fun isOwnedDocument(
        name: String,
        document: Document,
    ): Boolean = directory.documents().any { it.displayName == name && it.document == document }

    private fun recoverBeforePublication(
        transaction: AndroidSafOwnedDownloadTransaction,
    ): AndroidSafOwnedDownloadTransaction {
        val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
        val stage = ownedDocument(transaction.stageName)
        val backup = backupDocument(transaction)
        if (backup == null && originalDocumentIsFinal(transaction, final)) {
            val recovered = clearRestoredBackupName(transaction)
            stage?.let { deleteBestEffort(it.document) }
            return recovered
        }
        if (final != null && stage == null) {
            return transaction
        }
        check(!transaction.backupProtected || backup != null) {
            "The protected local item identity is unavailable."
        }
        val recovered = if (backup != null && final == null) {
            restoreBackup(transaction, backup)
        } else {
            transaction
        }
        ownedDocument(transaction.stageName)?.let {
            deleteBestEffort(it.document)
        }
        return recovered
    }

    private fun restoreBackup(
        transaction: AndroidSafOwnedDownloadTransaction,
        backup: AndroidSafPublicationDocument<Document>,
    ): AndroidSafOwnedDownloadTransaction {
        val restoredDocument = try {
            requireNotNull(directory.rename(backup.document, transaction.finalName)) {
                "The protected local item could not be restored."
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val documents = directory.documents()
            val restored = documents.singleOrNull { it.document == backup.document }
            val exactRestored = documents.singleOrNull {
                it.displayName == transaction.finalName && it.document == backup.document
            }
            val remainingBackup = documents.singleOrNull {
                it.displayName == transaction.backupName && it.document == backup.document
            }
            if (exactRestored != null && remainingBackup == null) {
                return clearRestoredBackupName(transaction)
            }
            var recovered = transaction
            if (restored != null && restored.displayName != transaction.backupName) {
                recovered = transaction.copy(backupDisplayName = restored.displayName)
                ownership.replace(recovered)
            }
            throw failure
        }
        val restored = requireNotNull(
            directory.documents().singleOrNull { it.document == restoredDocument },
        ) { "The restored local item could not be resolved after rename." }
        if (restored.displayName != transaction.finalName) {
            ownership.replace(transaction.copy(backupDisplayName = restored.displayName))
            error("The local file provider changed the restored item name.")
        }
        require(restored.document == restoredDocument && ownedDocument(transaction.backupName) == null) {
            "The protected local item could not be restored."
        }
        return clearRestoredBackupName(transaction)
    }

    private fun clearRestoredBackupName(
        transaction: AndroidSafOwnedDownloadTransaction,
    ): AndroidSafOwnedDownloadTransaction {
        if (transaction.backupDisplayName == null && !transaction.backupProtected) return transaction
        val restored = transaction.copy(backupDisplayName = null, backupProtected = false)
        ownership.replace(restored)
        return restored
    }

    private fun isPublished(
        transaction: AndroidSafOwnedDownloadTransaction,
        stage: Document,
    ): Boolean =
        directory.documents().any { document ->
            document.displayName == transaction.finalName && document.document == stage
        } &&
            ownedDocument(transaction.stageName) == null

    private fun markPublished(transaction: AndroidSafOwnedDownloadTransaction): AndroidSafOwnedDownloadTransaction {
        val published = transaction.copy(publicationAttempted = true, publicationCompleted = true)
        ownership.replace(published)
        return published
    }

    private fun markPublicationAttempted(
        transaction: AndroidSafOwnedDownloadTransaction,
    ): AndroidSafOwnedDownloadTransaction {
        val attempted = transaction.copy(publicationAttempted = true)
        ownership.replace(attempted)
        return attempted
    }

    private fun deleteBestEffort(document: Document) {
        try {
            directory.delete(document)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // The reserved recovery name stays hidden and a later scan retries cleanup.
        }
    }

    private fun retireRecoveredOwnership(transaction: AndroidSafOwnedDownloadTransaction) {
        if (
            ownedDocument(transaction.stageName) == null &&
            ownedDocument(transaction.backupName) == null &&
            (!transaction.backupProtected || transaction.publicationCompleted)
        ) {
            ownership.remove(transaction)
        }
    }

    private fun retireRecoveredOwnershipBestEffort(transaction: AndroidSafOwnedDownloadTransaction) {
        try {
            retireRecoveredOwnership(transaction)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // The durable record is safe to replay after the physical recovery already completed.
        }
    }
}

internal data class AndroidSafOwnedDownloadTransaction(
    val finalName: String,
    val token: String,
    val publicationAttempted: Boolean = false,
    val publicationCompleted: Boolean = false,
    val backupDisplayName: String? = null,
    val backupProtected: Boolean = backupDisplayName != null,
    val backupDocumentIdentity: String? = null,
) {
    val stageName: String = ".nextcloud-native-download-$token"
    val backupName: String = backupDisplayName ?: ".nextcloud-native-backup-$token"

    init {
        require(finalName.isNotBlank() && '/' !in finalName && finalName.none(Char::isISOControl))
        require(token == requireValidToken(token))
        require(!publicationCompleted || publicationAttempted)
        require(backupDisplayName == null || backupProtected)
        require(backupDocumentIdentity == null || backupDocumentIdentity.isNotBlank())
        require(
            backupDisplayName == null ||
                backupDisplayName.isNotBlank() &&
                '/' !in backupDisplayName &&
                backupDisplayName.none(Char::isISOControl) &&
                backupDisplayName != finalName &&
                backupDisplayName != stageName,
        ) { "The protected local item name is invalid." }
    }
}

internal fun requireValidToken(token: String): String {
    require(UUID.fromString(token).toString() == token.lowercase())
    return token.lowercase()
}
