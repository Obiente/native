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

internal interface AndroidSafDownloadOwnershipDirectory {
    fun hasPendingTransactions(): Boolean
    fun hasPendingTransactionsForDirectory(directoryIdentity: String): Boolean =
        forDirectory(directoryIdentity).transactions().isNotEmpty()
    fun observedPendingDirectoryIdentities(): Set<String> = emptySet()
    fun forDirectory(directoryIdentity: String): AndroidSafDownloadOwnership
    fun observeRecoveryNames(directoryIdentity: String, observedNames: Set<String>) = Unit
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
    private val contentIdentity: (Document) -> String?,
) {
    fun reconcile() {
        val observedNames = directory.documents().mapTo(mutableSetOf()) { it.displayName }
        ownership.transactions(observedNames).forEach { originalTransaction ->
            var transaction = authenticatePendingStageCreation(originalTransaction)
            val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
            val stage = stageDocument(transaction)
            val backup = backupDocument(transaction)
            var publicationFailureAccountedFor = false
            var ownershipReleased = false
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
            if (
                !transaction.publicationCompleted &&
                transaction.publicationAttempted &&
                transaction.stageDocumentIdentity != null &&
                stage == null &&
                authenticatedPublishedFinal(transaction, final)
            ) {
                transaction = markPublished(transaction)
            }
            when {
                transaction.publicationCompleted && backup != null && stage == null -> {
                    ownershipReleased = cleanupPublishedBackup(transaction, backup)
                }
                backup != null && stage != null && final == null -> {
                    transaction = restoreBackup(transaction, backup)
                    deleteBestEffort(stage.document)
                }
                backup != null && stage == null && final == null && !transaction.publicationAttempted ->
                    transaction = restoreBackup(transaction, backup)
                backup == null && stage != null && !transaction.backupProtected -> {
                    deleteBestEffort(stage.document)
                    publicationFailureAccountedFor = final == null && stageDocument(transaction) == null
                }
            }
            if (!ownershipReleased) {
                retireRecoveredOwnershipBestEffort(transaction, publicationFailureAccountedFor)
            }
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
        backupContentIdentity: String? = null,
        revalidateCurrent: () -> Unit = {},
        write: (OutputStream) -> Unit,
    ) = publish(
        finalName = finalName,
        currentDocument = currentDocument,
        backupContentIdentity = backupContentIdentity,
        createStage = directory::createFile,
        revalidateCurrent = revalidateCurrent,
        prepareStage = { stage -> directory.writeFile(stage, write) },
    )

    fun publish(
        finalName: String,
        currentDocument: Document?,
        backupContentIdentity: String? = null,
        createStage: (displayName: String) -> Document,
        revalidateCurrent: () -> Unit = {},
        prepareStage: (Document) -> Unit,
    ) {
        require(finalName.isNotBlank() && '/' !in finalName && finalName.none(Char::isISOControl))
        reconcileForSync()
        val authenticatedBackupContentIdentity = backupContentIdentity ?: currentDocument?.let(contentIdentity)
        val currentIdentity = currentDocument?.let { current ->
            requireNotNull(directory.documents().singleOrNull { it.document == current }) {
                "The local item identity could not be resolved before replacement."
            }.documentIdentity
        }
        var transaction = AndroidSafOwnedDownloadTransaction(
            finalName = finalName,
            token = requireValidToken(newToken()),
            backupDocumentIdentity = currentIdentity,
            backupContentIdentity = authenticatedBackupContentIdentity,
        )
        require(documentNamed(transaction.stageName) == null) {
            "The local recovery stage name is already in use."
        }
        ownership.add(transaction)
        val stage = createStage(transaction.stageName)
        val staged = requireNotNull(directory.documents().singleOrNull { document -> document.document == stage }) {
            "The local file provider did not return the created recovery stage."
        }
        transaction = transaction.copy(stageDocumentIdentity = staged.documentIdentity)
        ownership.replace(transaction)
        if (staged.displayName != transaction.stageName) {
            val failure = IllegalStateException("The local file provider changed the recovery stage name.")
            deleteMisnamedStageOrThrow(stage, failure)
            retireRecoveredOwnershipBestEffort(transaction, publicationFailureAccountedFor = true)
            throw failure
        }
        require(stageDocument(transaction)?.document == stage) {
            "The local file provider changed the recovery stage identity."
        }

        try {
            prepareStage(stage)
            val stageContentIdentity = requireNotNull(contentIdentity(stage)) {
                "The staged local content could not be authenticated before publication."
            }
            val authenticated = transaction.copy(stageContentIdentity = stageContentIdentity)
            ownership.replace(authenticated)
            transaction = authenticated
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
                    backupDocumentIdentity = protected.documentIdentity,
                )
                ownership.replace(transaction)
                require(documentNamed(transaction.backupName)?.document == renamed) {
                    "The local file provider changed the protected item identity."
                }
                renamed
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                recoverBeforePublicationOrSuppress(transaction, failure)
                    ?.let { recovered -> retireRecoveredOwnershipBestEffort(recovered) }
                throw failure
            }
        }
        transaction = markPublicationAttempted(transaction)

        var renamedStage: Document? = null
        try {
            val publishedDocument = requireNotNull(directory.rename(stage, finalName)) {
                "The staged local file could not be published."
            }
            renamedStage = publishedDocument
            val final = directory.documents().singleOrNull { it.displayName == finalName }
            val remainingStage = stageDocument(transaction)
            require(final?.document == publishedDocument && remainingStage == null) {
                "The staged local file could not be published."
            }
            transaction = markPublished(transaction)
        } catch (failure: Throwable) {
            if (failure is CancellationException && renamedStage == null) throw failure
            if (isPublished(transaction, stage, renamedStage)) {
                transaction = markPublished(transaction)
                val ownershipReleased = backupDocument(transaction)
                    ?.let { cleanupPublishedBackup(transaction, it) } == true
                if (!ownershipReleased) retireRecoveredOwnershipBestEffort(transaction)
                if (failure is CancellationException) throw failure
                return
            }
            val stageStillOwned = stageDocument(transaction)?.document == stage
            renamedStage?.let { document -> deleteMisnamedStageOrThrow(document, failure) }
            recoverBeforePublicationOrSuppress(transaction, failure)
                ?.let { recovered ->
                    retireRecoveredOwnershipBestEffort(
                        recovered,
                        publicationFailureAccountedFor = renamedStage != null || stageStillOwned,
                    )
                }
            throw failure
        }
        val ownershipReleased = backupDocument(transaction)
            ?.let { cleanupPublishedBackup(transaction, it) } == true
        if (!ownershipReleased) retireRecoveredOwnershipBestEffort(transaction)
    }

    fun delete(
        finalName: String,
        currentDocument: Document,
        backupContentIdentity: String,
    ) {
        require(finalName.isNotBlank() && '/' !in finalName && finalName.none(Char::isISOControl))
        reconcileForSync()
        val current = requireNotNull(
            directory.documents().singleOrNull { document ->
                document.document == currentDocument && document.displayName == finalName
            },
        ) { "The local item identity could not be resolved before deletion." }
        require(contentIdentity(current.document) == backupContentIdentity) {
            "The local item changed before deletion."
        }
        var transaction = AndroidSafOwnedDownloadTransaction(
            finalName = finalName,
            token = requireValidToken(newToken()),
            backupDocumentIdentity = current.documentIdentity,
            backupContentIdentity = backupContentIdentity,
        )
        ownership.add(transaction)
        val backup = try {
            transaction = transaction.copy(backupProtected = true)
            ownership.replace(transaction)
            val renamed = requireNotNull(directory.rename(current.document, transaction.backupName)) {
                "The local item could not be protected before deletion."
            }
            val protected = requireNotNull(
                directory.documents().singleOrNull { document -> document.document == renamed },
            ) { "The protected local item could not be resolved after rename." }
            transaction = transaction.copy(
                backupDisplayName = protected.displayName.takeIf { it != transaction.backupName },
                backupDocumentIdentity = protected.documentIdentity,
            )
            ownership.replace(transaction)
            require(documentNamed(transaction.backupName)?.document == renamed) {
                "The local file provider changed the protected item identity."
            }
            protected
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            recoverBeforePublicationOrSuppress(transaction, failure)
                ?.let { recovered -> retireRecoveredOwnershipBestEffort(recovered) }
            throw failure
        }
        if (contentIdentity(backup.document) != backupContentIdentity) {
            preserveChangedBackup(transaction, backup)
            return
        }
        transaction = markPublished(markPublicationAttempted(transaction))
        val persistedBackup = requireNotNull(backupDocument(transaction)) {
            "The protected local item identity is unavailable."
        }
        val ownershipReleased = cleanupPublishedBackup(transaction, persistedBackup)
        if (!ownershipReleased) retireRecoveredOwnershipBestEffort(transaction)
    }

    fun visibleDocuments(
        documents: List<AndroidSafPublicationDocument<Document>> = directory.documents(),
    ): List<AndroidSafPublicationDocument<Document>> {
        val observedNames = documents.mapTo(mutableSetOf()) { it.displayName }
        val ownedNames = ownership.transactions(observedNames).flatMapTo(mutableSetOf()) { transaction ->
            listOfNotNull(
                stageDocument(transaction, documents)?.displayName,
                backupDocument(transaction, documents)?.displayName,
            ) + recoveryDocuments(transaction, documents).map { document -> document.displayName }
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

    private fun documentNamed(name: String): AndroidSafPublicationDocument<Document>? =
        directory.documents().singleOrNull { it.displayName == name }

    private fun authenticatePendingStageCreation(
        transaction: AndroidSafOwnedDownloadTransaction,
    ): AndroidSafOwnedDownloadTransaction {
        if (transaction.stageDocumentIdentity != null) return transaction
        val documents = directory.documents()
        val stage = documents.singleOrNull { it.displayName == transaction.stageName }
            ?: recoveryDocuments(transaction, documents).singleOrNull()
            ?: return transaction
        return transaction.copy(stageDocumentIdentity = stage.documentIdentity).also(ownership::replace)
    }

    private fun recoveryDocuments(
        transaction: AndroidSafOwnedDownloadTransaction,
        documents: List<AndroidSafPublicationDocument<Document>> = directory.documents(),
    ): List<AndroidSafPublicationDocument<Document>> = if (transaction.stageDocumentIdentity != null) {
        emptyList()
    } else documents.filter { document ->
        transaction.token in document.displayName &&
            document.displayName != transaction.finalName &&
            document.displayName != transaction.backupName &&
            document.displayName != transaction.generatedBackupName &&
            document.documentIdentity != transaction.backupDocumentIdentity
    }

    private fun stageDocument(
        transaction: AndroidSafOwnedDownloadTransaction,
        documents: List<AndroidSafPublicationDocument<Document>> = directory.documents(),
    ): AndroidSafPublicationDocument<Document>? {
        val identity = transaction.stageDocumentIdentity ?: return null
        return documents.singleOrNull { document ->
            document.documentIdentity == identity &&
                document.displayName != transaction.finalName &&
                (transaction.stageContentIdentity == null ||
                    contentIdentity(document.document) == transaction.stageContentIdentity)
        }
    }

    private fun backupDocument(
        transaction: AndroidSafOwnedDownloadTransaction,
        documents: List<AndroidSafPublicationDocument<Document>> = directory.documents(),
    ): AndroidSafPublicationDocument<Document>? {
        val identity = transaction.backupDocumentIdentity
        return if (identity == null) {
            documents.singleOrNull { document -> document.displayName == transaction.backupName }
        } else {
            documents.singleOrNull { document ->
                document.documentIdentity == identity && document.displayName != transaction.finalName
            } ?: documents.singleOrNull { document ->
                document.displayName == transaction.backupName &&
                    transaction.backupContentIdentity != null &&
                    contentIdentity(document.document) == transaction.backupContentIdentity
            } ?: documents.singleOrNull { document ->
                transaction.token in document.displayName &&
                    document.documentIdentity != transaction.stageDocumentIdentity &&
                    document.displayName != transaction.backupName &&
                    document.displayName != transaction.generatedBackupName &&
                    document.displayName != transaction.stageName &&
                    document.displayName != transaction.finalName
            }
        }
    }

    private fun originalDocumentIsFinal(
        transaction: AndroidSafOwnedDownloadTransaction,
        final: AndroidSafPublicationDocument<Document>?,
    ): Boolean = transaction.backupDocumentIdentity != null &&
        final?.documentIdentity == transaction.backupDocumentIdentity

    private fun recoverBeforePublication(
        transaction: AndroidSafOwnedDownloadTransaction,
    ): AndroidSafOwnedDownloadTransaction {
        val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
        val stage = stageDocument(transaction)
        val backup = backupDocument(transaction)
        if (backup == null && originalDocumentIsFinal(transaction, final)) {
            val recovered = clearRestoredBackupName(transaction)
            stage?.let { deleteBestEffort(it.document) }
            return recovered
        }
        if (final != null && stage == null) {
            if (backup == null) return transaction
            preserveConcurrentFinal(transaction, final)
            return restoreBackup(transaction, backup)
        }
        check(!transaction.backupProtected || backup != null) {
            "The protected local item identity is unavailable."
        }
        val recovered = if (backup != null && final == null) {
            restoreBackup(transaction, backup)
        } else {
            transaction
        }
        stageDocument(transaction)?.let {
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
            val exactRestored = documents.singleOrNull { it.displayName == transaction.finalName }
            val remainingBackup = documents.singleOrNull { it.displayName == transaction.backupName }
            val restoredIsAuthenticated = exactRestored != null && (
                exactRestored.document == backup.document ||
                    transaction.backupContentIdentity != null &&
                    contentIdentity(exactRestored.document) == transaction.backupContentIdentity
                )
            if (restoredIsAuthenticated && remainingBackup == null) {
                return clearRestoredBackupName(transaction, requireNotNull(exactRestored).documentIdentity)
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
        require(restored.document == restoredDocument && backupDocument(transaction) == null) {
            "The protected local item could not be restored."
        }
        return clearRestoredBackupName(transaction, restored.documentIdentity)
    }

    private fun clearRestoredBackupName(
        transaction: AndroidSafOwnedDownloadTransaction,
        restoredDocumentIdentity: String? = null,
    ): AndroidSafOwnedDownloadTransaction {
        if (
            transaction.backupDisplayName == null &&
            !transaction.backupProtected &&
            restoredDocumentIdentity == null
        ) return transaction
        val restored = transaction.copy(
            backupDisplayName = null,
            backupProtected = false,
            backupDocumentIdentity = restoredDocumentIdentity ?: transaction.backupDocumentIdentity,
        )
        ownership.replace(restored)
        return restored
    }

    private fun isPublished(
        transaction: AndroidSafOwnedDownloadTransaction,
        stage: Document,
        renamedStage: Document? = null,
    ): Boolean {
        val final = directory.documents().singleOrNull { document ->
            document.displayName == transaction.finalName
        }
        return stageDocument(transaction) == null &&
            (final?.document == stage || final?.document == renamedStage || authenticatedPublishedFinal(transaction, final))
    }

    private fun authenticatedPublishedFinal(
        transaction: AndroidSafOwnedDownloadTransaction,
        final: AndroidSafPublicationDocument<Document>?,
    ): Boolean {
        if (final == null) return false
        if (final.documentIdentity == transaction.stageDocumentIdentity) return true
        val expectedContentIdentity = transaction.stageContentIdentity ?: return false
        if (contentIdentity(final.document) != expectedContentIdentity) return false
        val confirmed = directory.documents().singleOrNull { document ->
            document.displayName == transaction.finalName && document.documentIdentity == final.documentIdentity
        } ?: return false
        return contentIdentity(confirmed.document) == expectedContentIdentity
    }

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

    private fun cleanupPublishedBackup(
        transaction: AndroidSafOwnedDownloadTransaction,
        backup: AndroidSafPublicationDocument<Document>,
    ): Boolean {
        val expectedIdentity = transaction.backupContentIdentity
            ?: return preserveChangedBackup(transaction, backup)
        val currentIdentity = contentIdentity(backup.document) ?: return false
        if (currentIdentity == expectedIdentity) {
            if (contentIdentity(backup.document) != expectedIdentity) {
                return preserveChangedBackup(transaction, backup)
            }
            deleteBestEffort(backup.document)
            return false
        }
        return preserveChangedBackup(transaction, backup)
    }

    private fun preserveChangedBackup(
        transaction: AndroidSafOwnedDownloadTransaction,
        backup: AndroidSafPublicationDocument<Document>,
    ): Boolean {
        val preservedDocument = requireNotNull(directory.rename(backup.document, transaction.changedBackupName)) {
            "The changed local backup could not be preserved as a conflict."
        }
        val preserved = requireNotNull(
            directory.documents().singleOrNull { document -> document.document == preservedDocument },
        ) { "The changed local backup could not be resolved after preservation." }
        require(
            preserved.displayName != transaction.finalName &&
                preserved.displayName != transaction.stageName &&
                preserved.displayName != transaction.backupName &&
                preserved.displayName != transaction.generatedBackupName &&
                transaction.token !in preserved.displayName
        ) { "The changed local backup was not preserved under a safe name." }
        ownership.remove(transaction)
        return true
    }

    private fun preserveConcurrentFinal(
        transaction: AndroidSafOwnedDownloadTransaction,
        final: AndroidSafPublicationDocument<Document>,
    ) {
        val preservedDocument = requireNotNull(directory.rename(final.document, transaction.changedBackupName)) {
            "The concurrent local item could not be preserved as a conflict."
        }
        val preserved = requireNotNull(
            directory.documents().singleOrNull { document -> document.document == preservedDocument },
        ) { "The concurrent local item could not be resolved after preservation." }
        require(
            preserved.displayName != transaction.finalName &&
                preserved.displayName != transaction.stageName &&
                preserved.displayName != transaction.backupName &&
                preserved.displayName != transaction.generatedBackupName &&
                transaction.token !in preserved.displayName
        ) { "The concurrent local item was not preserved under a safe name." }
        require(documentNamed(transaction.finalName) == null) {
            "The concurrent local item still occupies the destination name."
        }
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

    private fun deleteMisnamedStageOrThrow(document: Document, publicationFailure: Throwable) {
        try {
            check(directory.delete(document)) { "The provider-normalized recovery stage could not be removed." }
        } catch (failure: CancellationException) {
            failure.addSuppressed(publicationFailure)
            throw failure
        } catch (failure: Throwable) {
            publicationFailure.addSuppressed(failure)
            throw publicationFailure
        }
    }

    private fun retireRecoveredOwnership(
        transaction: AndroidSafOwnedDownloadTransaction,
        publicationFailureAccountedFor: Boolean = false,
    ) {
        val final = directory.documents().singleOrNull { it.displayName == transaction.finalName }
        val publicationAccountedFor = publicationFailureAccountedFor ||
            !transaction.publicationAttempted ||
            transaction.publicationCompleted ||
            originalDocumentIsFinal(transaction, final)
        if (
            publicationAccountedFor &&
            stageDocument(transaction) == null &&
            documentNamed(transaction.stageName) == null &&
            recoveryDocuments(transaction).isEmpty() &&
            backupDocument(transaction) == null &&
            (!transaction.backupProtected || transaction.publicationCompleted)
        ) {
            ownership.remove(transaction)
        }
    }

    private fun retireRecoveredOwnershipBestEffort(
        transaction: AndroidSafOwnedDownloadTransaction,
        publicationFailureAccountedFor: Boolean = false,
    ) {
        try {
            retireRecoveredOwnership(transaction, publicationFailureAccountedFor)
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
    val stageDocumentIdentity: String? = null,
    val backupContentIdentity: String? = null,
    val stageContentIdentity: String? = null,
) {
    val stageName: String = ".nextcloud-native-download-$token"
    val generatedBackupName: String = ".nextcloud-native-backup-$token"
    val backupName: String = backupDisplayName ?: generatedBackupName
    val changedBackupName: String = "Nextcloud recovered local ${token.take(8)}"

    init {
        require(finalName.isNotBlank() && '/' !in finalName && finalName.none(Char::isISOControl))
        require(token == requireValidToken(token))
        require(!publicationCompleted || publicationAttempted)
        require(backupDisplayName == null || backupProtected)
        require(backupDocumentIdentity == null || backupDocumentIdentity.isNotBlank())
        require(stageDocumentIdentity == null || stageDocumentIdentity.isNotBlank())
        require(backupContentIdentity == null || backupContentIdentity.isNotBlank())
        require(stageContentIdentity == null || stageContentIdentity.isNotBlank())
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
