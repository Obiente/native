package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.normalizeSyncSha256
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

internal data class AndroidRemoteSyncDocument(
    val entry: RemoteSyncEntry,
    val isDirectory: Boolean,
)

/** Recursive, bounded and revision-guarded view of one Nextcloud Files subtree. */
internal class AndroidFileSyncRemoteTree(
    private val session: NextcloudSession,
    private val userId: String,
    remoteRootPath: String,
    private val webDav: NextcloudDocumentWebDav,
) {
    private val rootPath = remoteRootPath.trim('/')

    fun scan(
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
    ): List<AndroidRemoteSyncDocument> {
        val result = ArrayList<AndroidRemoteSyncDocument>()
        val pending = ArrayDeque<String>()
        pending += ""
        while (pending.isNotEmpty()) {
            val relativeParent = pending.removeFirst()
            require(relativeParent.count { it == '/' } < MAX_DEPTH) { "The Nextcloud folder is nested too deeply." }
            val listing = webDav.listDirectory(
                session = session,
                userId = userId,
                path = fullPath(relativeParent),
                maximumEntries = MAX_CHILDREN,
            )
            require(!listing.limited) { "A Nextcloud folder contains too many entries to sync safely." }
            listing.files.forEach { file ->
                val relativePath = toRelativePath(file.path) ?: return@forEach
                val kind = if (file.isDirectory) SyncEntryKind.Directory else SyncEntryKind.File
                if (!includes(relativePath, kind)) return@forEach
                require(result.size < MAX_ENTRIES) { "The Nextcloud folder contains too many entries." }
                val etag = file.etag?.takeIf(String::isNotBlank)
                    ?: error("Refresh failed because ${file.name} has no server revision.")
                val document = AndroidRemoteSyncDocument(
                    entry = RemoteSyncEntry(
                        relativePath = relativePath,
                        kind = kind,
                        etag = etag,
                        size = if (file.isDirectory) null else file.size,
                        contentHash = if (file.isDirectory) {
                            null
                        } else {
                            file.checksums.firstNotNullOfOrNull(::normalizeSyncSha256)
                        },
                    ),
                    isDirectory = file.isDirectory,
                )
                result += document
                if (file.isDirectory) pending += relativePath
            }
        }
        return result.sortedBy { it.entry.relativePath }
    }

    fun resolve(relativePath: String): AndroidRemoteSyncDocument? {
        val parent = relativePath.substringBeforeLast('/', "")
        val target = fullPath(relativePath)
        return webDav.listDirectory(session, userId, fullPath(parent), MAX_CHILDREN)
            .files
            .firstOrNull { it.path.trim('/') == target }
            ?.let { file ->
                val etag = file.etag?.takeIf(String::isNotBlank)
                    ?: error("The server item has no usable revision.")
                AndroidRemoteSyncDocument(
                    RemoteSyncEntry(
                        relativePath = relativePath,
                        kind = if (file.isDirectory) SyncEntryKind.Directory else SyncEntryKind.File,
                        etag = etag,
                        size = if (file.isDirectory) null else file.size,
                        contentHash = if (file.isDirectory) {
                            null
                        } else {
                            file.checksums.firstNotNullOfOrNull(::normalizeSyncSha256)
                        },
                    ),
                    file.isDirectory,
                )
            }
    }

    fun stageDownload(
        relativePath: String,
        expectedRemoteEtag: String,
        destination: File,
        maximumBytes: Long,
    ): RemoteSyncEntry {
        FileOutputStream(destination).use { output ->
            webDav.readFile(
                session = session,
                userId = userId,
                path = fullPath(relativePath),
                destination = output,
                maximumBytes = maximumBytes,
                expectedEtag = expectedRemoteEtag,
            )
            output.fd.sync()
        }
        val after = requireNotNull(resolve(relativePath)) { "The server file disappeared while downloading." }
        require(after.entry.etag == expectedRemoteEtag) {
            "The server file changed while downloading."
        }
        return after.entry
    }

    /**
     * Verifies a DAV checksum hint against bytes read from the exact ETag generation.
     *
     * Nextcloud documents that regular-upload checksum properties are client supplied and are not
     * always server validated. They can narrow candidates, but only this bounded GET makes them
     * safe evidence for automatically accepting identical local and remote content.
     */
    fun verifyContentHash(
        relativePath: String,
        expectedRemoteEtag: String,
        expectedContentHash: String,
        expectedBytes: Long,
        maximumBytes: Long,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(byte: Int) {
                digest.update(byte.toByte())
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                digest.update(bytes, offset, length)
            }
        }
        val result = webDav.readFile(
            session = session,
            userId = userId,
            path = fullPath(relativePath),
            destination = sink,
            maximumBytes = maximumBytes,
            expectedEtag = expectedRemoteEtag,
        )
        require(result.byteCount == expectedBytes) { "The server returned truncated content during verification." }
        require(result.etag == null || result.etag == expectedRemoteEtag) {
            "The server file changed during content verification."
        }
        val actual = "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return actual == expectedContentHash
    }

    fun createDirectory(relativePath: String, expectedRemoteEtag: String?) {
        val current = resolve(relativePath)
        if (expectedRemoteEtag == null) {
            require(current == null) { "The server folder appeared after the sync scan." }
            webDav.createFolder(session, userId, fullPath(relativePath))
        } else {
            require(current?.entry?.etag == expectedRemoteEtag) {
                "The server folder changed after the sync scan."
            }
            require(current.isDirectory)
        }
    }

    fun writeFile(relativePath: String, source: File, expectedRemoteEtag: String?) {
        val current = resolve(relativePath)
        if (expectedRemoteEtag == null) {
            require(current == null) { "The server file appeared after the sync scan." }
            webDav.createFile(session, userId, fullPath(relativePath), source)
        } else {
            require(current?.entry?.etag == expectedRemoteEtag) {
                "The server file changed after the sync scan."
            }
            require(!current.isDirectory) { "The server item changed type." }
            webDav.replaceFile(
                session,
                userId,
                fullPath(relativePath),
                source,
                expectedRemoteEtag,
            )
        }
    }

    fun delete(relativePath: String, expectedRemoteEtag: String) {
        val current = requireNotNull(resolve(relativePath)) { "The server item was already removed." }
        require(current.entry.etag == expectedRemoteEtag) {
            "The server item changed after the sync scan."
        }
        webDav.delete(
            session,
            userId,
            fullPath(relativePath),
            expectedRemoteEtag,
            isDirectory = current.isDirectory,
        )
    }

    private fun fullPath(relativePath: String): String =
        listOf(rootPath, relativePath.trim('/')).filter(String::isNotBlank).joinToString("/")

    private fun toRelativePath(fullPath: String): String? {
        val normalized = fullPath.trim('/')
        if (rootPath.isBlank()) return normalized.takeIf(String::isNotBlank)
        if (!normalized.startsWith("$rootPath/")) return null
        return normalized.removePrefix("$rootPath/").takeIf(String::isNotBlank)
    }

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_CHILDREN = 5_000
        const val MAX_DEPTH = 64
    }
}
