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
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticFieldDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.SupportDiagnosticValuePrivacy
import dev.obiente.nextcloudnative.app.sanitizeExternalFileName
import dev.obiente.nextcloudnative.app.sanitizeExternalMimeType
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
/**
 * Storage Access Framework bridge for locally stored Nextcloud accounts.
 *
 * Reads use the same bounded WebDAV download path as the app. Writes are staged in app-private
 * storage and committed with ETag preconditions only when the caller closes the descriptor.
 */
class NextcloudDocumentsProvider : DocumentsProvider() {
    private lateinit var services: AndroidNextcloudServices
    private lateinit var offline: AndroidFileOfflineRepository
    private lateinit var virtualFiles: AndroidVirtualFileCache
    private lateinit var webDav: NextcloudDocumentWebDav
    private lateinit var documentIncarnations: AndroidDocumentProviderIncarnationStore
    private lateinit var accountResolver: NextcloudDocumentsAccountResolver
    @Volatile
    private var cachedAccount: ResolvedAccount? = null

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        cleanupIncompleteAndroidDocumentWritebacks(providerContext)
        services = AndroidNextcloudServices(providerContext)
        documentIncarnations = AndroidDocumentProviderIncarnationStore(providerContext)
        accountResolver = NextcloudDocumentsAccountResolver(
            services::listAccounts,
            services::loadSession,
            documentIncarnations::activeIncarnation,
        )
        AndroidExternalFileHandoffRegistry.bind(AndroidExternalFileHandoffStore(providerContext))
        offline = AndroidFileOfflineRepository(providerContext)
        virtualFiles = AndroidVirtualFileCache(providerContext)
        webDav = NextcloudDocumentWebDav(
            client = OkHttpClient.Builder()
                .useAndroidNextcloudCertificateTrust(providerContext)
                .build(),
            cloudMutationsAllowed = providerContext.cloudMutationGate(),
        )
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(columns)
        accountResolver.resolvableAccounts().forEach { account ->
            cursor.addNextcloudRootRow(
                session = account.session,
                incarnation = account.incarnation,
                title = context?.getString(R.string.documents_provider_root_name).orEmpty(),
                readOnly = context?.isReadOnlyTestMode() == true,
            )
        }
        return cursor
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        if (AndroidExternalFileHandoffRegistry.isHandoffDocumentId(documentId)) {
            val session = requireActiveSession()
            val handoff = AndroidExternalFileHandoffRegistry.peek(documentId, session)
                ?: throw FileNotFoundException("This external file handoff has expired.")
            cursor.addExternalHandoffRow(handoff)
            return cursor
        }
        return withDocumentRead(documentId) { session, reference ->
            cursor.addNextcloudDocumentRow(
                session,
                reference.incarnation,
                reference.takeUnless(NextcloudDocumentReference::isRoot)
                    ?.let { findDocumentWithOfflineFallback(session, it.path) },
                documentsRootTitle(),
            )
            cursor
        }
    }
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        return withDocumentRead(parentDocumentId) { session, parent ->
            val children = runCatching {
                val account = resolveAccount(session)
                runBlocking(Dispatchers.IO) {
                    services.listFilesWhileAccountLeaseHeld(session, account.userId, parent.path)
                }
            }.getOrElse { failure ->
                val cachedChildren = offline.availableChildren(session, parent.path)
                if (cachedChildren.isNotEmpty() || offline.isStoredDirectory(session, parent.path)) cachedChildren
                else throw FileNotFoundException("Could not load this Nextcloud folder.").also { it.initCause(failure) }
            }
            children.forEach { cursor.addNextcloudDocumentRow(session, parent.incarnation, it, documentsRootTitle()) }
            cursor
        }
    }
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(columns)
        return withRootRead(rootId) { session, incarnation ->
            val account = resolveAccount(session)
            val result = providerCall(
                message = "Could not search this Nextcloud account.",
                accountIdentity = NextcloudDocumentIds.accountKey(session),
            ) { webDav.searchFiles(session, account.userId, query) }
            result.files.forEach { cursor.addNextcloudDocumentRow(session, incarnation, it, documentsRootTitle()) }
            cursor
        }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val resolved = runCatching { accountResolver.requireDocument(parentDocumentId) }.getOrNull() ?: return false
        val session = resolved.session
        val parent = resolved.reference
        val child = runCatching {
            NextcloudDocumentIds.requireForSession(documentId, session, parent.incarnation)
        }.getOrNull()
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
        if (AndroidExternalFileHandoffRegistry.isHandoffDocumentId(documentId)) {
            val session = requireActiveSession()
            if (mode != "r") throw SecurityException("External file handoffs are read-only.")
            return openExternalHandoffDocument(session, documentId, signal)
        }
        val (session, reference) = requireDocument(documentId)
        if (reference.isRoot) throw FileNotFoundException("Folders cannot be opened as files.")
        val accountLease = acquireDocumentReadLease(session, reference.incarnation)
        try {
            if (mode == "r") {
                offline.availableContent(session, reference.path)?.let { cached ->
                    signal?.throwIfCanceled()
                    return openAndroidDocumentAccountLeasedContent(cached.content, accountLease, WRITE_HANDLER)
                }
            }
            val account = resolveAccount(session)
            val file = runCatching { findDocument(session, account, reference.path) }
                .getOrElse { failure ->
                    if (mode == "r") {
                        virtualFiles.acquire(session, reference.path)?.let { lease ->
                            signal?.throwIfCanceled()
                            return openAndroidDocumentVirtualFileLease(lease, accountLease, WRITE_HANDLER)
                        }
                    }
                    throw failure
                }
            if (file.isDirectory) throw FileNotFoundException("Folders cannot be opened as files.")
            if (mode != "r") return openWritableDocument(
                session, reference.incarnation, account, file, mode, signal, accountLease,
            )
            file.etag?.takeIf(String::isNotBlank)?.let { etag ->
                virtualFiles.acquire(session, reference.path, expectedRemoteEtag = etag)?.let { lease ->
                    signal?.throwIfCanceled()
                    return openAndroidDocumentVirtualFileLease(lease, accountLease, WRITE_HANDLER)
                }
            }
            return openVirtualFileProxy(session, account.userId, file, signal, accountLease)
        } catch (failure: Throwable) {
            accountLease.close()
            throw failure
        }
    }
    private fun openVirtualFileProxy(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        signal: CancellationSignal?,
        accountLease: AndroidAccountOperationLease,
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
                    return openAndroidDocumentVirtualFileLease(lease, accountLease, WRITE_HANDLER)
                }
            }
            if (!empty.exists()) empty = virtualFiles.createHydrationStagingFile()
            return ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY, WRITE_HANDLER) {
                try { virtualFiles.discardHydrationStagingFile(empty) } finally { accountLease.close() }
            }
        }
        val rangeSession = services.openFileRangeSession(
            session = session,
            userId = userId,
            path = file.path,
            size = size,
            expectedEtag = etag,
        )
        val staging = try {
            virtualFiles.prepareHydration(session, size)
        } catch (failure: Throwable) {
            rangeSession.close()
            throw failure
        }
        val callback = try {
            AndroidVirtualFileProxyCallback(
                source = rangeSession,
                staging = staging,
                publishCompleteHydration = { complete ->
                    runCatching { virtualFiles.publishHydration(session, file, complete) }
                        .onFailure { failure ->
                            Log.w(LOG_TAG, "Virtual file cache publish failed", failure)
                            recordProviderFailure(
                                operation = "documents.cache-publish",
                                failure = failure,
                                accountIdentity = NextcloudDocumentIds.accountKey(session),
                                remotePath = file.path,
                            )
                        }
                        .getOrDefault(false)
                },
                discardIncompleteHydration = virtualFiles::discardHydrationStagingFile,
                onReleased = accountLease::close,
            )
        } catch (failure: Throwable) {
            rangeSession.close()
            staging?.let(virtualFiles::discardHydrationStagingFile)
            throw failure
        }
        signal?.setOnCancelListener(callback::cancel)
        return try {
            requireNotNull(context?.getSystemService(StorageManager::class.java))
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, nextProxyHandler())
        } catch (failure: Throwable) {
            callback.onRelease()
            throw failure
        }
    }
    private fun openExternalHandoffDocument(
        session: NextcloudSession,
        documentId: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val lease = AndroidExternalFileHandoffRegistry.acquire(documentId, session)
            ?: throw FileNotFoundException("This external file handoff expired or has too many active readers.")
        val record = lease.record
        val file = record.file
        val size = file.size ?: run {
            lease.release()
            throw FileNotFoundException("The external file size is unavailable.")
        }
        val etag = file.etag?.takeIf(String::isNotBlank) ?: run {
            lease.release()
            throw FileNotFoundException("The external file version is unavailable.")
        }
        if (signal?.isCanceled == true) {
            lease.release()
            throw OperationCanceledException()
        }
        resolveLargeExternalHandoffContent(requireNotNull(context).cacheDir, record)?.let { staged ->
            return openExternalLocalContent(staged, lease, signal = signal)
        }
        offline.availableContent(session, file.path)
            ?.takeIf { cached -> cached.file.etag == etag && cached.content.length() == size }
            ?.let { cached ->
                return openExternalLocalContent(cached.content, lease, signal = signal)
            }
        virtualFiles.acquire(session, file.path, expectedRemoteEtag = etag)?.let { cached ->
            if (cached.content.length() == size) {
                return openExternalLocalContent(cached.content, lease, cached, signal)
            }
            cached.release()
        }
        if (size == 0L) {
            val empty = virtualFiles.createHydrationStagingFile()
            return try {
                openExternalLocalContent(
                    content = empty,
                    handoffLease = lease,
                    signal = signal,
                    onReleased = { virtualFiles.discardHydrationStagingFile(empty) },
                )
            } catch (failure: Throwable) {
                virtualFiles.discardHydrationStagingFile(empty)
                throw failure
            }
        }

        val account = try {
            resolveAccount(session)
        } catch (failure: Throwable) {
            lease.release()
            throw failure
        }
        val rangeSession = try {
            services.openFileRangeSession(
                session = session,
                userId = account.userId,
                path = file.path,
                size = size,
                expectedEtag = etag,
            )
        } catch (failure: Throwable) {
            lease.release()
            throw failure
        }
        val staging = try {
            virtualFiles.prepareHydration(session, size)
        } catch (failure: Throwable) {
            rangeSession.close()
            lease.release()
            throw failure
        }
        val callback = try {
            AndroidVirtualFileProxyCallback(
                source = rangeSession,
                staging = staging,
                publishCompleteHydration = { complete ->
                    runCatching { virtualFiles.publishHydration(session, file, complete) }
                        .onFailure { failure ->
                            Log.w(LOG_TAG, "External handoff cache publish failed", failure)
                            recordProviderFailure(
                                operation = "documents.handoff-cache-publish",
                                failure = failure,
                                accountIdentity = record.accountId,
                                remotePath = file.path,
                            )
                        }
                        .getOrDefault(false)
                },
                discardIncompleteHydration = virtualFiles::discardHydrationStagingFile,
                accessAllowed = lease::isValid,
                onReleased = lease::release,
            )
        } catch (failure: Throwable) {
            rangeSession.close()
            staging?.let(virtualFiles::discardHydrationStagingFile)
            lease.release()
            throw failure
        }
        lease.onRevoked(callback::cancel)
        signal?.setOnCancelListener(callback::cancel)
        if (!lease.isValid() || signal?.isCanceled == true) {
            callback.onRelease()
            throw OperationCanceledException()
        }
        return try {
            requireNotNull(context?.getSystemService(StorageManager::class.java))
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, nextProxyHandler())
        } catch (failure: Throwable) {
            callback.onRelease()
            throw failure
        }
    }
    private fun openExternalLocalContent(
        content: File,
        handoffLease: AndroidExternalFileHandoffLease,
        virtualLease: AndroidVirtualFileLease? = null,
        signal: CancellationSignal? = null,
        onReleased: () -> Unit = {},
    ): ParcelFileDescriptor {
        val callback = try {
            AndroidLocalFileProxyCallback(
                content = content,
                accessAllowed = handoffLease::isValid,
                onReleased = {
                    try {
                        onReleased()
                    } finally {
                        virtualLease?.release()
                        handoffLease.release()
                    }
                },
            )
        } catch (failure: Throwable) {
            virtualLease?.release()
            handoffLease.release()
            throw failure
        }
        handoffLease.onRevoked(callback::cancel)
        signal?.setOnCancelListener(callback::cancel)
        if (!handoffLease.isValid() || signal?.isCanceled == true) {
            callback.onRelease()
            throw OperationCanceledException()
        }
        return try {
            requireNotNull(context?.getSystemService(StorageManager::class.java))
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, nextProxyHandler())
        } catch (failure: Throwable) {
            callback.onRelease()
            throw failure
        }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        withDocumentMutation(parentDocumentId) { session, parent ->
            val account = resolveAccount(session)
            requireDirectory(session, account, parent)
            val path = childPath(parent.path, requireSafeDisplayName(displayName))
            withNoBlockingAndroidDocumentWriteback(context, session, path) {
                mutationCall {
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        webDav.createFolder(session, account.userId, path)
                    } else {
                        val empty = createLocalStagingFile()
                        try { webDav.createFile(session, account.userId, path, empty) } finally { empty.delete() }
                    }
                }
            }
            notifyDocumentChanged(session, parent.incarnation, path)
            NextcloudDocumentIds.documentId(session, parent.incarnation, path)
        }
    override fun renameDocument(documentId: String, displayName: String): String =
        withDocumentMutation(documentId) { session, reference ->
            if (reference.isRoot) throw SecurityException("The Nextcloud root cannot be renamed.")
            val account = resolveAccount(session)
            val file = findDocument(session, account, reference.path)
            val destination = childPath(
                NextcloudDocumentIds.parentPath(reference.path),
                requireSafeDisplayName(displayName),
            )
            if (destination == reference.path) return@withDocumentMutation documentId
            val etag = requireMutationEtag(file)
            withNoBlockingAndroidDocumentWriteback(context, session, reference.path, destination) {
                mutationCall { webDav.move(session, account.userId, reference.path, destination, etag) }
            }
            notifyMove(session, reference.incarnation, reference.path, destination)
            NextcloudDocumentIds.documentId(session, reference.incarnation, destination)
        }
    override fun deleteDocument(documentId: String) =
        withDocumentMutation(documentId) { session, reference ->
            if (reference.isRoot) throw SecurityException("The Nextcloud root cannot be deleted.")
            val account = resolveAccount(session)
            val file = findDocument(session, account, reference.path)
            withNoBlockingAndroidDocumentWriteback(context, session, reference.path) {
                mutationCall {
                    webDav.delete(
                        session,
                        account.userId,
                        reference.path,
                        requireMutationEtag(file),
                        isDirectory = file.isDirectory,
                    )
                }
            }
            notifyDocumentChanged(session, reference.incarnation, reference.path)
        }
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String =
        withDocumentMutation(sourceDocumentId) { session, source ->
            val sourceParent = requireReference(sourceParentDocumentId, session, source.incarnation)
            val targetParent = requireReference(targetParentDocumentId, session, source.incarnation)
            if (source.isRoot) throw SecurityException("The Nextcloud root cannot be moved.")
            require(NextcloudDocumentIds.parentPath(source.path) == sourceParent.path) {
                "The supplied source parent does not contain this document."
            }
            val account = resolveAccount(session)
            requireDirectory(session, account, targetParent)
            val file = findDocument(session, account, source.path)
            val destination = childPath(targetParent.path, file.name)
            if (destination == source.path) return@withDocumentMutation sourceDocumentId
            withNoBlockingAndroidDocumentWriteback(context, session, source.path, destination) {
                mutationCall {
                    webDav.move(session, account.userId, source.path, destination, requireMutationEtag(file))
                }
            }
            notifyMove(session, source.incarnation, source.path, destination)
            NextcloudDocumentIds.documentId(session, source.incarnation, destination)
        }
    private fun openWritableDocument(
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
        account: ResolvedAccount,
        file: NextcloudFile,
        mode: String,
        signal: CancellationSignal?,
        accountLease: AndroidAccountOperationLease,
    ): ParcelFileDescriptor {
        val recovered: AndroidDocumentPendingWriteback?
        val writeback: AndroidDocumentPendingWriteback
        var pathReserved = false
        try {
            reserveAndroidDocumentWritebackPath(session, file.path)
            pathReserved = true
            recovered = claimAndroidDocumentPendingWriteback(context, session, file.path)
            if (recovered?.conflict == true) {
                recovered.releaseActive()
                error("This retained local edit conflicts with a newer Nextcloud generation.")
            }
            writeback = recovered ?: createDurableWriteback(session, file, requireMutationEtag(file))
        } catch (failure: Throwable) {
            if (pathReserved) releaseAndroidDocumentWritebackSetup(accountLease) {
                releaseAndroidDocumentWritebackPath(session, file.path)
            } else accountLease.close()
            throw failure
        }
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
            if (recovered == null) writeback.markReadyAndActive()
            if (mode in TRUNCATING_OPEN_MODES) {
                java.io.RandomAccessFile(staging, "rw").use { random ->
                    random.setLength(0L)
                    random.fd.sync()
                }
            }
            val callback = AndroidWritableFileProxyCallback(staging) { closeError ->
                try {
                    if (closeError != null) {
                        retainFailedWriteback(writeback, closeError)
                    } else {
                        requireAndroidDocumentStagedWritebackCapacity(
                            stagedBytes = staging.length(),
                            availableBytes = staging.parentFile?.usableSpace ?: 0L,
                        )
                        webDav.replaceFileAtomically(
                            session = session,
                            userId = account.userId,
                            path = writeback.remotePath,
                            source = staging,
                            expectedEtag = expectedEtag,
                        )
                        writeback.complete()
                        notifyDocumentChanged(session, incarnation, writeback.remotePath)
                    }
                } catch (failure: Throwable) {
                    retainFailedWriteback(writeback, failure)
                } finally {
                    writeback.releaseActive()
                    accountLease.close()
                }
            }
            return try {
                requireNotNull(context?.getSystemService(StorageManager::class.java))
                    .openProxyFileDescriptor(descriptorMode(mode), callback, nextProxyHandler())
            } catch (failure: Throwable) {
                callback.abort()
                throw failure
            }
        } catch (failure: Throwable) {
            if (writeback.manifest.isFile) {
                if (recovered == null) writeback.discard() else writeback.releaseActive()
            }
            accountLease.close()
            throw failure
        }
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
        recordProviderFailure(
            operation = "documents.writeback",
            failure = failure,
            accountIdentity = writeback.accountId,
            remotePath = writeback.remotePath,
            fields = listOf(SupportDiagnosticFieldDraft("recovery_complete", wasRetained.toString())),
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

    private inline fun <T> mutationCall(operation: () -> T): T = documentMutationCall(operation)

    private fun notifyMove(
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
        sourcePath: String,
        destinationPath: String,
    ) {
        notifyDocumentChanged(session, incarnation, sourcePath)
        notifyDocumentChanged(session, incarnation, destinationPath)
    }

    private fun notifyDocumentChanged(
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
        path: String,
    ) {
        runCatching { virtualFiles.invalidate(session, path) }
            .onFailure { failure ->
                Log.w(LOG_TAG, "Could not invalidate virtual file content", failure)
                recordProviderFailure(
                    operation = "documents.cache-invalidate",
                    failure = failure,
                    accountIdentity = NextcloudDocumentIds.accountKey(session),
                    remotePath = path,
                )
            }
        val providerContext = context ?: return
        val resolver = providerContext.contentResolver
        val authority = nextcloudDocumentsAuthority(providerContext.packageName)
        resolver.notifyChange(
            DocumentsContract.buildDocumentUri(authority, NextcloudDocumentIds.documentId(session, incarnation, path)),
            null,
        )
        resolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(
                authority,
                NextcloudDocumentIds.documentId(session, incarnation, NextcloudDocumentIds.parentPath(path)),
            ),
            null,
        )
    }
    private fun MatrixCursor.addExternalHandoffRow(record: AndroidExternalFileHandoffRecord) {
        val file = record.file
        addNamedRow(
            mapOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to record.documentId,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to sanitizeExternalFileName(file.name),
                DocumentsContract.Document.COLUMN_MIME_TYPE to sanitizeExternalMimeType(file.mimeType),
                DocumentsContract.Document.COLUMN_FLAGS to 0,
                DocumentsContract.Document.COLUMN_SIZE to file.size,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified?.toEpochMilliseconds(),
            ),
        )
    }

    private fun MatrixCursor.addNamedRow(values: Map<String, Any?>) {
        val row = newRow()
        columnNames.forEach { column -> row.add(values[column]) }
    }

    private fun documentsRootTitle(): String =
        context?.getString(R.string.documents_provider_root_name).orEmpty()

    private fun requireActiveSession(): NextcloudSession = services.loadSession()
        ?: throw FileNotFoundException("Sign in to nati.ve to browse files.")

    private fun requireDocument(documentId: String): ResolvedNextcloudDocument = providerCall(
        message = "This Nextcloud document ID is no longer valid.",
        accountIdentity = runCatching { NextcloudDocumentIds.parse(documentId).accountKey }.getOrNull(),
    ) {
        accountResolver.requireDocument(documentId)
    }

    private fun requireRoot(rootId: String): ResolvedNextcloudDocumentsAccount = providerCall(
        message = "This Nextcloud document root is no longer valid.",
        accountIdentity = runCatching { NextcloudDocumentIds.parseProviderRootId(rootId).accountKey }.getOrNull(),
    ) {
        accountResolver.requireRoot(rootId)
    }

    private inline fun <Result> withDocumentRead(
        documentId: String,
        action: (NextcloudSession, NextcloudDocumentReference) -> Result,
    ): Result {
        val resolved = requireDocument(documentId)
        return withAndroidDocumentProviderReadAccess(
            resolved.session, resolved.reference.incarnation,
            { services.loadSession(resolved.session.accountId) }, documentIncarnations::activeIncarnation,
        ) { session -> action(session, resolved.reference) }
    }
    private inline fun <Result> withRootRead(
        rootId: String,
        action: (NextcloudSession, NextcloudDocumentIncarnation) -> Result,
    ): Result {
        val resolved = requireRoot(rootId)
        return withAndroidDocumentProviderReadAccess(
            resolved.session, resolved.incarnation,
            { services.loadSession(resolved.session.accountId) }, documentIncarnations::activeIncarnation,
        ) { session -> action(session, resolved.incarnation) }
    }
    private fun acquireDocumentReadLease(
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
    ) = acquireAndroidDocumentProviderReadLease(
        session,
        incarnation,
        { services.loadSession(session.accountId) },
        documentIncarnations::activeIncarnation,
    )
    private fun requireReference(
        documentId: String,
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
    ): NextcloudDocumentReference =
        providerCall(
            message = "This Nextcloud document ID is no longer valid.",
            accountIdentity = NextcloudDocumentIds.accountKey(session),
        ) {
            NextcloudDocumentIds.requireForSession(documentId, session, incarnation)
        }

    private inline fun <Result> withDocumentMutation(
        documentId: String,
        action: (NextcloudSession, NextcloudDocumentReference) -> Result,
    ): Result {
        val resolved = requireDocument(documentId)
        return withAndroidDocumentMutation(
            session = resolved.session,
            loadCurrentSession = { services.loadSession(resolved.session.accountId) },
        ) { session ->
            requireCurrentIncarnation(session, resolved.reference.incarnation)
            action(session, resolved.reference)
        }
    }
    private fun requireCurrentIncarnation(
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
    ) {
        require(documentIncarnations.activeIncarnation(NextcloudDocumentIds.accountKey(session)) == incarnation) {
            "The document belongs to an earlier account incarnation."
        }
    }
    private fun resolveAccount(session: NextcloudSession): ResolvedAccount {
        val accountKey = NextcloudDocumentIds.accountKey(session)
        cachedAccount?.takeIf { it.accountKey == accountKey }?.let { return it }
        return providerCall(
            message = "Could not resolve the signed-in Nextcloud account.",
            accountIdentity = accountKey,
        ) {
            val info = runBlocking(Dispatchers.IO) { services.loadServerInfo(session) }
            ResolvedAccount(accountKey, info.userId).also { cachedAccount = it }
        }
    }

    private fun findDocument(session: NextcloudSession, account: ResolvedAccount, path: String): NextcloudFile =
        providerCall(
            message = "The requested Nextcloud document was not found.",
            accountIdentity = account.accountKey,
        ) {
            val parent = NextcloudDocumentIds.parentPath(path)
            runBlocking(Dispatchers.IO) {
                services.listFilesWhileAccountLeaseHeld(session, account.userId, parent)
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

    private inline fun <T> providerCall(
        message: String,
        accountIdentity: String? = null,
        operation: () -> T,
    ): T = try {
        operation()
    } catch (failure: FileNotFoundException) {
        throw failure
    } catch (failure: Throwable) {
        Log.w(LOG_TAG, message, failure)
        recordProviderFailure(
            operation = "documents.provider-call",
            failure = failure,
            accountIdentity = accountIdentity,
            fields = listOf(SupportDiagnosticFieldDraft("provider_message", message)),
        )
        throw FileNotFoundException(message).also { it.initCause(failure) }
    }

    private fun recordProviderFailure(
        operation: String,
        failure: Throwable,
        accountIdentity: String? = null,
        remotePath: String? = null,
        fields: List<SupportDiagnosticFieldDraft> = emptyList(),
    ) {
        runCatching {
            val event = SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.VirtualFiles,
                operation = operation,
                outcome = "failed",
                fields = buildList {
                    remotePath?.let {
                        add(SupportDiagnosticFieldDraft("remote_path", it, SupportDiagnosticValuePrivacy.RemotePath))
                    }
                    addAll(fields)
                },
                exception = failure.toSupportDiagnosticExceptionDraft(),
            )
            if (accountIdentity == null) {
                services.recordSupportDiagnostic(event)
            } else {
                services.recordSupportDiagnosticForAccountIdentity(accountIdentity, event)
            }
        }
    }

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
        val PROXY_THREADS = List(PROXY_CALLBACK_THREAD_COUNT) { index ->
            HandlerThread("nextcloud-document-proxy-${index + 1}").apply { start() }
        }
        val PROXY_HANDLERS = PROXY_THREADS.map { thread -> Handler(thread.looper) }
        val NEXT_PROXY_HANDLER = AtomicInteger()

        fun nextProxyHandler(): Handler = PROXY_HANDLERS[
            Math.floorMod(NEXT_PROXY_HANDLER.getAndIncrement(), PROXY_HANDLERS.size)
        ]

        const val PROXY_CALLBACK_THREAD_COUNT = 4
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
