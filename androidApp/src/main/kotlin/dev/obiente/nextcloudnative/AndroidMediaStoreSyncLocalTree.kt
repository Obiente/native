package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal fun createAndroidFileSyncLocalTree(
    resolver: ContentResolver,
    rootId: String,
    contentHashPaths: Set<String> = emptySet(),
): AndroidFileSyncLocalTree =
    if (rootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX)) {
        AndroidMediaStoreSyncLocalTree(
            root = resolveMediaStoreSyncRoot(rootId, Environment.getExternalStorageDirectory()),
            contentHashPaths = contentHashPaths,
        )
    } else {
        AndroidSafFileSyncLocalTree(resolver, rootId, contentHashPaths)
    }

internal fun resolveMediaStoreSyncRoot(rootId: String, externalStorageRoot: File): File {
    require(rootId.startsWith(MEDIA_STORE_SYNC_ROOT_PREFIX)) {
        "The local sync root is not a detected MediaStore folder."
    }
    val relativePath = normalizeMediaStoreRelativePath(rootId.removePrefix(MEDIA_STORE_SYNC_ROOT_PREFIX))
    val storageRoot = externalStorageRoot.canonicalFile
    val folder = File(storageRoot, relativePath).canonicalFile
    require(folder.toPath().startsWith(storageRoot.toPath()) && folder != storageRoot) {
        "The detected media folder is outside shared storage."
    }
    require(folder.isDirectory) { "The detected media folder no longer exists." }
    require(folder.canRead()) { "The detected media folder is not readable." }
    return folder
}

/**
 * Direct shared-storage adapter for folders already discovered through MediaStore.
 *
 * It avoids a redundant SAF browser while constraining every root to primary shared storage.
 */
