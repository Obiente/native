package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.hashExactJvmFileSyncSlice
import dev.obiente.nextcloudnative.app.normalizeSyncSha256
import dev.obiente.nextcloudnative.app.skipExactJvmFileSyncBytes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.security.MessageDigest

/**
 * Revision-guarded adapter over one persisted Storage Access Framework tree.
 *
 * Downloads are staged as siblings. Existing content is renamed to a recovery backup before the
 * staged generation takes its name, so interruption never silently truncates the user's file.
 */
internal class AndroidSafFileSyncLocalTree(
    private val resolver: ContentResolver,
    rootId: String,
    private val downloadOwnershipStore: AndroidSafDownloadOwnershipStore,
) : AndroidFileSyncLocalTree {
    private val treeUri = Uri.parse(rootId)
    private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)

    init {
        require(rootId.startsWith("content://")) { "The local sync root is not a document-tree grant." }
        require(
            resolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            },
        ) { "Access to the selected local folder has expired. Select it again." }
    }

    override fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean,
        shouldContinue: () -> Boolean,
    ): List<AndroidLocalSyncDocument> {
        val ownershipDirectory = downloadOwnershipStore.indexed()
        indexRecoveryLocationsIfNeeded(ownershipDirectory, shouldContinue)
        val result = ArrayList<AndroidLocalSyncDocument>()
        val pending = ArrayDeque<Pair<String, Uri>>()
        pending += "" to rootUri
        while (pending.isNotEmpty()) {
            requireScanContinuation(shouldContinue)
            val (parentPath, parentUri) = pending.removeFirst()
            require(parentPath.count { it == '/' } < MAX_DEPTH) { "The local folder is nested too deeply." }
            for (document in children(parentUri, parentPath, ownershipDirectory, shouldContinue)) {
                requireScanContinuation(shouldContinue)
                if (!includes(document.entry.relativePath, document.entry.kind)) continue
                require(result.size < MAX_ENTRIES) { "The local folder contains too many entries." }
                result += document
                if (document.entry.kind == SyncEntryKind.Directory) {
                    pending += document.entry.relativePath to document.uri
                }
            }
        }
        return result.sortedBy { it.entry.relativePath }
    }

    private fun indexRecoveryLocations(
        ownershipDirectory: AndroidSafDownloadOwnershipDirectory,
        shouldContinue: () -> Boolean,
    ) {
        val pending = ArrayDeque<Pair<String, Uri>>()
        val visited = mutableSetOf<String>()
        var observedEntries = 0
        pending += "" to rootUri
        while (pending.isNotEmpty()) {
            requireScanContinuation(shouldContinue)
            val (parentPath, parentUri) = pending.removeFirst()
            require(visited.add(parentUri.toString())) { "The local folder contains a directory cycle." }
            require(parentPath.count { it == '/' } < MAX_DEPTH) { "The local folder is nested too deeply." }
            val documents = rawChildren(parentUri, parentPath)
            val observedNames = documents.mapTo(mutableSetOf(), AndroidLocalSyncDocument::displayName)
            ownershipDirectory.observeRecoveryNames(
                parentUri.toString(),
                observedNames,
            )
            val ownedRecoveryTokens = ownershipDirectory.forDirectory(parentUri.toString())
                .transactions(observedNames)
                .mapTo(mutableSetOf(), AndroidSafOwnedDownloadTransaction::token)
            observedEntries = Math.addExact(observedEntries, documents.size)
            require(observedEntries <= MAX_ENTRIES) { "The local folder contains too many entries." }
            documents.filter { document ->
                document.entry.kind == SyncEntryKind.Directory &&
                    shouldTraverseAndroidSafRecoveryDirectory(document.displayName, ownedRecoveryTokens)
            }.forEach { document -> pending += document.entry.relativePath to document.uri }
        }
    }

    private fun indexRecoveryLocationsIfNeeded(
        ownershipDirectory: AndroidSafDownloadOwnershipDirectory,
        shouldContinue: () -> Boolean,
    ) = indexAndroidSafRecoveryLocationsIfNeeded(ownershipDirectory) {
        indexRecoveryLocations(ownershipDirectory, shouldContinue)
    }

    override fun authenticateFileForReplacement(
        path: String,
        expectedLocalRevision: String,
        expectedContentHash: String?,
        shouldContinue: () -> Boolean,
    ) {
        val current = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(current.entry.kind == SyncEntryKind.File) { "The local item changed type after the sync scan." }
        authenticatedReplacementSnapshot(
            document = current,
            expectedLocalRevision = expectedLocalRevision,
            expectedContentHash = expectedContentHash,
            expectedReplacementAuthentication = null,
            shouldContinue = shouldContinue,
        )
    }

    override fun strengthenReplacementEntries(
        documents: List<AndroidLocalSyncDocument>,
        protectedPaths: Set<String>,
        contentReadBudget: AndroidFileSyncContentReadBudget,
        shouldContinue: () -> Boolean,
    ): List<AndroidLocalSyncDocument> = strengthenAndroidSafReplacementEntries(
        documents = documents,
        protectedPaths = protectedPaths,
        contentReadBudget = contentReadBudget,
        contentHash = { document -> replacementContentHash(document, shouldContinue) },
    )

    override fun reconcileOwnedDownloads(shouldContinue: () -> Boolean) {
        requireScanContinuation(shouldContinue)
        val ownershipDirectory = downloadOwnershipStore.indexed()
        indexRecoveryLocationsIfNeeded(
            ownershipDirectory = ownershipDirectory,
            shouldContinue = shouldContinue,
        )
        val pending = ArrayDeque<Pair<String, Uri>>()
        pending += "" to rootUri
        var visitedEntries = 0
        while (pending.isNotEmpty()) {
            requireScanContinuation(shouldContinue)
            val (parentPath, parentUri) = pending.removeFirst()
            require(parentPath.count { it == '/' } < MAX_DEPTH) {
                "The local recovery folder is nested too deeply."
            }
            val publisher = downloadPublisher(
                parentUri,
                parentPath,
                shouldContinue,
                ownershipDirectory,
            )
            publisher.reconcileForSync()
            requireScanContinuation(shouldContinue)
            val listedChildren = rawChildren(parentUri, parentPath)
            requireScanContinuation(shouldContinue)
            val visibleUris = publisher.visibleDocuments(
                listedChildren.map { document ->
                    AndroidSafPublicationDocument(document.uri, document.displayName)
                },
            ).mapTo(mutableSetOf()) { it.document }
            listedChildren.forEach { document ->
                requireScanContinuation(shouldContinue)
                if (document.uri !in visibleUris) return@forEach
                require(visitedEntries < MAX_ENTRIES) {
                    "The local recovery folder contains too many entries."
                }
                visitedEntries += 1
                if (document.entry.kind == SyncEntryKind.Directory) {
                    pending += document.entry.relativePath to document.uri
                }
            }
        }
        requireScanContinuation(shouldContinue)
    }

    override fun contentHash(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): String? = contentHashRead(path, expectedLocalRevision, expectedBytes, maximumBytes).contentHash

    override fun contentHashRead(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): AndroidFileSyncContentHashRead {
        val before = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(before.entry.kind == SyncEntryKind.File && before.entry.revision == expectedLocalRevision) {
            "The local file changed before content verification."
        }
        require(before.entry.size == expectedBytes) { "The local file size changed before content verification." }
        val hashRead = resolver.openInputStream(before.uri)?.use { input ->
            sha256SyncContentHashRead(input, expectedBytes, maximumBytes)
        } ?: return AndroidFileSyncContentHashRead(null, 0L)
        val after = requireNotNull(resolve(path)) { "The local file disappeared during content verification." }
        require(after.entry.revision == expectedLocalRevision && after.entry.size == expectedBytes) {
            "The local file changed during content verification."
        }
        return hashRead
    }

    override fun contentRangeHash(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        offset: Long,
        length: Int,
    ): String? {
        require(offset >= 0L && length >= 0 && offset <= expectedBytes - length)
        val before = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(before.entry.kind == SyncEntryKind.File && before.entry.revision == expectedLocalRevision)
        require(before.entry.size == expectedBytes)
        val shouldContinue = { !Thread.currentThread().isInterrupted }
        val hash = try {
            requireNotNull(resolver.openFileDescriptor(before.uri, "r")) {
                "The local file provider did not expose readable content."
            }.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.position(offset)
                    hashExactJvmFileSyncSlice(Channels.newInputStream(channel), length, shouldContinue)
                }
            }
        } catch (_: IOException) {
            requireNotNull(resolver.openInputStream(before.uri)) {
                "The local file provider did not expose readable content."
            }.use { input ->
                skipExactJvmFileSyncBytes(input, offset, shouldContinue)
                hashExactJvmFileSyncSlice(input, length, shouldContinue)
            }
        }
        val after = requireNotNull(resolve(path)) { "The local file disappeared during verification." }
        require(after.entry.revision == expectedLocalRevision && after.entry.size == expectedBytes) {
            "The local file changed during content verification."
        }
        return hash
    }

    override fun stageForUpload(
        path: String,
        destination: File,
        maximumBytes: Long,
        shouldContinue: () -> Boolean,
    ): LocalSyncEntry {
        val document = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(document.entry.kind == SyncEntryKind.File) { "Only files can be uploaded as file content." }
        require((document.entry.size ?: 0L) <= maximumBytes) { "The local file exceeds the sync size limit." }
        val stagedContentHash = requireNotNull(resolver.openInputStream(document.uri)) {
            "The local file could not be opened."
        }.use { source ->
            stageAndroidFileSyncUpload(source, destination, document.entry.size, maximumBytes, shouldContinue)
        }
        val after = requireNotNull(resolve(path)) { "The local file disappeared while it was read." }
        require(after.entry.revision == document.entry.revision && after.entry.size == document.entry.size) {
            "The local file changed while it was being prepared for upload."
        }
        return after.entry.copy(
            revision = androidStagedFileSyncRevision(stagedContentHash),
            size = destination.length(),
            contentHash = stagedContentHash,
        )
    }

    override fun createDirectory(path: String, expectedLocalRevision: String?) {
        createDirectoryForDownload(
            path = path,
            expectedLocalRevision = expectedLocalRevision,
            expectedContentHash = null,
            expectedReplacementAuthentication = null,
            shouldContinue = { !Thread.currentThread().isInterrupted },
        )
    }

    override fun createDirectoryForDownload(
        path: String,
        expectedLocalRevision: String?,
        expectedContentHash: String?,
        expectedReplacementAuthentication: String?,
        shouldContinue: () -> Boolean,
    ) {
        val existing = resolve(path)
        if (expectedLocalRevision == null) {
            require(existing == null) { "The local folder appeared after the sync scan." }
        } else {
            requireNotNull(existing) { "The local item disappeared after the sync scan." }
        }
        if (
            authenticateExistingAndroidSafDirectory(existing?.entry?.kind) {
                authenticatedReplacementSnapshot(
                    document = requireNotNull(existing),
                    expectedLocalRevision = requireNotNull(expectedLocalRevision),
                    expectedContentHash = expectedContentHash,
                    expectedReplacementAuthentication = expectedReplacementAuthentication,
                    shouldContinue = shouldContinue,
                )
            }
        ) {
            return
        }
        val replacementSnapshot = existing?.let { document ->
            authenticatedReplacementSnapshot(
                document = document,
                expectedLocalRevision = requireNotNull(expectedLocalRevision),
                expectedContentHash = expectedContentHash,
                expectedReplacementAuthentication = expectedReplacementAuthentication,
                shouldContinue = shouldContinue,
            )
        }
        val parent = ensureParent(path)
        val parentPath = path.substringBeforeLast('/', "")
        val finalName = path.substringAfterLast('/')
        if (existing == null) {
            createAndroidSafDirectoryAfterCancellationCheck(shouldContinue) {
                createDirectoryDocument(parent, finalName)
            }
        } else {
            val directory = publicationDirectory(parent, parentPath)
            downloadPublisher(parent, parentPath, shouldContinue).publish(
                finalName = finalName,
                currentDocument = existing.uri,
                backupContentIdentity = requireNotNull(replacementSnapshot).let(
                    ::androidSafReplacementContentIdentity,
                ),
                createStage = directory::createDirectory,
                revalidateCurrent = {
                    requireUnchangedReplacement(path, replacementSnapshot, shouldContinue)
                },
                prepareStage = {},
            )
        }
    }

    override fun writeFile(path: String, source: File, expectedLocalRevision: String?) {
        require(source.isFile)
        writeFileFromStream(path, expectedLocalRevision) { output ->
            FileInputStream(source).use { input -> input.copyTo(output, BUFFER_BYTES) }
        }
    }

    override fun writeFileFromStream(
        path: String,
        expectedLocalRevision: String?,
        write: (OutputStream) -> Unit,
    ) {
        writeFileFromStreamForDownload(
            path = path,
            expectedLocalRevision = expectedLocalRevision,
            expectedContentHash = null,
            expectedReplacementAuthentication = null,
            shouldContinue = { !Thread.currentThread().isInterrupted },
            write = write,
        )
    }

    override fun writeFileFromStreamForDownload(
        path: String,
        expectedLocalRevision: String?,
        expectedContentHash: String?,
        expectedReplacementAuthentication: String?,
        shouldContinue: () -> Boolean,
        write: (OutputStream) -> Unit,
    ) {
        val current = resolve(path)
        if (expectedLocalRevision == null) {
            require(current == null) { "The local file appeared after the sync scan." }
        } else {
            requireNotNull(current) { "The local item disappeared after the sync scan." }
        }
        val replacementSnapshot = current?.let { document ->
            authenticatedReplacementSnapshot(
                document = document,
                expectedLocalRevision = requireNotNull(expectedLocalRevision),
                expectedContentHash = expectedContentHash,
                expectedReplacementAuthentication = expectedReplacementAuthentication,
                shouldContinue = shouldContinue,
            )
        }
        val parentUri = ensureParent(path)
        val parentPath = path.substringBeforeLast('/', "")
        val finalName = path.substringAfterLast('/')
        downloadPublisher(parentUri, parentPath, shouldContinue)
            .publish(
                finalName = finalName,
                currentDocument = current?.uri,
                backupContentIdentity = replacementSnapshot?.let(::androidSafReplacementContentIdentity),
                revalidateCurrent = {
                    requireUnchangedReplacement(path, replacementSnapshot, shouldContinue)
                },
                write = write,
            )
    }

    override fun delete(path: String, expectedLocalRevision: String) {
        deleteForSync(
            path = path,
            expectedLocalRevision = expectedLocalRevision,
            expectedContentHash = null,
            expectedReplacementAuthentication = null,
            shouldContinue = { !Thread.currentThread().isInterrupted },
        )
    }

    override fun deleteForSync(
        path: String,
        expectedLocalRevision: String,
        expectedContentHash: String?,
        expectedReplacementAuthentication: String?,
        shouldContinue: () -> Boolean,
    ) {
        requireDeletionContinuation(shouldContinue)
        val current = requireNotNull(resolve(path)) { "The local item was already removed." }
        val authenticatedSnapshot = authenticatedReplacementSnapshot(
            document = current,
            expectedLocalRevision = expectedLocalRevision,
            expectedContentHash = expectedContentHash,
            expectedReplacementAuthentication = expectedReplacementAuthentication,
            shouldContinue = shouldContinue,
        )
        requireDeletionContinuation(shouldContinue)
        requireUnchangedReplacement(path, authenticatedSnapshot, shouldContinue)
        requireDeletionContinuation(shouldContinue)
        val parentPath = path.substringBeforeLast('/', "")
        val parentUri = if (parentPath.isBlank()) rootUri else requireNotNull(resolve(parentPath)) {
            "The local item's parent folder disappeared before deletion."
        }.uri
        downloadPublisher(parentUri, parentPath, shouldContinue).delete(
            finalName = path.substringAfterLast('/'),
            currentDocument = current.uri,
            backupContentIdentity = androidSafReplacementContentIdentity(authenticatedSnapshot),
        )
    }

    private fun requireDeletionContinuation(shouldContinue: () -> Boolean) {
        if (!shouldContinue() || Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("The local deletion was cancelled.")
        }
    }

    override fun resolve(path: String): AndroidLocalSyncDocument? = resolve(path, ::children)

    private fun resolveRaw(path: String): AndroidLocalSyncDocument? = resolve(path, ::rawChildren)

    private fun resolve(
        path: String,
        listChildren: (parentUri: Uri, parentPath: String) -> List<AndroidLocalSyncDocument>,
    ): AndroidLocalSyncDocument? {
        if (path.isBlank()) return null
        var parentPath = ""
        var parentUri = rootUri
        val segments = path.split('/')
        require(segments.size <= MAX_DEPTH)
        segments.forEachIndexed { index, segment ->
            val match = listChildren(parentUri, parentPath).singleOrNull {
                it.displayName == segment
            } ?: return null
            if (index == segments.lastIndex) return match
            if (match.entry.kind != SyncEntryKind.Directory) return null
            parentPath = match.entry.relativePath
            parentUri = match.uri
        }
        return null
    }

    private fun replacementSnapshot(
        document: AndroidLocalSyncDocument,
        shouldContinue: () -> Boolean,
    ): List<AndroidSafReplacementEvidence> = collectAndroidSafReplacementEvidence(
        document = document,
        shouldContinue = shouldContinue,
        maximumDepth = MAX_DEPTH,
        maximumEntries = MAX_ENTRIES,
        listChildren = { parent -> rawChildren(parent.uri, parent.entry.relativePath) },
        contentHash = { file -> replacementContentHash(file, shouldContinue) },
    )

    private fun replacementContentHash(
        document: AndroidLocalSyncDocument,
        shouldContinue: () -> Boolean,
    ): String {
        return requireNotNull(resolver.openInputStream(document.uri)) {
            "The local replacement item could not be opened for verification."
        }.use { input ->
            hashAndroidSafReplacementContent(input, document.entry.size, shouldContinue)
        }
    }

    private fun requireUnchangedReplacement(
        path: String,
        expected: List<AndroidSafReplacementEvidence>?,
        shouldContinue: () -> Boolean,
    ) {
        val current = resolveRaw(path)
        val actual = current?.let { replacementSnapshot(it, shouldContinue) }
        requireUnchangedAndroidSafReplacement(expected, actual)
    }

    private fun authenticatedReplacementSnapshot(
        document: AndroidLocalSyncDocument,
        expectedLocalRevision: String,
        expectedContentHash: String?,
        expectedReplacementAuthentication: String?,
        shouldContinue: () -> Boolean,
    ): List<AndroidSafReplacementEvidence> {
        val snapshot = replacementSnapshot(document, shouldContinue)
        requireExpectedAndroidSafReplacement(
            expected = document.entry.copy(
                revision = expectedLocalRevision,
                contentHash = expectedContentHash,
                replacementAuthentication = expectedReplacementAuthentication,
            ),
            actual = snapshot,
        )
        return snapshot
    }

    private fun ensureParent(path: String): Uri {
        val parentPath = path.substringBeforeLast('/', "")
        if (parentPath.isBlank()) return rootUri
        var currentPath = ""
        var currentUri = rootUri
        parentPath.split('/').forEach { segment ->
            val nextPath = if (currentPath.isBlank()) segment else "$currentPath/$segment"
            val existing = children(currentUri, currentPath).singleOrNull { it.displayName == segment }
            currentUri = when {
                existing == null -> requireNotNull(
                    DocumentsContract.createDocument(
                        resolver,
                        currentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment,
                    ),
                ) { "A local parent folder could not be created." }
                existing.entry.kind != SyncEntryKind.Directory ->
                    error("A local parent path is not a folder.")
                else -> existing.uri
            }
            currentPath = nextPath
        }
        return currentUri
    }

    private fun children(
        parentUri: Uri,
        parentPath: String,
        ownershipDirectory: AndroidSafDownloadOwnershipDirectory = downloadOwnershipStore,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): List<AndroidLocalSyncDocument> {
        requireScanContinuation(shouldContinue)
        val publisher = downloadPublisher(
            parentUri,
            parentPath,
            shouldContinue = shouldContinue,
            ownershipDirectory = ownershipDirectory,
        )
        publisher.reconcileForSync()
        requireScanContinuation(shouldContinue)
        val listedChildren = rawChildren(parentUri, parentPath)
        val visibleUris = publisher.visibleDocuments(
            listedChildren.map { document ->
                AndroidSafPublicationDocument(document.uri, document.displayName)
            },
        ).mapTo(mutableSetOf()) { it.document }
        return listedChildren.filter { it.uri in visibleUris }
    }

    private fun downloadPublisher(
        parentUri: Uri,
        parentPath: String,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        ownershipDirectory: AndroidSafDownloadOwnershipDirectory = downloadOwnershipStore,
    ): AndroidSafDownloadPublisher<Uri> = AndroidSafDownloadPublisher(
        directory = publicationDirectory(parentUri, parentPath),
        ownership = ownershipDirectory.forDirectory(parentUri.toString()),
        contentIdentity = { document ->
            replacementContentIdentity(parentUri, parentPath, document, shouldContinue)
        },
    )

    private fun replacementContentIdentity(
        parentUri: Uri,
        parentPath: String,
        document: Uri,
        shouldContinue: () -> Boolean,
    ): String? = rawChildren(parentUri, parentPath)
        .singleOrNull { child -> child.uri == document }
        ?.let { child -> androidSafReplacementContentIdentity(replacementSnapshot(child, shouldContinue)) }

    private fun rawChildren(parentUri: Uri, parentPath: String): List<AndroidLocalSyncDocument> {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = requireNotNull(resolver.query(childrenUri, PROJECTION, null, null, null)) {
            "The local file provider could not list the selected folder."
        }
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val documentId = it.getString(0)
                    val name = it.getString(1)?.takeIf(String::isNotBlank) ?: continue
                    if (name.contains('/') || name.any(Char::isISOControl)) continue
                    val mimeType = it.getString(2).orEmpty()
                    val modified = if (it.isNull(3)) 0L else it.getLong(3)
                    val size = if (it.isNull(4)) null else it.getLong(4).coerceAtLeast(0L)
                    val path = if (parentPath.isBlank()) name else "$parentPath/$name"
                    val kind = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        SyncEntryKind.Directory
                    } else {
                        SyncEntryKind.File
                    }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    add(
                        AndroidLocalSyncDocument(
                            entry = LocalSyncEntry(
                                relativePath = path,
                                kind = kind,
                                revision = revision(documentId, mimeType, modified, size),
                                size = if (kind == SyncEntryKind.File) size else null,
                                modifiedEpochMillis = knownAndroidFileSyncModifiedEpochMillis(modified),
                            ),
                            uri = documentUri,
                            displayName = name,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun publicationDirectory(
        parentUri: Uri,
        parentPath: String,
    ): AndroidSafPublicationDirectory<Uri> = object : AndroidSafPublicationDirectory<Uri> {
        override fun documents(): List<AndroidSafPublicationDocument<Uri>> =
            rawChildren(parentUri, parentPath).map { document ->
                AndroidSafPublicationDocument(document.uri, document.displayName)
            }

        override fun createFile(displayName: String): Uri = requireNotNull(
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/octet-stream",
                displayName,
            ),
        ) { "A staged local file could not be created." }

        override fun createDirectory(displayName: String): Uri = requireNotNull(
            createDirectoryDocument(parentUri, displayName),
        ) { "A staged local folder could not be created." }

        override fun writeFile(document: Uri, write: (OutputStream) -> Unit) {
            writeDocument(document, write)
        }

        override fun rename(document: Uri, displayName: String): Uri? =
            DocumentsContract.renameDocument(resolver, document, displayName)

        override fun delete(document: Uri): Boolean = DocumentsContract.deleteDocument(resolver, document)
    }

    private fun createDirectoryDocument(parentUri: Uri, displayName: String): Uri? =
        DocumentsContract.createDocument(
            resolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName,
        )

    private fun writeDocument(uri: Uri, write: (OutputStream) -> Unit) {
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rwt")) {
            "The staged local file could not be opened."
        }
        descriptor.use {
            FileOutputStream(it.fileDescriptor).use { output ->
                write(output)
                output.fd.sync()
            }
        }
    }

    private fun revision(documentId: String, mimeType: String, modified: Long, size: Long?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$documentId\u0000$mimeType\u0000$modified\u0000${size ?: -1L}".encodeToByteArray())
        return "saf-" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_DEPTH = 64
        const val BUFFER_BYTES = 64 * 1024
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

internal inline fun indexAndroidSafRecoveryLocationsIfNeeded(
    ownershipDirectory: AndroidSafDownloadOwnershipDirectory,
    indexRecoveryLocations: () -> Unit,
) {
    if (ownershipDirectory.hasPendingTransactions()) indexRecoveryLocations()
}

internal fun shouldTraverseAndroidSafRecoveryDirectory(
    displayName: String,
    ownedRecoveryTokens: Set<String>,
): Boolean = ownedRecoveryTokens.none(displayName::contains)

internal fun requireScanContinuation(shouldContinue: () -> Boolean) {
    if (!shouldContinue() || Thread.currentThread().isInterrupted) {
        throw kotlinx.coroutines.CancellationException("The local sync scan was cancelled.")
    }
}

internal fun knownAndroidFileSyncModifiedEpochMillis(value: Long): Long? = value.takeIf { it > 0L }

internal fun sha256SyncContentHash(
    input: InputStream,
    expectedBytes: Long,
    maximumBytes: Long,
    shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
): String? = sha256SyncContentHashRead(input, expectedBytes, maximumBytes, shouldContinue).contentHash

internal fun sha256SyncContentHashRead(
    input: InputStream,
    expectedBytes: Long,
    maximumBytes: Long,
    shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
): AndroidFileSyncContentHashRead {
    require(expectedBytes >= 0L)
    require(maximumBytes > 0L)
    if (expectedBytes > maximumBytes) return AndroidFileSyncContentHashRead(null, 0L)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        if (!shouldContinue() || Thread.currentThread().isInterrupted) {
            throw kotlinx.coroutines.CancellationException("File identity verification cancelled.")
        }
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) return AndroidFileSyncContentHashRead(null, total)
        digest.update(buffer, 0, read)
    }
    if (total != expectedBytes) return AndroidFileSyncContentHashRead(null, total)
    return AndroidFileSyncContentHashRead(
        "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) },
        total,
    )
}

internal fun stageAndroidFileSyncUpload(
    input: InputStream,
    destination: File,
    expectedBytes: Long?,
    maximumBytes: Long,
    shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
): String {
    require(expectedBytes == null || expectedBytes >= 0L)
    require(maximumBytes > 0L)
    val digest = MessageDigest.getInstance("SHA-256")
    var copied = 0L
    FileOutputStream(destination).use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (!shouldContinue() || Thread.currentThread().isInterrupted) {
                throw kotlinx.coroutines.CancellationException("Sync upload staging cancelled.")
            }
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            require(copied <= maximumBytes) { "The local file exceeds the sync size limit." }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        output.fd.sync()
    }
    require(expectedBytes == null || copied == expectedBytes) {
        "The local file size changed while it was being prepared for upload."
    }
    return "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun androidStagedFileSyncRevision(contentHash: String): String {
    require(normalizeSyncSha256(contentHash) == contentHash)
    return "staged-$contentHash"
}
