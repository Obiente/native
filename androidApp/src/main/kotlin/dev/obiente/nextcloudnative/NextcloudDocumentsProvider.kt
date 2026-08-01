package dev.obiente.nextcloudnative

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject
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
    private lateinit var virtualFiles: AndroidVirtualFileCache
    private lateinit var webDav: NextcloudDocumentWebDav

    @Volatile
    private var cachedAccount: ResolvedAccount? = null

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        cleanupIncompleteAndroidDocumentWritebacks(providerContext)
        services = AndroidNextcloudServices(providerContext)
        offline = AndroidFileOfflineRepository(providerContext)
        virtualFiles = AndroidVirtualFileCache(providerContext)
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
        val file = runCatching { findDocument(session, account, reference.path) }
            .getOrElse { failure ->
                if (mode == "r") {
                    virtualFiles.acquire(session, reference.path)?.let { lease ->
                        signal?.throwIfCanceled()
                        return openVirtualFileLease(lease)
                    }
                }
                throw failure
            }
        if (file.isDirectory) throw FileNotFoundException("Folders cannot be opened as files.")
        if (mode != "r") return openWritableDocument(session, account, file, mode, signal)

        file.etag?.takeIf(String::isNotBlank)?.let { etag ->
            virtualFiles.acquire(session, reference.path, expectedRemoteEtag = etag)?.let { lease ->
                signal?.throwIfCanceled()
                return openVirtualFileLease(lease)
            }
        }

        return openVirtualFileProxy(session, account, file, signal)
    }

    private fun openVirtualFileProxy(
        session: NextcloudSession,
        account: ResolvedAccount,
        file: NextcloudFile,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val size = file.size ?: throw FileNotFoundException(
            "Nextcloud did not provide a file size for seekable access.",
        )
        val etag = file.etag?.takeIf(String::isNotBlank) ?: throw FileNotFoundException(
            "Nextcloud did not provide an ETag for generation-safe access.",
        )
        if (size == 0L) {
            var empty = virtualFiles.createHydrationStagingFile()
            if (runCatching { virtualFiles.publishHydration(session, file, empty) }.getOrDefault(false)) {
                virtualFiles.acquire(session, file.path, expectedRemoteEtag = etag)?.let { lease ->
                    return openVirtualFileLease(lease)
                }
            }
            if (!empty.exists()) empty = virtualFiles.createHydrationStagingFile()
            return ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY, WRITE_HANDLER) {
                empty.delete()
            }
        }
        val rangeSession = services.openFileRangeSession(
            session = session,
            userId = account.userId,
            path = file.path,
            size = size,
            expectedEtag = etag,
        )
        val staging = virtualFiles.prepareHydration(session, size)
        val callback = AndroidVirtualFileProxyCallback(
            source = rangeSession,
            staging = staging,
            publishCompleteHydration = { complete ->
                runCatching { virtualFiles.publishHydration(session, file, complete) }
                    .onFailure { failure -> Log.w(LOG_TAG, "Virtual file cache publish failed", failure) }
                    .getOrDefault(false)
            },
        )
        signal?.setOnCancelListener(callback::cancel)
        return try {
            requireNotNull(context?.getSystemService(StorageManager::class.java))
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, WRITE_HANDLER)
        } catch (failure: Throwable) {
            callback.onRelease()
            throw failure
        }
    }

    private fun openVirtualFileLease(lease: AndroidVirtualFileLease): ParcelFileDescriptor = try {
        ParcelFileDescriptor.open(
            lease.content,
            ParcelFileDescriptor.MODE_READ_ONLY,
            WRITE_HANDLER,
        ) { lease.release() }
    } catch (failure: Throwable) {
        lease.release()
        throw failure
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
        val recovered = androidDocumentPendingWriteback(context, session, file.path)
        val writeback = recovered ?: createDurableWriteback(session, file, requireMutationEtag(file))
        val expectedEtag = writeback.expectedRemoteEtag
        val staging = writeback.staging
        try {
            if (recovered == null && mode !in TRUNCATING_OPEN_MODES) {
                val remoteSize = file.size ?: throw FileNotFoundException(
                    "Nextcloud did not provide a file size for safe editable staging.",
                )
                requireAndroidDocumentWritebackCapacity(
                    remoteSize = remoteSize,
                    availableBytes = staging.parentFile?.usableSpace ?: 0L,
                )
                staging.outputStream().use { output ->
                    webDav.readFile(
                        session = session,
                        userId = account.userId,
                        path = file.path,
                        destination = output,
                        maximumBytes = MAX_ANDROID_DOCUMENT_WRITEBACK_BYTES,
                        cancellation = signal.asDocumentCancellation(),
                    )
                }
            }
            signal?.throwIfCanceled()
            if (recovered == null) writeback.markReady()
            return ParcelFileDescriptor.open(
                staging,
                descriptorMode(mode),
                WRITE_HANDLER,
            ) { closeError ->
                if (closeError != null) {
                    retainFailedWriteback(writeback, closeError)
                    return@open
                }
                try {
                    check(staging.length() <= MAX_ANDROID_DOCUMENT_WRITEBACK_BYTES) {
                        "The edited file exceeds the ${formatByteLimit(MAX_ANDROID_DOCUMENT_WRITEBACK_BYTES)} limit."
                    }
                    webDav.replaceFileAtomically(
                        session = session,
                        userId = account.userId,
                        path = file.path,
                        source = staging,
                        expectedEtag = expectedEtag,
                    )
                    writeback.complete()
                    notifyDocumentChanged(session, file.path)
                } catch (failure: Throwable) {
                    retainFailedWriteback(writeback, failure)
                }
            }
        } catch (failure: Throwable) {
            if (recovered == null) writeback.discard()
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

    private fun createDurableWriteback(
        session: NextcloudSession,
        file: NextcloudFile,
        expectedEtag: String,
    ): AndroidDocumentPendingWriteback {
        val providerContext = requireNotNull(context) { "Provider context is unavailable." }
        val recovery = File(providerContext.filesDir, RECOVERY_DIRECTORY).apply { mkdirs() }
        check(recovery.isDirectory) { "Could not prepare document recovery storage." }
        val staging = File.createTempFile("writeback-", ".stage", recovery)
        val manifest = File(recovery, staging.name + ".json")
        try {
            val payload = JSONObject()
                .put("version", 1)
                .put("account", NextcloudDocumentIds.accountKey(session))
                .put("path", file.path)
                .put("etag", expectedEtag)
                .put("displayName", file.name)
                .put("stage", staging.name)
                .put("startedAt", System.currentTimeMillis())
                .put("ready", false)
                .toString().encodeToByteArray()
            check(payload.size <= MAX_WRITEBACK_MANIFEST_BYTES)
            val temporary = File.createTempFile("manifest-", ".tmp", recovery)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(payload)
                    output.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        manifest.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        manifest.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                temporary.delete()
            }
            return AndroidDocumentPendingWriteback(
                staging = staging,
                manifest = manifest,
                accountId = NextcloudDocumentIds.accountKey(session),
                remotePath = file.path,
                expectedRemoteEtag = expectedEtag,
            )
        } catch (failure: Throwable) {
            staging.delete()
            manifest.delete()
            throw failure
        }
    }

    private fun retainFailedWriteback(writeback: AndroidDocumentPendingWriteback, failure: Throwable) {
        val wasRetained = writeback.staging.isFile && writeback.manifest.isFile
        Log.e(
            LOG_TAG,
            if (wasRetained) "Document commit failed; local staged content and recovery metadata were retained."
            else "Document commit failed and durable recovery storage is incomplete.",
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
        runCatching { virtualFiles.invalidate(session, path) }
            .onFailure { failure -> Log.w(LOG_TAG, "Could not invalidate virtual file content", failure) }
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

    private fun formatByteLimit(bytes: Long): String = "${bytes / (1024 * 1024 * 1024)} GiB"

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
        val cached = offline.availableEntry(session, path) ?: virtualFiles.cachedEntry(session, path)
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
        const val MAX_WRITEBACK_MANIFEST_BYTES = 64 * 1024
        val SUPPORTED_OPEN_MODES = setOf("r", "w", "wt", "wa", "rw", "rwt")
        val TRUNCATING_OPEN_MODES = setOf("wt", "rwt")
        val WRITE_THREAD = HandlerThread("nextcloud-document-commit").apply { start() }
        val WRITE_HANDLER = Handler(WRITE_THREAD.looper)
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

internal const val MAX_ANDROID_DOCUMENT_WRITEBACK_BYTES = 2L * 1024L * 1024L * 1024L
internal const val MIN_ANDROID_DOCUMENT_FREE_BYTES = 512L * 1024L * 1024L

internal fun requireAndroidDocumentWritebackCapacity(remoteSize: Long, availableBytes: Long) {
    require(remoteSize >= 0L && availableBytes >= 0L)
    require(remoteSize <= MAX_ANDROID_DOCUMENT_WRITEBACK_BYTES) {
        "The file is too large for editable Android staging."
    }
    require(remoteSize <= (availableBytes - MIN_ANDROID_DOCUMENT_FREE_BYTES).coerceAtLeast(0L)) {
        "There is not enough free space to stage this edit safely."
    }
}

internal data class AndroidDocumentPendingWriteback(
    val staging: File,
    val manifest: File,
    val accountId: String,
    val remotePath: String,
    val expectedRemoteEtag: String,
) {
    init {
        require(accountId.isNotBlank())
        require(remotePath.isNotBlank() && remotePath.split('/').none { it.isEmpty() || it == "." || it == ".." })
        require(expectedRemoteEtag.isNotBlank() && '\r' !in expectedRemoteEtag && '\n' !in expectedRemoteEtag)
        require(staging.isFile && manifest.isFile)
    }

    fun markReady() {
        val payload = JSONObject(manifest.readText()).put("ready", true).toString().encodeToByteArray()
        val temporary = File.createTempFile("manifest-", ".tmp", manifest.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    manifest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun complete() {
        if (staging.delete()) manifest.delete()
    }

    fun discard() {
        manifest.delete()
        staging.delete()
    }
}

internal fun androidDocumentPendingWritebackCount(context: android.content.Context, session: NextcloudSession): Int {
    return androidDocumentPendingWritebacks(context, session).size
}

internal fun androidDocumentPendingWritebacks(
    context: android.content.Context,
    session: NextcloudSession,
): List<AndroidDocumentPendingWriteback> {
    val root = File(context.filesDir, "documents-recovery")
    if (!root.isDirectory) return emptyList()
    val account = NextcloudDocumentIds.accountKey(session)
    return root.listFiles().orEmpty().mapNotNull { manifest ->
        parseAndroidDocumentWriteback(root, manifest, account)
    }.sortedBy { writeback -> writeback.manifest.lastModified() }
}

internal fun androidDocumentPendingWriteback(
    context: android.content.Context?,
    session: NextcloudSession,
    remotePath: String,
): AndroidDocumentPendingWriteback? {
    val root = context?.let { File(it.filesDir, "documents-recovery") } ?: return null
    if (!root.isDirectory) return null
    val account = NextcloudDocumentIds.accountKey(session)
    return root.listFiles().orEmpty().asSequence()
        .mapNotNull { manifest -> parseAndroidDocumentWriteback(root, manifest, account) }
        .filter { writeback -> writeback.remotePath == remotePath }
        .maxByOrNull { writeback -> writeback.manifest.lastModified() }
}

private fun parseAndroidDocumentWriteback(
    root: File,
    manifest: File,
    expectedAccount: String?,
): AndroidDocumentPendingWriteback? = runCatching {
    require(manifest.isFile && manifest.name.endsWith(".stage.json") && manifest.length() <= 64 * 1024L)
    val data = JSONObject(manifest.readText())
    val stageName = data.getString("stage")
    require(data.getInt("version") == 1 && data.optBoolean("ready", false))
    val account = data.getString("account")
    require(expectedAccount == null || account == expectedAccount)
    require(data.getLong("startedAt") >= 0L)
    require(stageName.startsWith("writeback-") && stageName.endsWith(".stage"))
    require('/' !in stageName && '\\' !in stageName)
    require(manifest.name == "$stageName.json")
    val stage = File(root, stageName)
    require(stage.isFile)
    AndroidDocumentPendingWriteback(
        staging = stage,
        manifest = manifest,
        accountId = account,
        remotePath = data.getString("path"),
        expectedRemoteEtag = data.getString("etag"),
    )
}.getOrNull()

/** Removes writeback transactions that could not reach the close-ready state before process death. */
internal fun cleanupIncompleteAndroidDocumentWritebacks(context: android.content.Context): Int {
    val root = File(context.filesDir, "documents-recovery")
    if (!root.isDirectory) return 0
    val files = root.listFiles().orEmpty().filter(File::isFile)
    val retainedNames = files.mapNotNull { manifest ->
        parseAndroidDocumentWriteback(root, manifest, expectedAccount = null)
    }.flatMapTo(hashSetOf()) { writeback ->
        listOf(writeback.staging.name, writeback.manifest.name)
    }
    return files.count { file ->
        val owned =
            (file.name.startsWith("writeback-") && file.name.endsWith(".stage")) ||
                (file.name.startsWith("writeback-") && file.name.endsWith(".stage.json")) ||
                (file.name.startsWith("manifest-") && file.name.endsWith(".tmp"))
        owned && file.name !in retainedNames && file.delete()
    }
}
