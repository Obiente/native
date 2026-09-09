package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class DesktopLinuxPendingWriteback(
    val path: String,
    val expectedRemoteRevision: String?,
    val stagedBytes: Long,
    val stagedAtEpochMillis: Long,
    val dirty: Boolean,
)

internal data class DesktopLinuxWritebackRecoveryResult(
    val recoveredCount: Int,
    val retainedCount: Int,
)

internal interface LinuxVirtualWritebackRemote {
    fun resolveFile(relativePath: String): RemoteSyncEntry?

    fun stageDownload(
        relativePath: String,
        expectedRemoteEtag: String,
        destination: File,
        maximumBytes: Long,
    ): RemoteSyncEntry

    fun writeFile(relativePath: String, source: File, expectedRemoteEtag: String?): RemoteSyncEntry
}

/** Durable local staging for editable Linux virtual files. */
internal class DesktopLinuxVirtualFileWritebackStore(
    private val root: File,
    private val minimumFreeSpaceBytes: () -> Long = { DEFAULT_VIRTUAL_FILE_MINIMUM_FREE_BYTES },
    private val afterDirtyIntentPersisted: () -> Unit = {},
) {
    @Synchronized
    fun open(
        path: String,
        existing: LinuxVirtualFileNode?,
        truncate: Boolean,
        tree: LinuxVirtualWritebackRemote,
        onCommitted: (String) -> Unit,
    ): LinuxVirtualFileWriteHandle {
        require(path.isNotBlank())
        require(existing == null || !existing.directory)
        require(existing == null || existing.path == path)
        val directory = root.apply {
            check(isDirectory || mkdirs()) { "Could not create Linux virtual-file recovery storage." }
        }
        if (existing != null && !truncate) {
            require(existing.size <= MAX_WRITEBACK_BYTES) {
                "The Linux virtual file is too large for editable staging."
            }
            require(
                linuxWritebackFitsCapacity(
                    remoteBytes = existing.size,
                    availableBytes = directory.usableSpace.coerceAtLeast(0L),
                    reserveBytes = minimumFreeSpaceBytes(),
                ),
            ) { "There is not enough free space to stage this Linux virtual-file edit safely." }
        }
        val stage = File.createTempFile("writeback-", ".stage", directory)
        val manifestFile = File(directory, stage.name + ".json")
        val stagedAt = System.currentTimeMillis()
        var expectedRevision = existing?.remoteRevision
        var dirty = existing == null || truncate
        try {
            if (existing != null && !truncate) {
                tree.stageDownload(
                    relativePath = path,
                    expectedRemoteEtag = existing.remoteRevision,
                    destination = stage,
                    maximumBytes = MAX_WRITEBACK_BYTES,
                )
            }
            val random = RandomAccessFile(stage, "rw")
            try {
                saveManifest(
                    manifestFile,
                    WritebackManifest(
                        path = path,
                        expectedRemoteRevision = expectedRevision,
                        stagedAtEpochMillis = stagedAt,
                        dirty = dirty,
                        stageName = stage.name,
                        committed = if (dirty) null else false,
                    ),
                )
                if (truncate) {
                    afterDirtyIntentPersisted()
                    random.setLength(0L)
                }
            } catch (failure: Throwable) {
                runCatching(random::close)
                throw failure
            }
            return object : LinuxVirtualFileWriteHandle {
                private var closed = false

                override val size: Long
                    @Synchronized get() = random.length()

                @Synchronized
                override fun read(offset: Long, length: Int): ByteArray {
                    check(!closed)
                    require(offset >= 0L && length > 0 && offset + length <= random.length())
                    return ByteArray(length).also { bytes ->
                        random.seek(offset)
                        random.readFully(bytes)
                    }
                }

                @Synchronized
                override fun write(offset: Long, bytes: ByteArray): Int {
                    check(!closed)
                    require(offset >= 0L && bytes.isNotEmpty())
                    val end = runCatching { Math.addExact(offset, bytes.size.toLong()) }
                        .getOrElse { throw IllegalArgumentException("The Linux virtual file exceeds the writeback limit.") }
                    require(end <= MAX_WRITEBACK_BYTES)
                    requireGrowthCapacity(end)
                    markDirtyBeforeMutation()
                    requireGrowthCapacity(end)
                    random.seek(offset)
                    random.write(bytes)
                    return bytes.size
                }

                @Synchronized
                override fun truncate(size: Long) {
                    check(!closed)
                    require(size in 0L..MAX_WRITEBACK_BYTES)
                    requireGrowthCapacity(size)
                    markDirtyBeforeMutation()
                    requireGrowthCapacity(size)
                    random.setLength(size)
                }

                private fun requireGrowthCapacity(targetBytes: Long) {
                    require(
                        linuxWritebackGrowthFitsCapacity(
                            currentBytes = random.length(),
                            targetBytes = targetBytes,
                            availableBytes = directory.usableSpace.coerceAtLeast(0L),
                            reserveBytes = minimumFreeSpaceBytes(),
                        ),
                    ) { "There is not enough free space to grow this Linux virtual-file edit safely." }
                }

                private fun markDirtyBeforeMutation() {
                    if (dirty) return
                    saveManifest(
                        manifestFile,
                        WritebackManifest(
                            path = path,
                            expectedRemoteRevision = expectedRevision,
                            stagedAtEpochMillis = stagedAt,
                            dirty = true,
                            stageName = stage.name,
                            committed = null,
                        ),
                    )
                    dirty = true
                    afterDirtyIntentPersisted()
                }

                @Synchronized
                override fun flush() {
                    check(!closed)
                    if (!dirty) return
                    random.fd.sync()
                    val uploaded = tree.writeFile(path, stage, expectedRevision)
                    expectedRevision = uploaded.etag
                    dirty = false
                    saveManifest(
                        manifestFile,
                        WritebackManifest(
                            path = path,
                            expectedRemoteRevision = expectedRevision,
                            stagedAtEpochMillis = stagedAt,
                            dirty = false,
                            stageName = stage.name,
                            committed = true,
                        ),
                    )
                    onCommitted(path)
                }

                @Synchronized
                override fun close() {
                    if (closed) return
                    var failure: Throwable? = null
                    if (dirty) runCatching(::flush).onFailure { failure = it }
                    closed = true
                    runCatching(random::close)
                    if (!dirty && failure == null) {
                        manifestFile.delete()
                        stage.delete()
                    }
                    failure?.let { throw it }
                }
            }
        } catch (failure: Throwable) {
            if (!manifestFile.exists()) stage.delete()
            throw failure
        }
    }

    @Synchronized
    fun pendingWritebacks(): List<DesktopLinuxPendingWriteback> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".stage.json") }
            .mapNotNull { manifestFile ->
                val manifest = runCatching {
                    writebackJson.decodeFromString<WritebackManifest>(manifestFile.readText()).also { it.requireValid() }
                }.getOrNull() ?: return@mapNotNull null
                val stage = File(root, manifest.stageName)
                if (!stage.isFile) return@mapNotNull null
                DesktopLinuxPendingWriteback(
                    path = manifest.path,
                    expectedRemoteRevision = manifest.expectedRemoteRevision,
                    stagedBytes = stage.length(),
                    stagedAtEpochMillis = manifest.stagedAtEpochMillis,
                    dirty = manifest.dirty,
                )
            }.sortedBy(DesktopLinuxPendingWriteback::stagedAtEpochMillis)
    }

    @Synchronized
    fun recoverPending(
        tree: LinuxVirtualWritebackRemote,
        onCommitted: (String) -> Unit,
    ): DesktopLinuxWritebackRecoveryResult {
        var recovered = 0
        var retained = 0
        pendingWritebacks().forEach { pending ->
            val manifestFile = root.listFiles().orEmpty().firstOrNull { candidate ->
                if (!candidate.name.endsWith(".stage.json")) return@firstOrNull false
                runCatching {
                    val manifest = writebackJson.decodeFromString<WritebackManifest>(candidate.readText())
                    manifest.path == pending.path && manifest.stagedAtEpochMillis == pending.stagedAtEpochMillis
                }.getOrDefault(false)
            } ?: return@forEach
            val manifest = runCatching {
                writebackJson.decodeFromString<WritebackManifest>(manifestFile.readText()).also { it.requireValid() }
            }.getOrNull() ?: return@forEach
            val stage = File(root, manifest.stageName)
            if (!stage.isFile) return@forEach
            if (!manifest.dirty) {
                // Missing values are legacy clean manifests, whose pre- and post-upload states
                // were indistinguishable. Preserve their conservative invalidation behavior.
                if (manifest.committed != false) onCommitted(manifest.path)
                manifestFile.delete()
                stage.delete()
                recovered += 1
                return@forEach
            }
            val recoveredRemote = matchingRemoteGeneration(tree, manifest.path, stage)
            if (recoveredRemote != null) {
                onCommitted(manifest.path)
                manifestFile.delete()
                stage.delete()
                recovered += 1
                return@forEach
            }
            runCatching { tree.writeFile(manifest.path, stage, manifest.expectedRemoteRevision) }
                .recoverCatching { failure ->
                    matchingRemoteGeneration(tree, manifest.path, stage) ?: throw failure
                }
                .onSuccess {
                    onCommitted(manifest.path)
                    manifestFile.delete()
                    stage.delete()
                    recovered += 1
                }
                .onFailure { retained += 1 }
        }
        return DesktopLinuxWritebackRecoveryResult(recovered, retained)
    }

    private fun matchingRemoteGeneration(
        tree: LinuxVirtualWritebackRemote,
        path: String,
        stage: File,
    ): RemoteSyncEntry? {
        val remote = tree.resolveFile(path)
            ?.takeIf { it.kind == SyncEntryKind.File && it.size == stage.length() }
            ?: return null
        val downloaded = File.createTempFile("reconcile-", ".stage", root)
        return try {
            val verified = tree.stageDownload(path, remote.etag, downloaded, MAX_WRITEBACK_BYTES)
            verified.takeIf {
                it.etag == remote.etag &&
                    downloaded.length() == stage.length() &&
                    Files.mismatch(downloaded.toPath(), stage.toPath()) == -1L
            }
        } finally {
            downloaded.delete()
        }
    }

    private fun saveManifest(destination: File, manifest: WritebackManifest) {
        manifest.requireValid()
        val bytes = writebackJson.encodeToString(manifest).encodeToByteArray()
        require(bytes.size <= MAX_MANIFEST_BYTES)
        val temporary = File.createTempFile("manifest-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (System.getProperty("os.name").equals("Linux", ignoreCase = true)) {
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { directory ->
                    directory.force(true)
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val MAX_WRITEBACK_BYTES = Long.MAX_VALUE
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}

internal fun linuxWritebackFitsCapacity(
    remoteBytes: Long,
    availableBytes: Long,
    reserveBytes: Long = DEFAULT_VIRTUAL_FILE_MINIMUM_FREE_BYTES,
): Boolean =
    remoteBytes >= 0L &&
        availableBytes >= 0L &&
        reserveBytes >= 0L &&
        availableBytes >= remoteBytes &&
        availableBytes - remoteBytes >= reserveBytes

internal fun linuxWritebackGrowthFitsCapacity(
    currentBytes: Long,
    targetBytes: Long,
    availableBytes: Long,
    reserveBytes: Long = DEFAULT_VIRTUAL_FILE_MINIMUM_FREE_BYTES,
): Boolean {
    if (currentBytes < 0L || targetBytes < 0L || availableBytes < 0L || reserveBytes < 0L) return false
    val growth = (targetBytes - currentBytes).coerceAtLeast(0L)
    return availableBytes >= growth && availableBytes - growth >= reserveBytes
}

internal fun defaultDesktopLinuxWritebackStore(session: NextcloudSession): DesktopLinuxVirtualFileWritebackStore {
    val xdgData = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
    val dataRoot = xdgData?.let(::File) ?: File(System.getProperty("user.home"), ".local/share")
    return DesktopLinuxVirtualFileWritebackStore(
        File(dataRoot, "nextcloud-native/vfs-writeback/${desktopFileCacheAccountId(session)}"),
    )
}

@Serializable
private data class WritebackManifest(
    val path: String,
    val expectedRemoteRevision: String?,
    val stagedAtEpochMillis: Long,
    val dirty: Boolean,
    val stageName: String,
    val committed: Boolean? = null,
) {
    fun requireValid() {
        FileOfflineKey("account", path)
        require(expectedRemoteRevision == null || expectedRemoteRevision.isNotBlank())
        require(stagedAtEpochMillis >= 0L)
        require(stageName.startsWith("writeback-") && stageName.endsWith(".stage"))
        require('/' !in stageName && '\\' !in stageName)
    }
}

private val writebackJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
