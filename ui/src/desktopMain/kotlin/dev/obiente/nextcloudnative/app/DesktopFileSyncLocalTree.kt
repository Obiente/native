package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileStore
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
import java.util.concurrent.ConcurrentHashMap

internal data class DesktopLocalSyncDocument(
    val entry: LocalSyncEntry,
    val path: Path,
)

/** Revision-guarded, symlink-rejecting local filesystem adapter for desktop folder sync. */
internal class DesktopFileSyncLocalTree(
    root: File,
    private val changeTokenProvider: (Path) -> String? = ::desktopFileChangeToken,
    private val contentDigester: (Path) -> String = ::desktopSha256File,
) {
    private val root = root.toPath().toAbsolutePath().normalize()
    private val knownDirectoryIdentities = ConcurrentHashMap<String, LocalDirectoryIdentity>()

    init {
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            "The selected desktop sync folder is no longer available."
        }
        require(!Files.isSymbolicLink(this.root)) { "A symbolic link cannot be used as a sync root." }
        rememberOrRequireDirectoryIdentity(
            this.root,
            Files.readAttributes(this.root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS),
        )
    }

    fun scan(
        cachedLocalRevisions: Map<String, String> = emptyMap(),
        includes: (relativePath: String, kind: SyncEntryKind) -> Boolean = { _, _ -> true },
    ): List<DesktopLocalSyncDocument> {
        requireSafeAncestors(root, includeLeaf = true, allowMissingTail = false)
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
                    rememberOrRequireDirectoryIdentity(dir, attrs)
                    if (dir != root) {
                        if (isOwnedRecoveryPath(dir)) return FileVisitResult.SKIP_SUBTREE
                        val relative = relative(dir)
                        if (!includes(relative, SyncEntryKind.Directory)) return FileVisitResult.SKIP_SUBTREE
                        add(dir, attrs, SyncEntryKind.Directory)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isOwnedRecoveryPath(file)) return FileVisitResult.CONTINUE
                    require(!Files.isSymbolicLink(file)) {
                        "Folder sync stopped because ${relative(file)} is a symbolic link."
                    }
                    require(attrs.isRegularFile) {
                        "Folder sync stopped because ${relative(file)} is not a regular file."
                    }
                    if (includes(relative(file), SyncEntryKind.File)) add(file, attrs, SyncEntryKind.File)
                    return FileVisitResult.CONTINUE
                }

                private fun add(path: Path, attrs: BasicFileAttributes, kind: SyncEntryKind) {
                    require(result.size < MAX_ENTRIES) { "The desktop folder contains too many entries." }
                    val relative = relative(path)
                    val metadata = metadataDigest(path, attrs)
                    val contentDigest = path.takeIf { kind == SyncEntryKind.File }?.let {
                        reusableContentDigest(cachedLocalRevisions[relative], metadata)
                            ?: run {
                                requireSafeAncestors(path, includeLeaf = true, allowMissingTail = false)
                                contentDigester(path)
                            }
                    }
                    result += DesktopLocalSyncDocument(
                        LocalSyncEntry(
                            relativePath = relative,
                            kind = kind,
                            revision = revision(metadata.value, contentDigest),
                            size = attrs.size().takeIf { kind == SyncEntryKind.File },
                            contentHash = contentDigest?.let { "sha256:$it" },
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
        requireSafeAncestors(path, includeLeaf = true, allowMissingTail = true)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(!Files.isSymbolicLink(path)) { "The local item changed into a symbolic link." }
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val kind = when {
            attrs.isDirectory -> SyncEntryKind.Directory
            attrs.isRegularFile -> SyncEntryKind.File
            else -> error("The local item is not a regular file or folder.")
        }
        val metadata = metadataDigest(path, attrs)
        if (kind == SyncEntryKind.Directory) rememberOrRequireDirectoryIdentity(path, attrs)
        val contentDigest = path.takeIf { kind == SyncEntryKind.File }?.let {
            requireSafeAncestors(path, includeLeaf = true, allowMissingTail = false)
            contentDigester(path)
        }
        return DesktopLocalSyncDocument(
            LocalSyncEntry(
                relativePath,
                kind,
                revision(metadata.value, contentDigest),
                attrs.size().takeIf { kind == SyncEntryKind.File },
                contentDigest?.let { "sha256:$it" },
            ),
            path,
        )
    }

    fun fileStore(relativePath: String): FileStore {
        val destination = safePath(relativePath)
        requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = true)
        val existingParent = generateSequence(destination.parent) { parent -> parent.parent }
            .first { parent -> Files.exists(parent, LinkOption.NOFOLLOW_LINKS) }
        return Files.getFileStore(existingParent)
    }

    fun stageForUpload(relativePath: String, destination: File, maximumBytes: Long): LocalSyncEntry {
        val before = requireNotNull(resolve(relativePath)) { "The local file no longer exists." }
        require(before.entry.kind == SyncEntryKind.File)
        require((before.entry.size ?: 0L) <= maximumBytes) { "The local file exceeds the sync size limit." }
        requireSafeAncestors(before.path, includeLeaf = true, allowMissingTail = false)
        Files.newByteChannel(
            before.path,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            Channels.newInputStream(channel).use { input ->
                FileOutputStream(destination).use { output ->
                    copyBounded(input, output, maximumBytes)
                    output.fd.sync()
                }
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
            val destination = safePath(relativePath)
            requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = true)
            createSafeDirectories(destination)
            requireSafeAncestors(destination, includeLeaf = true, allowMissingTail = false)
        } else {
            require(current?.entry?.revision == expectedLocalRevision) {
                "The local folder changed after the sync scan."
            }
            require(current.entry.kind == SyncEntryKind.Directory)
        }
    }

    fun writeFile(relativePath: String, source: File, expectedLocalRevision: String?): LocalSyncEntry {
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
        requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = true)
        createSafeDirectories(parent)
        requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = false)
        return publishFileReplacement(destination, current, source)
    }

    fun replaceWithFile(relativePath: String, source: File, expectedLocalRevision: String): LocalSyncEntry {
        val destination = safePath(relativePath)
        val current = requireNotNull(resolve(relativePath)) { "The local item was already removed." }
        require(current.entry.revision == expectedLocalRevision) {
            "The local item changed after the sync scan."
        }
        require(current.entry.kind == SyncEntryKind.Directory) {
            "The local item type changed after the sync scan."
        }
        return publishFileReplacement(destination, current, source)
    }

    fun replaceWithDirectory(relativePath: String, expectedLocalRevision: String) {
        val destination = safePath(relativePath)
        val current = requireNotNull(resolve(relativePath)) { "The local item was already removed." }
        require(current.entry.revision == expectedLocalRevision) {
            "The local item changed after the sync scan."
        }
        require(current.entry.kind == SyncEntryKind.File) {
            "The local item type changed after the sync scan."
        }
        val parent = requireNotNull(destination.parent)
        requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = false)
        val token = UUID.randomUUID().toString()
        val backup = parent.resolve(".${destination.fileName}.nextcloud-native-backup-$token")
        var protected = false
        try {
            requireSafeAncestors(destination, includeLeaf = true, allowMissingTail = false)
            requireUnchanged(current)
            move(current.path, backup, replace = false)
            protected = true
            forgetDirectoryIdentitiesWithin(destination)
            requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = false)
            Files.createDirectory(destination)
            requireSafeAncestors(destination, includeLeaf = true, allowMissingTail = false)
        } catch (failure: Throwable) {
            if (protected && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { move(backup, destination, replace = false) }
            }
            throw failure
        }
        deleteOwnedPath(backup)
    }

    private fun publishFileReplacement(
        destination: Path,
        current: DesktopLocalSyncDocument?,
        source: File,
    ): LocalSyncEntry {
        val parent = requireNotNull(destination.parent)
        requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = false)
        val expectedContentHash = "sha256:${contentDigester(source.toPath())}"
        val token = UUID.randomUUID().toString()
        val staged = parent.resolve(".${destination.fileName}.nextcloud-native-download-$token")
        val backup = parent.resolve(".${destination.fileName}.nextcloud-native-backup-$token")
        Files.copy(source.toPath(), staged, StandardCopyOption.REPLACE_EXISTING)
        requireSafeAncestors(staged, includeLeaf = true, allowMissingTail = false)
        FileChannel.open(staged, setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { it.force(true) }
        var protected = false
        try {
            if (current != null) {
                requireSafeAncestors(current.path, includeLeaf = true, allowMissingTail = false)
                requireUnchanged(current)
                move(current.path, backup, replace = false)
                protected = true
                if (current.entry.kind == SyncEntryKind.Directory) {
                    forgetDirectoryIdentitiesWithin(destination)
                }
            }
            move(staged, destination, replace = false)
            val published = requireNotNull(resolve(relative(destination))) {
                "The published local file disappeared."
            }.entry
            require(published.kind == SyncEntryKind.File && published.contentHash == expectedContentHash) {
                "The local file changed while its synchronized revision was being recorded."
            }
            if (protected) deleteOwnedPath(backup)
            return published
        } catch (failure: Throwable) {
            Files.deleteIfExists(staged)
            if (protected && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { move(backup, destination, replace = false) }
            }
            throw failure
        }
    }

    fun delete(relativePath: String, expectedLocalRevision: String) {
        val current = requireNotNull(resolve(relativePath)) { "The local item was already removed." }
        require(current.entry.revision == expectedLocalRevision) {
            "The local item changed after the sync scan."
        }
        requireSafeAncestors(current.path, includeLeaf = true, allowMissingTail = false)
        requireUnchanged(current)
        Files.delete(current.path)
        if (current.entry.kind == SyncEntryKind.Directory) forgetDirectoryIdentitiesWithin(current.path)
    }

    private fun recoverOwnedStagingFiles() {
        Files.walkFileTree(
            root,
            setOf(),
            MAX_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val owned = ownedRecoveryPath(dir)
                    if (dir == root || owned?.kind != OwnedRecoveryKind.Backup) {
                        return FileVisitResult.CONTINUE
                    }
                    reconcileOwnedBackup(dir)
                    return if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                        FileVisitResult.CONTINUE
                    } else {
                        FileVisitResult.SKIP_SUBTREE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    when (ownedRecoveryPath(file)?.kind) {
                        OwnedRecoveryKind.Download -> Files.deleteIfExists(file)
                        OwnedRecoveryKind.Backup -> reconcileOwnedBackup(file)
                        null -> Unit
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun reconcileOwnedBackup(backup: Path) {
        val owned = ownedRecoveryPath(backup)?.takeIf { it.kind == OwnedRecoveryKind.Backup } ?: return
        val finalPath = requireNotNull(backup.parent).resolve(owned.destinationName)
        if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeAncestors(finalPath, includeLeaf = true, allowMissingTail = false)
            val incompleteDownload = backup.parent.resolve(
                ".${owned.destinationName}$DOWNLOAD_MARKER${owned.token}",
            )
            if (!Files.exists(incompleteDownload, LinkOption.NOFOLLOW_LINKS)) deleteOwnedPath(backup)
        } else {
            move(backup, finalPath, replace = false)
        }
    }

    private fun ownedRecoveryPath(path: Path): OwnedRecoveryPath? {
        val name = path.fileName.toString()
        if (!name.startsWith('.')) return null
        val candidates = listOf(
            OwnedRecoveryKind.Download to DOWNLOAD_MARKER,
            OwnedRecoveryKind.Backup to BACKUP_MARKER,
        )
        return candidates.firstNotNullOfOrNull { (kind, marker) ->
            val markerIndex = name.lastIndexOf(marker)
            if (markerIndex <= 1) return@firstNotNullOfOrNull null
            val token = name.substring(markerIndex + marker.length)
            if (runCatching { UUID.fromString(token) }.isFailure) return@firstNotNullOfOrNull null
            val destinationName = name.substring(1, markerIndex)
            destinationName.takeIf(String::isNotBlank)?.let { OwnedRecoveryPath(kind, it, token) }
        }
    }

    private fun isOwnedRecoveryPath(path: Path): Boolean = ownedRecoveryPath(path) != null

    private fun deleteOwnedPath(path: Path) {
        require(path.startsWith(root) && ownedRecoveryPath(path)?.kind == OwnedRecoveryKind.Backup)
        requireSafeAncestors(path, includeLeaf = true, allowMissingTail = false)
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                    failure?.let { throw it }
                    Files.delete(dir)
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

    private fun requireSafeAncestors(path: Path, includeLeaf: Boolean, allowMissingTail: Boolean) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized == root || normalized.startsWith(root)) { "The local sync path escaped its root." }
        val relative = root.relativize(normalized)
        var current = root
        val components = relative.toList()
        val lastDirectoryIndex = if (includeLeaf) components.lastIndex else components.lastIndex - 1
        for (index in -1..lastDirectoryIndex) {
            if (index >= 0) current = current.resolve(components[index])
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(allowMissingTail) { "The local sync path disappeared before it could be used." }
                return
            }
            require(!Files.isSymbolicLink(current)) {
                "Folder sync stopped because an item in the local path became a symbolic link."
            }
            val attrs = Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (index < lastDirectoryIndex || normalized == root || !includeLeaf) {
                require(attrs.isDirectory) { "Folder sync stopped because a local path parent is no longer a folder." }
                rememberOrRequireDirectoryIdentity(current, attrs)
            } else if (attrs.isDirectory) {
                rememberOrRequireDirectoryIdentity(current, attrs)
            } else {
                require(attrs.isRegularFile) { "Folder sync stopped because a local item is no longer a regular file." }
            }
        }
    }

    private fun rememberOrRequireDirectoryIdentity(path: Path, attrs: BasicFileAttributes) {
        require(attrs.isDirectory)
        val key = if (path == root) "" else relative(path)
        val identity = LocalDirectoryIdentity(attrs.fileKey()?.toString(), attrs.creationTime().toMillis())
        val known = knownDirectoryIdentities.putIfAbsent(key, identity)
        require(known == null || known == identity) {
            "Folder sync stopped because a local path parent was replaced after it was scanned."
        }
    }

    private fun forgetDirectoryIdentitiesWithin(path: Path) {
        val prefix = relative(path)
        knownDirectoryIdentities.keys.removeIf { key -> key == prefix || key.startsWith("$prefix/") }
    }

    private fun requireUnchanged(expected: DesktopLocalSyncDocument) {
        val current = requireNotNull(resolve(expected.entry.relativePath)) {
            "The local item disappeared before it could be changed."
        }
        require(current.entry.revision == expected.entry.revision) {
            "The local item changed immediately before the synchronized operation."
        }
    }

    private fun createSafeDirectories(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        require(normalized == root || normalized.startsWith(root)) { "The local sync path escaped its root." }
        var current = root
        root.relativize(normalized).forEach { component ->
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current)
            }
            requireSafeAncestors(current, includeLeaf = true, allowMissingTail = false)
        }
    }

    private fun relative(path: Path): String =
        root.relativize(path.toAbsolutePath().normalize()).joinToString("/") { it.toString() }

    private fun metadataDigest(path: Path, attrs: BasicFileAttributes): LocalMetadataDigest {
        val changeTime = changeTokenProvider(path)
        val fingerprint = buildString {
            append(attrs.fileKey()?.toString().orEmpty())
            append('\u0000')
            append(attrs.lastModifiedTime())
            append('\u0000')
            append(changeTime.orEmpty())
            append('\u0000')
            append(attrs.size())
            append('\u0000')
            append(attrs.isDirectory)
            append('\u0000')
            append(relative(path))
            append('\u0000')
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return LocalMetadataDigest(digest, reusable = changeTime != null)
    }

    private fun revision(metadataDigest: String, contentDigest: String?): String =
        "$REVISION_PREFIX:$metadataDigest:${contentDigest.orEmpty()}"

    private fun reusableContentDigest(previousRevision: String?, metadata: LocalMetadataDigest): String? {
        if (previousRevision == null || !metadata.reusable) return null
        val fields = previousRevision.split(':')
        if (fields.size != 3 || fields[0] != REVISION_PREFIX || fields[1] != metadata.value) return null
        return fields[2].takeIf { digest ->
            digest.length == SHA256_HEX_LENGTH && digest.all { it in '0'..'9' || it in 'a'..'f' }
        }
    }

    private fun move(source: Path, destination: Path, replace: Boolean) {
        requireSafeAncestors(source, includeLeaf = true, allowMissingTail = false)
        requireSafeAncestors(destination, includeLeaf = false, allowMissingTail = false)
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
        input: InputStream,
        output: OutputStream,
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
        const val REVISION_PREFIX = "desktop-v2"
        const val SHA256_HEX_LENGTH = 64
        const val MAX_ENTRIES = 20_000
        const val MAX_DEPTH = 64
        const val BUFFER_BYTES = 64 * 1024
        const val DOWNLOAD_MARKER = ".nextcloud-native-download-"
        const val BACKUP_MARKER = ".nextcloud-native-backup-"
    }
}

private data class LocalMetadataDigest(val value: String, val reusable: Boolean)

private data class LocalDirectoryIdentity(
    val fileKey: String?,
    val creationTimeMillis: Long,
)

private fun desktopFileChangeToken(path: Path): String? = runCatching {
    Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS).toString()
}.getOrNull()

private fun desktopSha256File(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
        Channels.newInputStream(channel).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private enum class OwnedRecoveryKind { Download, Backup }

private data class OwnedRecoveryPath(
    val kind: OwnedRecoveryKind,
    val destinationName: String,
    val token: String,
)
