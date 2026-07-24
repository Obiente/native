package dev.obiente.nextcloudnative

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import dev.obiente.nextcloudnative.app.DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Storage Access Framework bridge for the currently authenticated account.
 *
 * Reads use the same bounded WebDAV download path as the app. Writes are staged in app-private
 * storage and committed with ETag preconditions only when the caller closes the descriptor.
 */
class NextcloudDocumentsProvider : DocumentsProvider() {
    private lateinit var services: AndroidNextcloudServices
    private lateinit var offline: AndroidFileOfflineRepository
    private lateinit var webDav: NextcloudDocumentWebDav

    @Volatile
    private var cachedAccount: ResolvedAccount? = null

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        services = AndroidNextcloudServices(providerContext)
        offline = AndroidFileOfflineRepository(providerContext)
        webDav = NextcloudDocumentWebDav(
            cloudMutationsAllowed = providerContext.cloudMutationGate(),
        )
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns)
        val session = services.loadSession() ?: return cursor
        val host = runCatching { URI(session.serverUrl).host }.getOrNull().orEmpty()
        cursor.addNamedRow(
            mapOf(
                DocumentsContract.Root.COLUMN_ROOT_ID to NextcloudDocumentIds.accountKey(session),
                DocumentsContract.Root.COLUMN_DOCUMENT_ID to NextcloudDocumentIds.rootId(session),
                DocumentsContract.Root.COLUMN_TITLE to context?.getString(R.string.documents_provider_root_name),
                DocumentsContract.Root.COLUMN_SUMMARY to buildString {
                    append(session.loginName)
                    if (host.isNotBlank()) append(" on ").append(host)
                },
                DocumentsContract.Root.COLUMN_FLAGS to (
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                        DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                        if (context?.isReadOnlyTestMode() == true) {
                            0
                        } else {
                            DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        }
                    ),
                DocumentsContract.Root.COLUMN_ICON to R.mipmap.ic_launcher,
                DocumentsContract.Root.COLUMN_MIME_TYPES to "*/*",
            ),
        )
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        val session = requireSession()
        val reference = requireReference(documentId, session)
        if (reference.isRoot) {
            cursor.addDocumentRow(session, null)
            return cursor
        }

        cursor.addDocumentRow(session, findDocumentWithOfflineFallback(session, reference.path))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        val session = requireSession()
        val parent = requireReference(parentDocumentId, session)
        val children = runCatching {
            val account = resolveAccount(session)
            runBlocking(Dispatchers.IO) { services.listFiles(session, account.userId, parent.path) }
        }.getOrElse { failure ->
            val cachedChildren = offline.availableChildren(session, parent.path)
            if (cachedChildren.isNotEmpty() || offline.isStoredDirectory(session, parent.path)) {
                cachedChildren
            } else {
                throw FileNotFoundException("Could not load this Nextcloud folder.").also {
                    it.initCause(failure)
                }
            }
        }
        children.forEach { cursor.addDocumentRow(session, it) }
        return cursor
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        val session = requireSession()
        require(rootId == NextcloudDocumentIds.accountKey(session)) {
            "The document root belongs to another account."
        }
        val account = resolveAccount(session)
        val result = providerCall("Could not search this Nextcloud account.") {
            webDav.searchFiles(session, account.userId, query)
        }
        result.files.forEach { cursor.addDocumentRow(session, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val session = services.loadSession() ?: return false
        val parent = runCatching { NextcloudDocumentIds.requireForSession(parentDocumentId, session) }.getOrNull()
            ?: return false
        val child = runCatching { NextcloudDocumentIds.requireForSession(documentId, session) }.getOrNull()
            ?: return false
        if (child.isRoot || parent.path == child.path) return false
        return parent.isRoot || child.path.startsWith(parent.path + "/")
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode !in SUPPORTED_OPEN_MODES) {
            throw SecurityException("Unsupported document mode: $mode")
        }
        signal?.throwIfCanceled()

        val session = requireSession()
        val reference = requireReference(documentId, session)
        if (reference.isRoot) throw FileNotFoundException("Folders cannot be opened as files.")
        if (mode == "r") {
            offline.availableContent(session, reference.path)?.let { cached ->
                signal?.throwIfCanceled()
                return ParcelFileDescriptor.open(cached.content, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val account = resolveAccount(session)
        val file = findDocument(session, account, reference.path)
        if (file.isDirectory) throw FileNotFoundException("Folders cannot be opened as files.")
        if ((file.size ?: 0L) > DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES) {
            throw FileNotFoundException("This file is larger than the current provider limit.")
        }

        if (mode != "r") return openWritableDocument(session, account, file, mode, signal)

        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        READ_EXECUTOR.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    webDav.readFile(
                        session = session,
                        userId = account.userId,
                        path = reference.path,
                        destination = output,
                        maximumBytes = DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES,
                        cancellation = signal.asDocumentCancellation(),
                    )
                }
            } catch (cancelled: OperationCanceledException) {
                runCatching { writeSide.closeWithError("Read cancelled") }
            } catch (failure: Throwable) {
                Log.w(LOG_TAG, "System document read failed", failure)
                runCatching { writeSide.closeWithError("Could not read the Nextcloud document") }
            }
        }
        return readSide
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val session = requireSession()
        val parent = requireReference(parentDocumentId, session)
        val account = resolveAccount(session)
        requireDirectory(session, account, parent)
        val safeName = requireSafeDisplayName(displayName)
        val path = childPath(parent.path, safeName)
        mutationCall {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                webDav.createFolder(session, account.userId, path)
            } else {
                val empty = createLocalStagingFile()
                try {
                    webDav.createFile(session, account.userId, path, empty)
                } finally {
                    empty.delete()
                }
            }
        }
        notifyDocumentChanged(session, path)
        return NextcloudDocumentIds.documentId(session, path)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val session = requireSession()
        val reference = requireReference(documentId, session)
        if (reference.isRoot) throw SecurityException("The Nextcloud root cannot be renamed.")
        val account = resolveAccount(session)
        val file = findDocument(session, account, reference.path)
        val destination = childPath(NextcloudDocumentIds.parentPath(reference.path), requireSafeDisplayName(displayName))
        if (destination == reference.path) return documentId
        val etag = requireMutationEtag(file)
        mutationCall { webDav.move(session, account.userId, reference.path, destination, etag) }
        notifyMove(session, reference.path, destination)
        return NextcloudDocumentIds.documentId(session, destination)
    }

    override fun deleteDocument(documentId: String) {
        val session = requireSession()
        val reference = requireReference(documentId, session)
        if (reference.isRoot) throw SecurityException("The Nextcloud root cannot be deleted.")
        val account = resolveAccount(session)
        val file = findDocument(session, account, reference.path)
        mutationCall {
            webDav.delete(
                session,
                account.userId,
                reference.path,
                requireMutationEtag(file),
                isDirectory = file.isDirectory,
            )
        }
        notifyDocumentChanged(session, reference.path)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val session = requireSession()
        val source = requireReference(sourceDocumentId, session)
        val sourceParent = requireReference(sourceParentDocumentId, session)
        val targetParent = requireReference(targetParentDocumentId, session)
        if (source.isRoot) throw SecurityException("The Nextcloud root cannot be moved.")
        require(NextcloudDocumentIds.parentPath(source.path) == sourceParent.path) {
            "The supplied source parent does not contain this document."
        }
        val account = resolveAccount(session)
        requireDirectory(session, account, targetParent)
        val file = findDocument(session, account, source.path)
        val destination = childPath(targetParent.path, file.name)
        if (destination == source.path) return sourceDocumentId
        mutationCall {
            webDav.move(session, account.userId, source.path, destination, requireMutationEtag(file))
        }
        notifyMove(session, source.path, destination)
        return NextcloudDocumentIds.documentId(session, destination)
    }

    private fun openWritableDocument(
        session: NextcloudSession,
        account: ResolvedAccount,
        file: NextcloudFile,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val expectedEtag = requireMutationEtag(file)
        val staging = createLocalStagingFile()
        try {
            if (mode !in TRUNCATING_OPEN_MODES) {
                staging.outputStream().use { output ->
                    webDav.readFile(
                        session = session,
                        userId = account.userId,
                        path = file.path,
                        destination = output,
                        maximumBytes = DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES,
                        cancellation = signal.asDocumentCancellation(),
                    )
                }
            }
            signal?.throwIfCanceled()
            return ParcelFileDescriptor.open(
                staging,
                descriptorMode(mode),
                WRITE_HANDLER,
            ) { closeError ->
                if (closeError != null) {
                    retainFailedStaging(staging, file.name, closeError)
                    return@open
                }
                try {
                    check(staging.length() <= DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES) {
                        "The edited file exceeds the ${formatByteLimit(DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES)} limit."
                    }
                    webDav.replaceFileAtomically(
                        session = session,
                        userId = account.userId,
                        path = file.path,
                        source = staging,
                        expectedEtag = expectedEtag,
                    )
                    staging.delete()
                    notifyDocumentChanged(session, file.path)
                } catch (failure: Throwable) {
                    retainFailedStaging(staging, file.name, failure)
                }
            }
        } catch (failure: Throwable) {
            staging.delete()
            throw failure
        }
    }

    private fun descriptorMode(mode: String): Int = when (mode) {
        "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY
        "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
        "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_APPEND
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
        "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
        else -> error("Unsupported writable mode: $mode")
    }

    private fun createLocalStagingFile(): File {
        val providerContext = requireNotNull(context) { "Provider context is unavailable." }
        val directory = File(providerContext.cacheDir, STAGING_DIRECTORY).apply { mkdirs() }
        check(directory.isDirectory) { "Could not prepare local document staging." }
        return File.createTempFile("document-", ".stage", directory)
    }

    private fun retainFailedStaging(staging: File, displayName: String, failure: Throwable) {
        val providerContext = context
        val recovery = providerContext?.let { File(it.filesDir, RECOVERY_DIRECTORY).apply { mkdirs() } }
        val safeDisplayName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "document" }
        val retained = recovery?.takeIf(File::isDirectory)?.let { directory ->
            File(directory, "${System.currentTimeMillis()}-$safeDisplayName.stage")
        }
        val wasRetained = retained != null && staging.renameTo(retained)
        if (!wasRetained) staging.delete()
        Log.e(
            LOG_TAG,
            if (wasRetained) "Document commit failed; local staged content was retained."
            else "Document commit failed and local staging could not be retained.",
            failure,
        )
    }

    private fun requireDirectory(
        session: NextcloudSession,
        account: ResolvedAccount,
        reference: NextcloudDocumentReference,
    ) {
        if (reference.isRoot) return
        val parent = findDocument(session, account, reference.path)
        require(parent.isDirectory) { "The selected parent is not a folder." }
    }

    private fun requireMutationEtag(file: NextcloudFile): String = file.etag?.takeIf(String::isNotBlank)
        ?: throw IllegalStateException("Nextcloud did not provide an ETag, so this document cannot be changed safely.")

    private fun requireSafeDisplayName(displayName: String): String {
        require(displayName.isNotBlank()) { "Document name cannot be empty." }
        require(displayName == displayName.trim()) { "Document name cannot start or end with whitespace." }
        require('/' !in displayName && '\u0000' !in displayName) { "Document name contains an invalid character." }
        require(displayName != "." && displayName != "..") { "Relative document names are not allowed." }
        return displayName
    }

    private fun childPath(parentPath: String, displayName: String): String =
        if (parentPath.isBlank()) displayName else "$parentPath/$displayName"

    private inline fun <T> mutationCall(operation: () -> T): T = try {
        operation()
    } catch (failure: DocumentWebDavException) {
        when (failure.error) {
            DocumentWebDavError.Authentication,
            DocumentWebDavError.Permission,
            -> throw SecurityException(failure.message, failure)
            DocumentWebDavError.NotFound -> throw FileNotFoundException(failure.message).also { it.initCause(failure) }
            DocumentWebDavError.AlreadyExists,
            DocumentWebDavError.Conflict,
            DocumentWebDavError.Locked,
            DocumentWebDavError.InsufficientStorage,
            DocumentWebDavError.TooLarge,
            DocumentWebDavError.Server,
            -> throw IllegalStateException(failure.message, failure)
        }
    }

    private fun notifyMove(session: NextcloudSession, sourcePath: String, destinationPath: String) {
        notifyDocumentChanged(session, sourcePath)
        notifyDocumentChanged(session, destinationPath)
    }

    private fun notifyDocumentChanged(session: NextcloudSession, path: String) {
        val resolver = context?.contentResolver ?: return
        resolver.notifyChange(
            DocumentsContract.buildDocumentUri(
                NEXTCLOUD_DOCUMENTS_AUTHORITY,
                NextcloudDocumentIds.documentId(session, path),
            ),
            null,
        )
        resolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(
                NEXTCLOUD_DOCUMENTS_AUTHORITY,
                NextcloudDocumentIds.documentId(session, NextcloudDocumentIds.parentPath(path)),
            ),
            null,
        )
    }

    private fun formatByteLimit(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"

    private fun MatrixCursor.addDocumentRow(session: NextcloudSession, file: NextcloudFile?) {
        val isDirectory = file?.isDirectory ?: true
        val path = file?.path.orEmpty()
        val displayName = file?.name ?: context?.getString(R.string.documents_provider_root_name).orEmpty()
        addNamedRow(
            mapOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to NextcloudDocumentIds.documentId(session, path),
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to displayName,
                DocumentsContract.Document.COLUMN_MIME_TYPE to when {
                    isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
                    else -> file.mimeType ?: "application/octet-stream"
                },
                DocumentsContract.Document.COLUMN_FLAGS to documentFlags(file),
                DocumentsContract.Document.COLUMN_SIZE to file?.size,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file?.lastModified?.toEpochMilliseconds(),
            ),
        )
    }

    private fun documentFlags(file: NextcloudFile?): Int {
        if (file == null) {
            return DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        }
        var flags = if (file.isDirectory) {
            DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            0
        }
        if (!file.etag.isNullOrBlank()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_MOVE
            if (!file.isDirectory) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        return flags
    }

    private fun MatrixCursor.addNamedRow(values: Map<String, Any?>) {
        val row = newRow()
        columnNames.forEach { column -> row.add(values[column]) }
    }

    private fun requireSession(): NextcloudSession = services.loadSession()
        ?: throw FileNotFoundException("Sign in to Nextcloud Native to browse files.")

    private fun requireReference(documentId: String, session: NextcloudSession): NextcloudDocumentReference =
        providerCall("This Nextcloud document ID is no longer valid.") {
            NextcloudDocumentIds.requireForSession(documentId, session)
        }

    private fun resolveAccount(session: NextcloudSession): ResolvedAccount {
        val accountKey = NextcloudDocumentIds.accountKey(session)
        cachedAccount?.takeIf { it.accountKey == accountKey }?.let { return it }
        return providerCall("Could not resolve the signed-in Nextcloud account.") {
            val info = runBlocking(Dispatchers.IO) { services.loadServerInfo(session) }
            ResolvedAccount(accountKey, info.userId).also { cachedAccount = it }
        }
    }

    private fun findDocument(session: NextcloudSession, account: ResolvedAccount, path: String): NextcloudFile =
        providerCall("The requested Nextcloud document was not found.") {
            val parent = NextcloudDocumentIds.parentPath(path)
            runBlocking(Dispatchers.IO) {
                services.listFiles(session, account.userId, parent)
            }.firstOrNull { it.path == path }
                ?: throw FileNotFoundException("The requested Nextcloud document was not found.")
        }

    private fun findDocumentWithOfflineFallback(session: NextcloudSession, path: String): NextcloudFile {
        val cached = offline.availableEntry(session, path)
        return runCatching { findDocument(session, resolveAccount(session), path) }
            .getOrElse { failure ->
                cached ?: throw FileNotFoundException("The requested Nextcloud document was not found.").also {
                    it.initCause(failure)
                }
            }
    }

    private inline fun <T> providerCall(message: String, operation: () -> T): T = try {
        operation()
    } catch (failure: FileNotFoundException) {
        throw failure
    } catch (failure: Throwable) {
        Log.w(LOG_TAG, message, failure)
        throw FileNotFoundException(message).also { it.initCause(failure) }
    }

    private fun String.toEpochMilliseconds(): Long? = runCatching {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private fun CancellationSignal?.asDocumentCancellation(): DocumentRequestCancellation {
        val platformSignal = this ?: return NoDocumentRequestCancellation
        return object : DocumentRequestCancellation {
            override fun throwIfCancelled() = platformSignal.throwIfCanceled()

            override fun setOnCancelAction(action: (() -> Unit)?) {
                platformSignal.setOnCancelListener(action?.let { callback ->
                    CancellationSignal.OnCancelListener { callback() }
                })
            }
        }
    }

    private data class ResolvedAccount(val accountKey: String, val userId: String)

    private companion object {
        const val LOG_TAG = "NextcloudDocuments"
        const val STAGING_DIRECTORY = "documents-staging"
        const val RECOVERY_DIRECTORY = "documents-recovery"
        val SUPPORTED_OPEN_MODES = setOf("r", "w", "wt", "wa", "rw", "rwt")
        val TRUNCATING_OPEN_MODES = setOf("wt", "rwt")
        val WRITE_THREAD = HandlerThread("nextcloud-document-commit").apply { start() }
        val WRITE_HANDLER = Handler(WRITE_THREAD.looper)
        val READ_EXECUTOR = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "nextcloud-document-read").apply { isDaemon = true }
        }
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