internal class AndroidMediaStoreSyncLocalTree(
    private val root: File,
    private val contentHashPaths: Set<String> = emptySet(),
) : AndroidFileSyncLocalTree {
    init {
        require(root.isDirectory && root.canRead()) { "The detected media folder is unavailable." }
    }

    override fun scan(): List<AndroidLocalSyncDocument> {
        val result = ArrayList<AndroidLocalSyncDocument>()
        val pending = ArrayDeque<Pair<String, File>>()
        pending += "" to root
        while (pending.isNotEmpty()) {
            val (parentPath, parent) = pending.removeFirst()
            require(parentPath.count { it == '/' } < MAX_DEPTH) { "The local folder is nested too deeply." }
            val children = parent.listFiles()
                ?.asSequence()
                ?.filter { it.name.isSafeLocalName() }
                ?.sortedBy { it.name.lowercase() }
                ?.toList()
                .orEmpty()
            for (child in children) {
                require(result.size < MAX_ENTRIES) { "The local folder contains too many entries." }
                val path = if (parentPath.isBlank()) child.name else "$parentPath/${child.name}"
                val document = child.toSyncDocument(path)
                result += document
                if (document.entry.kind == SyncEntryKind.Directory) pending += path to child
            }
        }
        return result.sortedBy { it.entry.relativePath }
    }

    override fun stageForUpload(path: String, destination: File, maximumBytes: Long): LocalSyncEntry {
        val before = requireNotNull(resolve(path)) { "The local file no longer exists." }
        require(before.entry.kind == SyncEntryKind.File) { "Only files can be uploaded as file content." }
        require((before.entry.size ?: 0L) <= maximumBytes) { "The local file exceeds the sync size limit." }
        FileInputStream(before.uri.toFile()).use { input ->
            FileOutputStream(destination).use { output ->
                var copied = 0L
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maximumBytes) { "The local file exceeds the sync size limit." }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        val after = requireNotNull(resolve(path)) { "The local file disappeared while it was read." }
        require(after.entry.revision == before.entry.revision) {
            "The local file changed while it was being prepared for upload."
        }
        return after.entry
    }

    override fun createDirectory(path: String, expectedLocalRevision: String?) {
        val existing = resolve(path)
        if (expectedLocalRevision != null) {
            require(existing?.entry?.revision == expectedLocalRevision) {
                "The local folder changed after the sync scan."
            }
            require(existing.entry.kind == SyncEntryKind.Directory)
            return
        }
        require(existing == null) { "The local folder appeared after the sync scan." }
        val target = safeFile(path)
        require(target.mkdirs()) { "The local folder could not be created." }
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
        val target = safeFile(path)
        val parent = requireNotNull(target.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "A local parent folder could not be created." }
        val token = UUID.randomUUID().toString()
        val staged = File(parent, ".${target.name}.nextcloud-native-download-$token")
        val backup = File(parent, ".${target.name}.nextcloud-native-backup-$token")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(staged).use { output ->
                    input.copyTo(output, BUFFER_BYTES)
                    output.fd.sync()
                }
            }
            if (target.exists()) {
                require(target.renameTo(backup)) {
                    "The existing local file could not be protected before replacement."
                }
            }
            require(staged.renameTo(target)) { "The staged local file could not be published." }
            if (backup.exists()) {
                require(backup.delete()) { "The replaced local file could not be cleaned up." }
            }
        } catch (failure: Throwable) {
            staged.delete()
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            throw failure
        }
    }

    override fun delete(path: String, expectedLocalRevision: String) {
        val current = requireNotNull(resolve(path)) { "The local item was already removed." }
        require(current.entry.revision == expectedLocalRevision) {
            "The local item changed after the sync scan."
        }
        require(current.uri.toFile().deleteRecursively()) { "The local item could not be removed." }
    }

    override fun resolve(path: String): AndroidLocalSyncDocument? {
        if (path.isBlank()) return null
        val normalized = normalizeRelativeSyncPath(path)
        val file = safeFile(normalized)
        if (!file.exists()) return null
        return file.toSyncDocument(normalized)
    }

    private fun safeFile(path: String): File {
        val normalized = normalizeRelativeSyncPath(path)
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, normalized).canonicalFile
        require(file.toPath().startsWith(canonicalRoot.toPath()) && file != canonicalRoot) {
            "The local sync path escapes its root."
        }
        return file
    }

    private fun File.toSyncDocument(relativePath: String): AndroidLocalSyncDocument {
        val kind = if (isDirectory) SyncEntryKind.Directory else SyncEntryKind.File
        val size = if (kind == SyncEntryKind.File) length().coerceAtLeast(0L) else null
        return AndroidLocalSyncDocument(
            entry = LocalSyncEntry(
                relativePath = relativePath,
                kind = kind,
                revision = fileRevision(relativePath, kind, lastModified(), size),
                size = size,
                contentHash = if (
                    kind == SyncEntryKind.File &&
                    relativePath in contentHashPaths &&
                    size != null &&
                    size <= ANDROID_SYNC_CONTENT_IDENTITY_MAX_BYTES
                ) {
                    runCatching {
                        inputStream().use { input ->
                            sha256SyncContentHash(
                                input,
                                expectedBytes = size,
                                maximumBytes = ANDROID_SYNC_CONTENT_IDENTITY_MAX_BYTES,
                            )
                        }
                    }.getOrNull()
                } else {
                    null
                },
            ),
            uri = Uri.fromFile(this),
            displayName = name,
        )
    }

    private fun normalizeRelativeSyncPath(path: String): String {
        val segments = path.trim('/').split('/')
        require(segments.isNotEmpty() && segments.size <= MAX_DEPTH)
        require(segments.all { it.isSafeLocalName() }) { "The local sync path is invalid." }
        return segments.joinToString("/")
    }

    private fun String.isSafeLocalName(): Boolean =
        isNotBlank() &&
            this !in setOf(".", "..") &&
            '/' !in this &&
            '\\' !in this &&
            none(Char::isISOControl)

    private fun fileRevision(
        relativePath: String,
        kind: SyncEntryKind,
        modified: Long,
        size: Long?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$relativePath\u0000$kind\u0000$modified\u0000${size ?: -1L}".encodeToByteArray())
        return "file-" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun Uri.toFile(): File = File(requireNotNull(path))

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_DEPTH = 64
        const val BUFFER_BYTES = 64 * 1024
    }
}
