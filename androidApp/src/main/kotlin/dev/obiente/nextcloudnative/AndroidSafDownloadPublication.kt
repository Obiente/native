package dev.obiente.nextcloudnative

import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal data class AndroidSafPublicationDocument<Document>(
    val document: Document,
    val displayName: String,
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
        ownership.transactions(observedNames).forEach { transaction ->
            val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
            val stage = ownedDocument(transaction.stageName)
            val backup = ownedDocument(transaction.backupName)
            when {
                transaction.publicationCompleted && backup != null && stage == null ->
                    deleteBestEffort(backup.document)
                backup != null && stage != null && final == null -> {
                    restoreBackup(transaction, backup)
                    deleteBestEffort(stage.document)
                }
                backup != null && stage == null && final == null && !transaction.publicationAttempted ->
                    restoreBackup(transaction, backup)
                backup == null && stage != null ->
                    deleteBestEffort(stage.document)
            }
            retireRecoveredOwnershipBestEffort(transaction)
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
        reconcile()
        var transaction = AndroidSafOwnedDownloadTransaction(finalName, requireValidToken(newToken()))
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
                val renamed = requireNotNull(directory.rename(current, transaction.backupName)) {
                    "The existing local item could not be protected before replacement."
                }
                require(ownedDocument(transaction.backupName)?.document == renamed) {
                    "The local file provider changed the protected item identity."
                }
                renamed
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                recoverBeforePublicationOrSuppress(transaction, failure)
                retireRecoveredOwnershipBestEffort(transaction)
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
            retireRecoveredOwnershipBestEffort(transaction)
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
    ) {
        try {
            recoverBeforePublication(transaction)
        } catch (cancelled: CancellationException) {
            cancelled.addSuppressed(originalFailure)
            throw cancelled
        } catch (recoveryFailure: Throwable) {
            originalFailure.addSuppressed(recoveryFailure)
        }
    }

    fun hasPendingRecovery(): Boolean {
        val observedNames = directory.documents().mapTo(mutableSetOf()) { it.displayName }
        return ownership.transactions(observedNames).isNotEmpty()
    }

    private fun ownedDocument(name: String): AndroidSafPublicationDocument<Document>? =
        directory.documents().singleOrNull { it.displayName == name }

    private fun isOwnedDocument(
        name: String,
        document: Document,
    ): Boolean = directory.documents().any { it.displayName == name && it.document == document }

    private fun recoverBeforePublication(transaction: AndroidSafOwnedDownloadTransaction) {
        val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
        val stage = ownedDocument(transaction.stageName)
        val backup = ownedDocument(transaction.backupName)
        if (final != null && stage == null) {
            return
        }
        if (backup != null && final == null) restoreBackup(transaction, backup)
        ownedDocument(transaction.stageName)?.let {
            deleteBestEffort(it.document)
        }
    }

    private fun restoreBackup(
        transaction: AndroidSafOwnedDownloadTransaction,
        backup: AndroidSafPublicationDocument<Document>,
    ) {
        try {
            directory.rename(backup.document, transaction.finalName)
        } catch (failure: Throwable) {
            val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
            val remainingBackup = ownedDocument(transaction.backupName)
            if (failure is CancellationException) throw failure
            if (final != null && remainingBackup == null) return
            throw failure
        }
        val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
        val remainingBackup = ownedDocument(transaction.backupName)
        require(final != null && remainingBackup == null) {
            "The protected local item could not be restored."
        }
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
        if (ownedDocument(transaction.stageName) == null && ownedDocument(transaction.backupName) == null) {
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
) {
    init {
        require(finalName.isNotBlank() && '/' !in finalName && finalName.none(Char::isISOControl))
        require(token == requireValidToken(token))
        require(!publicationCompleted || publicationAttempted)
    }

    val stageName: String = ".$finalName.nextcloud-native-download-$token"
    val backupName: String = ".$finalName.nextcloud-native-backup-$token"
}

internal fun requireValidToken(token: String): String {
    require(UUID.fromString(token).toString() == token.lowercase())
    return token.lowercase()
}
