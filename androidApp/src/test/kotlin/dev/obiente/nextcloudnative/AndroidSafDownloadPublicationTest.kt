package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidSafDownloadPublicationTest {
    @Test
    fun `existing directories require authentication before reuse`() {
        var authenticated = false

        assertTrue(
            authenticateExistingAndroidSafDirectory(SyncEntryKind.Directory) {
                authenticated = true
            },
        )
        assertTrue(authenticated)
        assertFailsWith<IllegalArgumentException> {
            authenticateExistingAndroidSafDirectory(SyncEntryKind.Directory) {
                require(false) { "stale directory evidence" }
            }
        }
    }

    @Test
    fun `cancelled directory creation never reaches the provider`() {
        var providerCalled = false

        assertFailsWith<CancellationException> {
            createAndroidSafDirectoryAfterCancellationCheck(
                shouldContinue = { false },
                create = {
                    providerCalled = true
                    1
                },
            )
        }

        assertFalse(providerCalled)
    }

    @Test
    fun `recovery names stay bounded independently of the final filename`() {
        val finalName = "a".repeat(1_024)
        val transaction = AndroidSafOwnedDownloadTransaction(finalName, TOKEN)

        assertTrue(transaction.stageName.length < 80)
        assertTrue(transaction.backupName.length < 80)
        assertTrue(finalName !in transaction.stageName)
        assertTrue(finalName !in transaction.backupName)
    }

    @Test
    fun `replacement scope uses direct file lookup and bounded directory ranges`() {
        val paths = listOf("Archive", "Archive/a", "Archive/nested/b", "Archive-2/c", "Other").sorted()

        assertEquals(
            listOf("Archive/nested/b"),
            androidSafReplacementScopedPaths(paths, "Archive/nested/b", SyncEntryKind.File),
        )
        assertEquals(
            listOf("Archive", "Archive/a", "Archive/nested/b"),
            androidSafReplacementScopedPaths(paths, "Archive", SyncEntryKind.Directory),
        )
    }

    @Test
    fun `scan-time content identity rejects a same-size edit with unchanged SAF metadata`() {
        val expected = LocalSyncEntry(
            relativePath = "Archive.bin",
            kind = SyncEntryKind.File,
            revision = "saf-unchanged-metadata",
            size = 2L,
            contentHash = "sha256:${"0".repeat(64)}",
        )
        val edited = listOf(
            AndroidSafReplacementEvidence(
                entry = expected.copy(contentHash = null),
                documentIdentity = "content://provider/archive",
                displayName = "Archive.bin",
                contentHash = "sha256:${"1".repeat(64)}",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            requireExpectedAndroidSafReplacement(expected, edited)
        }
    }

    @Test
    fun `replacement fails closed when scan-time file content is unavailable`() {
        val scanned = LocalSyncEntry(
            relativePath = "Archive.bin",
            kind = SyncEntryKind.File,
            revision = "saf-unchanged-metadata",
            size = 2L,
        )
        val current = listOf(
            AndroidSafReplacementEvidence(
                entry = scanned,
                documentIdentity = "content://provider/archive",
                displayName = "Archive.bin",
                contentHash = "sha256:${"0".repeat(64)}",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            requireExpectedAndroidSafReplacement(scanned, current)
        }
    }

    @Test
    fun `scan-time folder identity includes descendant content`() {
        val folder = AndroidSafReplacementEvidence(
            entry = LocalSyncEntry(
                relativePath = "Archive",
                kind = SyncEntryKind.Directory,
                revision = "saf-folder-metadata",
            ),
            documentIdentity = "content://provider/archive",
            displayName = "Archive",
            contentHash = null,
        )
        val child = AndroidSafReplacementEvidence(
            entry = LocalSyncEntry(
                relativePath = "Archive/item.bin",
                kind = SyncEntryKind.File,
                revision = "saf-child-metadata",
                size = 2L,
            ),
            documentIdentity = "content://provider/archive/item",
            displayName = "item.bin",
            contentHash = "sha256:${"0".repeat(64)}",
        )

        val before = androidSafReplacementRevision(listOf(folder, child))
        val edited = listOf(folder, child.copy(contentHash = "sha256:${"1".repeat(64)}"))

        requireExpectedAndroidSafReplacement(folder.entry.copy(revision = before), listOf(folder, child))
        assertNotEquals(before, androidSafReplacementRevision(edited))
        assertFailsWith<IllegalArgumentException> {
            requireExpectedAndroidSafReplacement(folder.entry.copy(revision = before), edited)
        }
    }

    @Test
    fun `replacement hashing observes coroutine cancellation without thread interruption`() {
        var checks = 0

        assertFailsWith<CancellationException> {
            hashAndroidSafReplacementContent(
                input = ByteArrayInputStream(ByteArray(128 * 1024)),
                expectedBytes = 128L * 1024L,
                shouldContinue = { ++checks < 2 },
            )
        }

        assertEquals(2, checks)
    }

    @Test
    fun `same-size SAF metadata cannot hide replacement content changes`() {
        val weakMetadata = LocalSyncEntry(
            relativePath = "Archive",
            kind = SyncEntryKind.File,
            revision = "saf-unchanged-metadata",
            size = 2L,
            modifiedEpochMillis = null,
        )
        val expected = listOf(
            AndroidSafReplacementEvidence(
                entry = weakMetadata,
                documentIdentity = "content://provider/archive",
                displayName = "Archive",
                contentHash = "sha256:${"0".repeat(64)}",
            ),
        )
        val changed = expected.map { evidence ->
            evidence.copy(contentHash = "sha256:${"1".repeat(64)}")
        }

        assertFailsWith<IllegalArgumentException> {
            requireUnchangedAndroidSafReplacement(expected, changed)
        }
    }

    @Test
    fun `cancellation before publication preserves the mismatched directory`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")
        val publisher = publisher(directory)

        assertFailsWith<CancellationException> {
            publisher.publish("Archive", current) { output ->
                output.write(byteArrayOf(1, 2))
                throw CancellationException("worker stopped")
            }
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), publisher.visibleDocuments().map { it.displayName })
        assertEquals(0, directory.deleteCalls)
        assertEquals(1, directory.ownership.transactions().size)

        publisher.reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `write failure before publication preserves the mismatched directory`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")

        assertFailsWith<IOException> {
            publisher(directory).publish("Archive", current) {
                throw IOException("provider write failed")
            }
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `rename ambiguity recognizes the published stage and retires its backup`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")
        directory.throwAfterRenameTo = "Archive"

        publisher(directory).publish("Archive", current) { output ->
            output.write(byteArrayOf(3, 4, 5))
        }

        val published = directory.entryNamed("Archive")
        assertEquals(FakeSafKind.File, published.kind)
        assertContentEquals(byteArrayOf(3, 4, 5), published.bytes)
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `cancellation after exact publication preserves the committed generation`() {
        val cancellation = CancellationException("cancelled after exact provider rename")
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            cancelNextDocumentsAfterRenameTo["Archive"] = cancellation
        }

        val thrown = assertFailsWith<CancellationException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(5, 6))
            }
        }

        assertEquals(cancellation, thrown)
        assertContentEquals(byteArrayOf(5, 6), directory.entryNamed("Archive").bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `provider normalized publication is removed before restoring the original`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            normalizedRenameNames["Archive"] = "provider-final"
            replaceIdentityAfterRenameTo = "Archive"
        }

        assertFailsWith<IllegalArgumentException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(6, 7))
            }
        }

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `provider normalized new publication is removed and ownership retires`() {
        val directory = FakeSafDirectory().apply {
            normalizedRenameNames["Report.txt"] = "provider-report.txt"
        }

        assertFailsWith<IllegalArgumentException> {
            publisher(directory).publish("Report.txt", currentDocument = null) { output -> output.write(1) }
        }

        assertEquals(emptyList(), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `cancellation after normalized publication still restores the original`() {
        val cancellation = CancellationException("cancelled after provider rename")
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            normalizedRenameNames["Archive"] = "provider-final"
            replaceIdentityAfterRenameTo = "Archive"
            cancelNextDocumentsAfterRenameTo["Archive"] = cancellation
        }

        val thrown = assertFailsWith<CancellationException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(7, 8))
            }
        }

        assertEquals(cancellation, thrown)
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `failed normalized publication cleanup retains all recovery state`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            normalizedRenameNames["Archive"] = "provider-final"
            replaceIdentityAfterRenameTo = "Archive"
            failNextDeletionOfName = "provider-final"
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(8, 9))
            }
        }

        val transaction = directory.ownership.transactions().single()
        assertTrue(failure.suppressedExceptions.single() is IllegalStateException)
        assertEquals(setOf("provider-final", transaction.backupName), directory.names().toSet())
        assertEquals(FakeSafKind.Directory, directory.entryNamed(transaction.backupName).kind)
        assertContentEquals(byteArrayOf(8, 9), directory.entryNamed("provider-final").bytes)
        assertFailsWith<IllegalArgumentException> { publisher(directory).reconcileForSync() }
    }

    @Test
    fun `failed backup deletion stays hidden and a later instance retries cleanup`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")
        directory.failNextBackupDeletion = true
        val first = publisher(directory)

        first.publish("Archive", current) { output -> output.write(byteArrayOf(8, 9)) }

        assertEquals(listOf("Archive"), first.visibleDocuments().map { it.displayName })
        assertTrue(directory.names().any { ".nextcloud-native-backup-" in it })
        assertEquals(1, directory.ownership.transactions().size)
        assertTrue(directory.ownership.transactions().single().publicationCompleted)

        val restarted = publisher(directory)
        restarted.reconcile()

        assertEquals(listOf("Archive"), restarted.visibleDocuments().map { it.displayName })
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `concurrent final name occupancy is preserved before the backup is restored`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            replaceStageWithUnrelatedFinalBeforeRenameTo = "Archive"
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<IOException> {
            publisher(directory).publish("Archive", current) { output -> output.write(byteArrayOf(8, 9)) }
        }

        val recoveredName = AndroidSafOwnedDownloadTransaction("Archive", TOKEN).changedBackupName
        assertEquals(setOf("Archive", recoveredName), directory.names().toSet())
        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertContentEquals(byteArrayOf(21, 22), directory.entryNamed(recoveredName).bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
        assertEquals(
            setOf("Archive", recoveredName),
            publisher(directory).visibleDocuments().map { it.displayName }.toSet(),
        )
    }

    @Test
    fun `provider-normalized restore identity remains owned across restart`() {
        val providerBackupName = "provider-backup-$TOKEN"
        val restoredBackupName = "restored-provider-backup-$TOKEN"
        val initial = AndroidSafOwnedDownloadTransaction(
            finalName = "Archive",
            token = TOKEN,
            backupDisplayName = providerBackupName,
        )
        val directory = FakeSafDirectory().apply {
            addDirectory(providerBackupName)
        }
        val transaction = directory.addOwnedStage(initial, byteArrayOf(3, 4))
        directory.normalizedRenameNames[transaction.finalName] = restoredBackupName

        assertFailsWith<IllegalStateException> { publisher(directory).reconcile() }

        val relocated = directory.ownership.transactions().single()
        assertEquals(restoredBackupName, relocated.backupDisplayName)
        assertEquals(setOf(restoredBackupName, transaction.stageName), directory.names().toSet())
        assertEquals(emptyList(), publisher(directory).visibleDocuments())

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `unrelated final cannot satisfy an ambiguous backup restore`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addDirectory(initial.backupName)
        }
        val transaction = directory.addOwnedStage(initial, byteArrayOf(5, 6))
        directory.replaceBackupWithUnrelatedFinalBeforeRenameTo = transaction.finalName

        assertFailsWith<IOException> { publisher(directory).reconcile() }
        assertFailsWith<IllegalArgumentException> { publisher(directory).reconcileForSync() }

        assertContentEquals(byteArrayOf(31, 32), directory.entryNamed("Archive").bytes)
        assertEquals(setOf("Archive", transaction.stageName), directory.names().toSet())
        assertEquals(listOf(transaction.copy(backupProtected = true)), directory.ownership.transactions())
    }

    @Test
    fun `lost protected backup keeps the completed stage for manual recovery`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            removeBackupBeforeStageRenameFailureTo = "Archive"
        }

        assertFailsWith<IOException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(41, 42))
            }
        }

        val transaction = directory.ownership.transactions().single()
        assertTrue(transaction.backupProtected)
        assertEquals(listOf(transaction.stageName), directory.names())
        assertFailsWith<IllegalArgumentException> { publisher(directory).reconcileForSync() }
    }

    @Test
    fun `restart after publication rename never restores an attempted backup`() {
        val transaction = AndroidSafOwnedDownloadTransaction(
            finalName = "Archive",
            token = TOKEN,
            publicationAttempted = true,
        )
        val directory = FakeSafDirectory().apply {
            ownership.add(transaction)
            addDirectory(transaction.backupName)
            addFile("Archive", byteArrayOf(23, 24))
        }
        val restarted = publisher(directory)

        restarted.reconcile()
        directory.delete(directory.documentNamed("Archive"))
        restarted.reconcile()

        assertEquals(listOf(transaction.backupName), directory.names())
        assertEquals(listOf(transaction.copy(backupProtected = true)), directory.ownership.transactions())
    }

    @Test
    fun `unavailable provider listing retains physical and durable recovery state`() {
        val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            ownership.add(transaction)
            addDirectory(transaction.backupName)
            documentsFailure = IOException("provider unavailable")
        }

        assertFailsWith<IOException> { publisher(directory).reconcile() }

        assertEquals(listOf(transaction.backupName), directory.names())
        assertEquals(listOf(transaction), directory.ownership.transactions())
        directory.documentsFailure = null
        publisher(directory).reconcile()
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `visible filtering retains a same-name document whose provider identity changed`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addFile("Archive", byteArrayOf(1))
        }
        directory.addOwnedStage(initial, byteArrayOf(2))
        val obsoleteDocument = directory.documentNamed("Archive")
        directory.replaceDocumentIdentity("Archive")
        val listed = directory.documents()
        directory.documentsFailure = IOException("A second provider listing is unavailable")

        val visible = publisher(directory).visibleDocuments(listed)

        assertEquals(listOf("Archive"), visible.map { it.displayName })
        assertTrue(visible.single().document != obsoleteDocument)
        assertEquals(listed.single { it.displayName == "Archive" }.document, visible.single().document)
    }

    @Test
    fun `cancellation raised by failed-backup recovery remains cancellation`() {
        val cancellation = CancellationException("worker stopped during recovery")
        val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            failBeforeRenameTo = transaction.backupName
            cancelNextDocumentsAfterRenameFailure = cancellation
        }
        val originalDocument = directory.documentNamed("Archive")

        val thrown = assertFailsWith<CancellationException> {
            publisher(directory).publish("Archive", originalDocument) { output ->
                output.write(byteArrayOf(3, 4))
            }
        }

        assertEquals(cancellation, thrown)
        assertTrue(thrown.suppressedExceptions.single() is IOException)
        assertEquals(setOf("Archive", transaction.stageName), directory.names().toSet())
        assertEquals(
            listOf(
                transaction.copy(
                    backupProtected = true,
                    backupDocumentIdentity = originalDocument.toString(),
                    stageDocumentIdentity = directory.documentNamed(transaction.stageName).toString(),
                    backupContentIdentity = directory.contentIdentity(originalDocument),
                    stageContentIdentity = directory.contentIdentity(directory.documentNamed(transaction.stageName)),
                ),
            ),
            directory.ownership.transactions(),
        )

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `restart restores a protected directory and hides the abandoned stage`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addDirectory(initial.backupName)
        }
        directory.addOwnedStage(initial, byteArrayOf(10, 11))
        val restarted = publisher(directory)

        assertEquals(emptyList(), restarted.visibleDocuments())
        restarted.reconcile()

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), restarted.visibleDocuments().map { it.displayName })
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `reserved-looking user file is visible and never reconciled without durable ownership`() {
        val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addFile(transaction.stageName, byteArrayOf(12, 13))
        }
        val restarted = publisher(directory)

        restarted.reconcile()

        assertEquals(listOf(transaction.stageName), directory.names())
        assertEquals(listOf(transaction.stageName), restarted.visibleDocuments().map { it.displayName })
    }

    @Test
    fun `cancelled stage defers cleanup and restart retries a provider failure`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            failNextStageDeletion = true
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<CancellationException> {
            publisher(directory).publish("Archive", current) {
                throw CancellationException("worker stopped")
            }
        }

        assertEquals(listOf("Archive"), publisher(directory).visibleDocuments().map { it.displayName })
        assertEquals(0, directory.deleteCalls)
        assertEquals(1, directory.ownership.transactions().size)

        publisher(directory).reconcile()

        assertEquals(1, directory.ownership.transactions().size)

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `revalidation failure after staging preserves the current directory`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")
        var localRevision = 1

        assertFailsWith<IllegalArgumentException> {
            publisher(directory).publish(
                finalName = "Archive",
                currentDocument = current,
                createStage = directory::createFile,
                revalidateCurrent = { require(localRevision == 1) },
                prepareStage = { localRevision = 2 },
            )
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `ownership cleanup failure after publication does not turn success ambiguous`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            ownership.failNextRemove = true
        }
        val current = directory.documentNamed("Archive")

        publisher(directory).publish("Archive", current) { output -> output.write(byteArrayOf(14, 15)) }

        assertContentEquals(byteArrayOf(14, 15), directory.entryNamed("Archive").bytes)
        assertEquals(1, directory.ownership.transactions().size)

        publisher(directory).reconcile()

        assertEquals(emptyList(), directory.ownership.transactions())
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `ownership add failure prevents creation or replacement`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            ownership.failNextAdd = true
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<IOException> {
            publisher(directory).publish("Archive", current) { output -> output.write(1) }
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `directory publication failure restores the replaced file`() {
        val directory = FakeSafDirectory().apply {
            addFile("Archive", byteArrayOf(16, 17))
            failBeforeRenameTo = "Archive"
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<IOException> {
            publisher(directory).publish(
                finalName = "Archive",
                currentDocument = current,
                createStage = directory::createDirectory,
                prepareStage = {},
            )
        }

        assertEquals(FakeSafKind.File, directory.entryNamed("Archive").kind)
        assertContentEquals(byteArrayOf(16, 17), directory.entryNamed("Archive").bytes)
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `provider normalized backup name is persisted before failed publication recovery`() {
        val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val providerBackupName = "provider-backup-$TOKEN"
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            normalizedRenameNames[transaction.backupName] = providerBackupName
            failBeforeRenameTo = "Archive"
        }

        assertFailsWith<IOException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(18, 19))
            }
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `provider normalized backup name remains owned across restart`() {
        val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val providerBackupName = "provider-backup-$TOKEN"
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            normalizedRenameNames[transaction.backupName] = providerBackupName
            failNextDeletionOfName = providerBackupName
        }
        val first = publisher(directory)

        first.publish("Archive", directory.documentNamed("Archive")) { output ->
            output.write(byteArrayOf(20, 21))
        }

        val persisted = directory.ownership.transactions().single()
        assertEquals(providerBackupName, persisted.backupDisplayName)
        assertEquals(listOf("Archive"), first.visibleDocuments().map { it.displayName })
        assertEquals(setOf("Archive", providerBackupName), directory.names().toSet())

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `restart finds a normalized backup after its document identity also changes`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val providerBackupName = "provider-backup-$TOKEN"
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            normalizedRenameNames[initial.backupName] = providerBackupName
            replaceIdentityAfterRenameTo = initial.backupName
            cancelAfterRenameTo = initial.backupName
        }
        val originalIdentity = directory.documentNamed("Archive").toString()

        assertFailsWith<CancellationException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(22, 23))
            }
        }

        val interrupted = directory.ownership.transactions().single()
        assertEquals(originalIdentity, interrupted.backupDocumentIdentity)
        assertNotEquals(originalIdentity, directory.documentNamed(providerBackupName).toString())
        assertEquals(setOf(providerBackupName, interrupted.stageName), directory.names().toSet())

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `restart preserves recovery when neither backup name nor document identity is discoverable`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val providerBackupName = "provider-backup"
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            normalizedRenameNames[initial.backupName] = providerBackupName
            replaceIdentityAfterRenameTo = initial.backupName
            cancelAfterRenameTo = initial.backupName
        }

        assertFailsWith<CancellationException> {
            publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
                output.write(byteArrayOf(26, 27))
            }
        }

        val interrupted = directory.ownership.transactions().single()
        publisher(directory).reconcile()

        assertEquals(setOf(providerBackupName, interrupted.stageName), directory.names().toSet())
        assertEquals(listOf(interrupted), directory.ownership.transactions())
        assertFailsWith<IllegalArgumentException> { publisher(directory).reconcileForSync() }
    }

    @Test
    fun `persisted backup identity rejects an unrelated exact-name occupant`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory()
        val original = directory.addDirectory("provider-backup-$TOKEN")
        val stage = directory.addFile(initial.stageName, byteArrayOf(30, 31))
        val transaction = initial.copy(
            backupProtected = true,
            backupDocumentIdentity = original.toString(),
            stageDocumentIdentity = stage.toString(),
        )
        directory.ownership.add(transaction)
        directory.addFile(transaction.backupName, byteArrayOf(28, 29))

        publisher(directory).reconcile()

        assertEquals(setOf("Archive", transaction.backupName), directory.names().toSet())
        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertContentEquals(byteArrayOf(28, 29), directory.entryNamed(transaction.backupName).bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `ambiguous exact restore clears protection before persisting another backup name`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory()
        val backup = directory.addDirectory(initial.backupName)
        val stage = directory.addFile(initial.stageName, byteArrayOf(24, 25))
        val transaction = initial.copy(
            backupProtected = true,
            backupDocumentIdentity = backup.toString(),
            stageDocumentIdentity = stage.toString(),
        )
        directory.ownership.add(transaction)
        directory.throwAfterRenameTo = transaction.finalName

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `exact backup rename persists its changed provider identity for restart cleanup`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            replaceIdentityAfterRenameTo = initial.backupName
            failNextBackupDeletion = true
        }
        val originalIdentity = directory.documentNamed("Archive").toString()

        publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
            output.write(byteArrayOf(32, 33))
        }

        val persisted = directory.ownership.transactions().single()
        assertNotEquals(originalIdentity, persisted.backupDocumentIdentity)
        assertEquals(directory.documentNamed(initial.backupName).toString(), persisted.backupDocumentIdentity)
        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(FakeSafKind.File, directory.entryNamed("Archive").kind)
        assertContentEquals(byteArrayOf(32, 33), directory.entryNamed("Archive").bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `file ownership survives recreation and follows its recovery token after a parent move`() {
        val root = Files.createTempDirectory("saf-download-ownership-").toFile()
        try {
            val transaction = AndroidSafOwnedDownloadTransaction(
                finalName = "Archive",
                token = TOKEN,
                backupDocumentIdentity = "content://provider/document/original",
                stageDocumentIdentity = "content://provider/document/stage",
                backupContentIdentity = "sha256:${"a".repeat(64)}",
            )
            AndroidSafDownloadOwnershipStore(root).forDirectory("content://provider/tree/root/document/one")
                .add(transaction)

            val restarted = AndroidSafDownloadOwnershipStore(root)
            assertEquals(
                listOf(transaction),
                restarted.forDirectory("content://provider/tree/root/document/one").transactions(),
            )
            val protected = transaction.copy(
                backupDisplayName = "provider-backup-$TOKEN",
                backupProtected = true,
                backupDocumentIdentity = "content://provider/document/protected",
            )
            restarted.forDirectory("content://provider/tree/root/document/one").replace(protected)
            assertEquals(
                listOf(protected),
                AndroidSafDownloadOwnershipStore(root)
                    .forDirectory("content://provider/tree/root/document/one")
                    .transactions(),
            )
            val relocatedBackup = protected.copy(backupDisplayName = "restored-backup-$TOKEN")
            restarted.forDirectory("content://provider/tree/root/document/one").replace(relocatedBackup)
            assertEquals(
                listOf(relocatedBackup),
                AndroidSafDownloadOwnershipStore(root)
                    .forDirectory("content://provider/tree/root/document/one")
                    .transactions(),
            )
            val restored = relocatedBackup.copy(backupDisplayName = null, backupProtected = false)
            restarted.forDirectory("content://provider/tree/root/document/one").replace(restored)
            val attempted = restored.copy(publicationAttempted = true)
            restarted.forDirectory("content://provider/tree/root/document/one").replace(attempted)
            assertEquals(
                listOf(attempted),
                AndroidSafDownloadOwnershipStore(root)
                    .forDirectory("content://provider/tree/root/document/one")
                    .transactions(),
            )
            assertEquals(
                emptyList(),
                restarted.forDirectory("content://provider/tree/root/document/two").transactions(),
            )
            val relocated = restarted.forDirectory("content://provider/tree/root/document/two")
            assertEquals(
                listOf(attempted),
                relocated.transactions(setOf(attempted.backupName)),
            )
            val published = attempted.copy(publicationCompleted = true)
            relocated.replace(published)
            assertEquals(listOf(published), relocated.transactions(setOf(published.backupName)))

            relocated.remove(published)
            assertEquals(
                emptyList(),
                AndroidSafDownloadOwnershipStore(root)
                    .forDirectory("content://provider/tree/root/document/one")
                    .transactions(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `malformed ownership row blocks only its encoded recovery scope`() {
        val root = Files.createTempDirectory("saf-download-ownership-malformed-").toFile()
        try {
            val first = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
            val second = AndroidSafOwnedDownloadTransaction("Photos", OTHER_TOKEN)
            val store = AndroidSafDownloadOwnershipStore(root)
            store.forDirectory("content://provider/tree/root/document/one").add(first)
            store.forDirectory("content://provider/tree/root/document/two").add(second)
            val damaged = root.listFiles().orEmpty().single { file -> "-${first.token}.row" in file.name }
            damaged.writeBytes(byteArrayOf(0x01, 0x02))

            assertFailsWith<Exception> {
                store.forDirectory("content://provider/tree/root/document/one").transactions()
            }
            assertEquals(
                listOf(second),
                store.forDirectory("content://provider/tree/root/document/two").transactions(),
            )
            assertTrue(damaged.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun publisher(directory: FakeSafDirectory) =
        AndroidSafDownloadPublisher(directory, directory.ownership, { TOKEN }, directory::contentIdentity)

    private companion object {
        const val TOKEN = "01234567-89ab-cdef-0123-456789abcdef"
        const val OTHER_TOKEN = "fedcba98-7654-3210-fedc-ba9876543210"
    }
}

internal enum class FakeSafKind { File, Directory }

internal data class FakeSafEntry(
    val document: Int,
    var displayName: String,
    val kind: FakeSafKind,
    var bytes: ByteArray = byteArrayOf(),
)

internal class FakeSafDirectory : AndroidSafPublicationDirectory<Int> {
    private val entries = linkedMapOf<Int, FakeSafEntry>()
    private var nextDocument = 1

    var throwAfterRenameTo: String? = null
    var cancelAfterRenameTo: String? = null
    var replaceIdentityAfterRenameTo: String? = null
    var failBeforeRenameTo: String? = null
    var replaceStageWithUnrelatedFinalBeforeRenameTo: String? = null
    var replaceBackupWithUnrelatedFinalBeforeRenameTo: String? = null
    var removeBackupBeforeStageRenameFailureTo: String? = null
    val normalizedRenameNames = mutableMapOf<String, String>()
    val normalizedCreateNames = mutableMapOf<String, String>()
    val cancelNextDocumentsAfterRenameTo = mutableMapOf<String, CancellationException>()
    var failNextBackupDeletion: Boolean = false
    var failNextStageDeletion: Boolean = false
    var failNextDeletionOfName: String? = null
    var documentsFailure: IOException? = null
    var cancelNextDocumentsAfterRenameFailure: CancellationException? = null
    private var documentsCancellation: CancellationException? = null
    var deleteCalls: Int = 0
    var mutateNextBackupBeforeContentIdentity: ByteArray? = null
    var mutateBackupBeforeContentIdentityCall: Pair<Int, ByteArray>? = null
    private var backupContentIdentityCalls: Int = 0
    val ownership = FakeSafDownloadOwnership()

    fun addDirectory(displayName: String): Int = add(displayName, FakeSafKind.Directory)

    fun addFile(displayName: String, bytes: ByteArray = byteArrayOf()): Int =
        add(displayName, FakeSafKind.File, bytes)

    fun addOwnedStage(
        transaction: AndroidSafOwnedDownloadTransaction,
        bytes: ByteArray,
    ): AndroidSafOwnedDownloadTransaction {
        val stage = addFile(transaction.stageName, bytes)
        return transaction.copy(stageDocumentIdentity = stage.toString()).also(ownership::add)
    }

    fun documentNamed(displayName: String): Int = entryNamed(displayName).document

    fun entryNamed(displayName: String): FakeSafEntry = entries.values.single { it.displayName == displayName }

    fun names(): List<String> = entries.values.map { it.displayName }.sorted()

    fun contentIdentity(document: Int): String {
        val entry = entries.getValue(document)
        if (".nextcloud-native-backup-" in entry.displayName) {
            backupContentIdentityCalls += 1
            mutateBackupBeforeContentIdentityCall?.takeIf { it.first == backupContentIdentityCalls }?.let {
                mutateBackupBeforeContentIdentityCall = null
                entry.bytes = it.second
            }
            mutateNextBackupBeforeContentIdentity?.let { bytes ->
                mutateNextBackupBeforeContentIdentity = null
                entry.bytes = bytes
            }
        }
        return "${entry.kind.name}:${entry.bytes.joinToString(",") { byte -> byte.toUByte().toString() }}"
    }

    fun replaceDocumentIdentity(displayName: String) {
        val previous = entryNamed(displayName)
        entries.remove(previous.document)
        add(displayName, previous.kind, previous.bytes)
    }

    override fun documents(): List<AndroidSafPublicationDocument<Int>> {
        documentsCancellation?.let { cancellation ->
            documentsCancellation = null
            throw cancellation
        }
        documentsFailure?.let { throw it }
        return entries.values.map { entry -> AndroidSafPublicationDocument(entry.document, entry.displayName) }
    }

    override fun createFile(displayName: String): Int =
        addFile(normalizedCreateNames.remove(displayName) ?: displayName)

    override fun createDirectory(displayName: String): Int =
        addDirectory(normalizedCreateNames.remove(displayName) ?: displayName)

    override fun writeFile(document: Int, write: (OutputStream) -> Unit) {
        val destination = ByteArrayOutputStream()
        write(destination)
        entries.getValue(document).bytes = destination.toByteArray()
    }

    override fun rename(document: Int, displayName: String): Int {
        if (
            removeBackupBeforeStageRenameFailureTo == displayName &&
            ".nextcloud-native-download-" in entries.getValue(document).displayName
        ) {
            removeBackupBeforeStageRenameFailureTo = null
            val backup = entries.values.single { ".nextcloud-native-backup-" in it.displayName }
            entries.remove(backup.document)
            throw IOException("protected backup disappeared before publication")
        }
        if (
            replaceBackupWithUnrelatedFinalBeforeRenameTo == displayName &&
            ".nextcloud-native-backup-" in entries.getValue(document).displayName
        ) {
            replaceBackupWithUnrelatedFinalBeforeRenameTo = null
            entries.remove(document)
            addFile(displayName, byteArrayOf(31, 32))
            throw IOException("backup disappeared during restore")
        }
        if (
            replaceStageWithUnrelatedFinalBeforeRenameTo == displayName &&
            ".nextcloud-native-download-" in entries.getValue(document).displayName
        ) {
            replaceStageWithUnrelatedFinalBeforeRenameTo = null
            entries.remove(document)
            addFile(displayName, byteArrayOf(21, 22))
            throw IOException("stage disappeared before publication")
        }
        if (failBeforeRenameTo == displayName) {
            failBeforeRenameTo = null
            documentsCancellation = cancelNextDocumentsAfterRenameFailure
            cancelNextDocumentsAfterRenameFailure = null
            throw IOException("rename failed before publication")
        }
        val actualDisplayName = normalizedRenameNames.remove(displayName) ?: displayName
        require(entries.values.none { it.document != document && it.displayName == actualDisplayName })
        entries.getValue(document).displayName = actualDisplayName
        var renamedDocument = document
        if (replaceIdentityAfterRenameTo == displayName) {
            replaceIdentityAfterRenameTo = null
            val renamed = entries.remove(document) ?: error("renamed document disappeared")
            renamedDocument = add(renamed.displayName, renamed.kind, renamed.bytes)
        }
        if (throwAfterRenameTo == displayName) {
            throwAfterRenameTo = null
            throw IOException("rename result was lost")
        }
        if (cancelAfterRenameTo == displayName) {
            cancelAfterRenameTo = null
            throw CancellationException("process stopped after rename")
        }
        cancelNextDocumentsAfterRenameTo.remove(displayName)?.let { cancellation ->
            documentsCancellation = cancellation
        }
        return renamedDocument
    }

    override fun delete(document: Int): Boolean {
        deleteCalls += 1
        val entry = entries.getValue(document)
        if (failNextDeletionOfName == entry.displayName) {
            failNextDeletionOfName = null
            return false
        }
        if (failNextBackupDeletion && ".nextcloud-native-backup-" in entry.displayName) {
            failNextBackupDeletion = false
            return false
        }
        if (failNextStageDeletion && ".nextcloud-native-download-" in entry.displayName) {
            failNextStageDeletion = false
            return false
        }
        return entries.remove(document) != null
    }

    private fun add(
        displayName: String,
        kind: FakeSafKind,
        bytes: ByteArray = byteArrayOf(),
    ): Int {
        require(entries.values.none { it.displayName == displayName })
        val document = nextDocument++
        entries[document] = FakeSafEntry(document, displayName, kind, bytes)
        return document
    }
}

internal class FakeSafDownloadOwnership : AndroidSafDownloadOwnership {
    private val records = linkedSetOf<AndroidSafOwnedDownloadTransaction>()
    var failNextAdd = false
    var failNextRemove = false

    override fun transactions(observedNames: Set<String>): List<AndroidSafOwnedDownloadTransaction> = records.toList()

    override fun add(transaction: AndroidSafOwnedDownloadTransaction) {
        if (failNextAdd) {
            failNextAdd = false
            throw IOException("ownership save failed")
        }
        check(records.add(transaction))
    }

    override fun replace(transaction: AndroidSafOwnedDownloadTransaction) {
        val previous = records.single { record -> record.token == transaction.token }
        check(records.remove(previous))
        check(records.add(transaction))
    }

    override fun remove(transaction: AndroidSafOwnedDownloadTransaction) {
        if (failNextRemove) {
            failNextRemove = false
            throw IOException("ownership cleanup failed")
        }
        check(records.remove(transaction))
    }
}
