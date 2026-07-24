package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal data class DesktopLocalSyncDocument(
    val entry: LocalSyncEntry,
    val path: Path,
)

/** Revision-guarded, symlink-rejecting local filesystem adapter for desktop folder sync. */
internal class DesktopFileSyncLocalTree(root: File) {
    private val root = root.toPath().toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            "The selected desktop sync folder is no longer available."
        }
        require(!Files.isSymbolicLink(this.root)) { "A symbolic link cannot be used as a sync root." }
    }

    fun scan(): List<DesktopLocalSyncDocument> {
        recoverOwnedStagingFiles()
        val result = ArrayList<DesktopLocalSyncDocument>()
        Files.walkFileTree(
            root,
            setOf(),
            MAX_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!Files.isSymbolicLink(dir)) {
                        "Folder sync stopped because ${relative(dir)} is a symbolic link."
                    }
                    if (dir != root) add(dir, attrs, SyncEntryKind.Directory)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!Files.isSymbolicLink(file)) {
                        "Folder sync stopped because ${relative(file)} is a symbolic link."
                    }
                    require(attrs.isRegularFile) {
                        "Folder sync stopped because ${relative(file)} is not a regular file."
                    }
                    add(file, attrs, SyncEntryKind.File)
                    return FileVisitResult.CONTINUE
                }

                private fun add(path: Path, attrs: BasicFileAttributes, kind: SyncEntryKind) {
                    require(result.size < MAX_ENTRIES) { "The desktop folder contains too many entries." }
                    val relative = relative(path)
                    result += DesktopLocalSyncDocument(
                        LocalSyncEntry(
                            relativePath = relative,
                            kind = kind,
                            revision = revision(path, attrs),
                            size = attrs.size().takeIf { kind == SyncEntryKind.File },
                        ),
                        path,
                    )
                }
            },
        )
        return result.sortedBy { it.entry.relativePath }
    }

    fun resolve(relativePath: String): DesktopLocalSyncDocument? {
        val path = safePath(relativePath)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(path)) { "The local item changed into a symbolic link." }
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val kind = when {
            attrs.isDirectory -> SyncEntryKind.Directory
            attrs.isRegularFile -> SyncEntryKind.File
            else -> error("The local item is not a regular file or folder.")
        }
        return DesktopLocalSyncDocument(
            LocalSyncEntry(
                relativePath,
                kind,
                revision(path, attrs),
                attrs.size().takeIf { kind == SyncEntryKind.File },
            ),
            path,
        )
    }

    fun stageForUpload(relativePath: String, destination: File, maximumBytes: Long): LocalSyncEntry {
        val before = requireNotNull(resolve(relativePath)) { "The local file no longer exists." }
        require(before.entry.kind == SyncEntryKind.File)
        require((before.entry.size ?: 0L) <= maximumBytes) { "The local file exceeds the sync size limit." }
        FileInputStream(before.path.toFile()).use { input ->
            FileOutputStream(destination).use { output ->
                copyBounded(input, output, maximumBytes)
                output.fd.sync()
            }
        }
        val after = requireNotNull(resolve(relativePath))
        require(after.entry.revision == before.entry.revision) {
            "The local file changed while it was being prepared for upload."
        }
        return after.entry
    }

    fun createDirectory(relativePath: String, expectedLocalRevision: String?) {
        val current = resolve(relativePath)
        if (expectedLocalRevision == null) {
            require(current == null) { "The local folder appeared after the sync scan." }
            Files.createDirectories(safePath(relativePath))
        } else {
            require(current?.entry?.revision == expectedLocalRevision) {
                "The local folder changed after the sync scan."
            }
            require(current.entry.kind == SyncEntryKind.Directory)
        }
    }

    fun writeFile(relativePath: String, source: File, expectedLocalRevision: String?) {
        val destination = safePath(relativePath)
        val current = resolve(relativePath)
        if (expectedLocalRevision == null) {
            require(current == null) { "The local file appeared after the sync scan." }
        } else {
            require(current?.entry?.revision == expectedLocalRevision) {
                "The local file changed after the sync scan."
            }
            require(current.entry.kind == SyncEntryKind.File)
        }
        val parent = requireNotNull(destination.parent)
        Files.createDirectories(parent)
        val token = UUID.randomUUID().toString()
        val staged = parent.resolve(".${destination.fileName}.nextcloud-native-download-$token")
        val backup = parent.resolve(".${destination.fileName}.nextcloud-native-backup-$token")
        Files.copy(source.toPath(), staged, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(staged, StandardOpenOption.WRITE).use { it.force(true) }
        var protected = false
        try {
            if (current != null) {
                move(current.path, backup, replace = false)
                protected = true
            }
            move(staged, destination, replace = false)
            if (protected) Files.deleteIfExists(backup)
        } catch (failure: Throwable) {
            Files.deleteIfExists(staged)
            if (protected && !Files.exists(destination)) runCatching { move(backup, destination, replace = false) }
            throw failure
        }
    }

    fun delete(relativePath: String, expectedLocalRevision: String) {
        val current = requireNotNull(resolve(relativePath)) { "The local item was already removed." }
        require(current.entry.revision == expectedLocalRevision) {
            "The local item changed after the sync scan."
        }
        Files.delete(current.path)
    }

    private fun recoverOwnedStagingFiles() {
        Files.walkFileTree(
            root,
            setOf(),
            MAX_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    when {
                        DOWNLOAD_MARKER in name -> Files.deleteIfExists(file)
                        BACKUP_MARKER in name -> {
                            val finalName = name.removePrefix(".").substringBefore(BACKUP_MARKER)
                            val finalPath = requireNotNull(file.parent).resolve(finalName)
                            if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
                                Files.deleteIfExists(file)
                            } else {
                                move(file, finalPath, replace = false)
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun safePath(relativePath: String): Path {
        requireValidSyncPath(relativePath)
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root) && resolved != root) { "The local sync path escaped its root." }
        return resolved
    }

    private fun relative(path: Path): String =
        root.relativize(path.toAbsolutePath().normalize()).joinToString("/") { it.toString() }

    private fun revision(path: Path, attrs: BasicFileAttributes): String {
        val fingerprint = buildString {
            append(attrs.fileKey()?.toString().orEmpty())
            append('\u0000')
            append(attrs.lastModifiedTime().toMillis())
            append('\u0000')
            append(attrs.size())
            append('\u0000')
            append(attrs.isDirectory)
            append('\u0000')
            append(relative(path))
        }
        return "desktop-" + MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun move(source: Path, destination: Path, replace: Boolean) {
        val options = buildList {
            add(StandardCopyOption.ATOMIC_MOVE)
            if (replace) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        try {
            Files.move(source, destination, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source, destination)
            }
        }
    }

    private fun copyBounded(
        input: FileInputStream,
        output: FileOutputStream,
        maximumBytes: Long,
    ) {
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximumBytes) { "The local file exceeds the sync size limit." }
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_DEPTH = 64
        const val BUFFER_BYTES = 64 * 1024
        const val DOWNLOAD_MARKER = ".nextcloud-native-download-"
        const val BACKUP_MARKER = ".nextcloud-native-backup-"
    }
}
