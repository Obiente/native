package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal data class AndroidLocalSyncDocument(
    val entry: LocalSyncEntry,
    val uri: Uri,
    val displayName: String,
)

internal interface AndroidFileSyncLocalTree {
    fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
    ): List<AndroidLocalSyncDocument>
    fun contentHash(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): String?
    fun stageForUpload(path: String, destination: File, maximumBytes: Long): LocalSyncEntry
    fun createDirectory(path: String, expectedLocalRevision: String?)
    fun writeFile(path: String, source: File, expectedLocalRevision: String?)
    fun delete(path: String, expectedLocalRevision: String)
    fun resolve(path: String): AndroidLocalSyncDocument?
}

/**
 * Revision-guarded adapter over one persisted Storage Access Framework tree.
 *
 * Downloads are staged as siblings. Existing content is renamed to a recovery backup before the
 * staged generation takes its name, so interruption never silently truncates the user's file.
 */
internal class AndroidSafFileSyncLocalTree(
    private val resolver: ContentResolver,
    rootId: String,
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
    ): List<AndroidLocalSyncDocument> {
        val result = ArrayList<AndroidLocalSyncDocument>()
        val pending = ArrayDeque<Pair<String, Uri>>()
        pending += "" to rootUri
        while (pending.isNotEmpty()) {
            val (parentPath, parentUri) = pending.removeFirst()
            require(parentPath.count { it == '/' } < MAX_DEPTH) { "The local folder is nested too deeply." }
            for (document in children(parentUri, parentPath)) {
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

    override fun contentHash(
        path: String,
        expectedLocalRevision: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): String? {
        val before = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(before.entry.kind == SyncEntryKind.File && before.entry.revision == expectedLocalRevision) {
            "The local file changed before content verification."
        }
        require(before.entry.size == expectedBytes) { "The local file size changed before content verification." }
        val hash = resolver.openInputStream(before.uri)?.use { input ->
            sha256SyncContentHash(input, expectedBytes, maximumBytes)
        } ?: return null
        val after = requireNotNull(resolve(path)) { "The local file disappeared during content verification." }
        require(after.entry.revision == expectedLocalRevision && after.entry.size == expectedBytes) {
            "The local file changed during content verification."
        }
        return hash
    }

    override fun stageForUpload(path: String, destination: File, maximumBytes: Long): LocalSyncEntry {
        val document = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(document.entry.kind == SyncEntryKind.File) { "Only files can be uploaded as file content." }
        require((document.entry.size ?: 0L) <= maximumBytes) { "The local file exceeds the sync size limit." }
        resolver.openInputStream(document.uri).use { source ->
            requireNotNull(source) { "The local file could not be opened." }
            FileOutputStream(destination).use { output ->
                var copied = 0L
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maximumBytes) { "The local file exceeds the sync size limit." }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        val after = requireNotNull(resolve(path)) { "The local file disappeared while it was read." }
        require(after.entry.revision == document.entry.revision) {
            "The local file changed while it was being prepared for upload."
        }
        return after.entry
    }

    override fun createDirectory(path: String, expectedLocalRevision: String?) {
        val existing = resolve(path)
        if (expectedLocalRevision == null) {
            require(existing == null) { "The local folder appeared after the sync scan." }
        } else {
            require(existing?.entry?.revision == expectedLocalRevision) {
                "The local folder changed after the sync scan."
            }
            require(existing.entry.kind == SyncEntryKind.Directory)
            return
        }
        val parent = ensureParent(path)
        val created = DocumentsContract.createDocument(
            resolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            path.substringAfterLast('/'),
        )
        requireNotNull(created) { "The local folder could not be created." }
    }

    override fun writeFile(path: String, source: File, expectedLocalRevision: String?) {
        require(source.isFile)
        val current = resolve(path)
        if (expectedLocalRevision == null) {
            require(current == null) { "The local file appeared after the sync scan." }
        } else {
            require(current?.entry?.revision == expectedLocalRevision) {
                "The local file changed after the sync scan."
            }
            require(current.entry.kind == SyncEntryKind.File) { "The local item changed type." }
        }
        val parentUri = ensureParent(path)
        val finalName = path.substringAfterLast('/')
        val token = UUID.randomUUID().toString()
        val stagedName = ".$finalName.nextcloud-native-download-$token"
        val staged = requireNotNull(
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/octet-stream",
                stagedName,
            ),
        ) { "A staged local file could not be created." }
        var backup: Uri? = null
        try {
            writeDocument(staged, source)
            if (current != null) {
                backup = requireNotNull(
                    DocumentsContract.renameDocument(
                        resolver,
                        current.uri,
                        ".$finalName.nextcloud-native-backup-$token",
                    ),
                ) { "The existing local file could not be protected before replacement." }
            }
            requireNotNull(DocumentsContract.renameDocument(resolver, staged, finalName)) {
                "The staged local file could not be published."
            }
            backup?.let { DocumentsContract.deleteDocument(resolver, it) }
        } catch (failure: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, staged) }
            backup?.let { protected ->
                runCatching { DocumentsContract.renameDocument(resolver, protected, finalName) }
            }
            throw failure
        }
    }

    override fun delete(path: String, expectedLocalRevision: String) {
        val current = requireNotNull(resolve(path)) { "The local item was already removed." }
        require(current.entry.revision == expectedLocalRevision) {
            "The local item changed after the sync scan."
        }
        require(DocumentsContract.deleteDocument(resolver, current.uri)) {
            "The local item could not be removed."
        }
    }

    override fun resolve(path: String): AndroidLocalSyncDocument? {
        if (path.isBlank()) return null
        var parentPath = ""
        var parentUri = rootUri
        val segments = path.split('/')
        require(segments.size <= MAX_DEPTH)
        segments.forEachIndexed { index, segment ->
            val match = children(parentUri, parentPath).singleOrNull { it.displayName == segment } ?: return null
            if (index == segments.lastIndex) return match
            if (match.entry.kind != SyncEntryKind.Directory) return null
            parentPath = match.entry.relativePath
            parentUri = match.uri
        }
        return null
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

    private fun children(parentUri: Uri, parentPath: String): List<AndroidLocalSyncDocument> {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0)
                    val name = cursor.getString(1)?.takeIf(String::isNotBlank) ?: continue
                    if (name.contains('/') || name.any(Char::isISOControl)) continue
                    val mimeType = cursor.getString(2).orEmpty()
                    val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val size = if (cursor.isNull(4)) null else cursor.getLong(4).coerceAtLeast(0L)
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

    private fun writeDocument(uri: Uri, source: File) {
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rwt")) {
            "The staged local file could not be opened."
        }
        descriptor.use {
            FileInputStream(source).use { input ->
                FileOutputStream(it.fileDescriptor).use { output ->
                    input.copyTo(output, BUFFER_BYTES)
                    output.fd.sync()
                }
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

internal fun knownAndroidFileSyncModifiedEpochMillis(value: Long): Long? = value.takeIf { it > 0L }

internal fun sha256SyncContentHash(
    input: InputStream,
    expectedBytes: Long,
    maximumBytes: Long,
): String? {
    require(expectedBytes >= 0L)
    require(maximumBytes > 0L)
    if (expectedBytes > maximumBytes) return null
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) return null
        digest.update(buffer, 0, read)
    }
    if (total != expectedBytes) return null
    return "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
