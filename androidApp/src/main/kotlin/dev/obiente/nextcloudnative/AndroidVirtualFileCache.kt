package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.VirtualFileActivity
import dev.obiente.nextcloudnative.app.VirtualFileCacheEntry
import dev.obiente.nextcloudnative.app.VirtualFileCachePolicy
import dev.obiente.nextcloudnative.app.VirtualFileEvictionPlan
import dev.obiente.nextcloudnative.app.VirtualFileRetention
import dev.obiente.nextcloudnative.app.planVirtualFileEviction
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class AndroidVirtualFileLease(
    val file: NextcloudFile,
    val content: File,
    val localRevision: String,
    val release: () -> Unit,
)

internal data class AndroidVirtualFileCacheSummary(
    val policy: VirtualFileCachePolicy,
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val entryCount: Int,
    val availableFreeBytes: Long,
    val lastEvictionPlan: VirtualFileEvictionPlan,
)

/**
 * Disposable hydrate-on-open storage for Android's cloud DocumentsProvider.
 *
 * This cache is deliberately separate from durable offline pins. Every blob is content-addressed,
 * fsynced, and paired with the exact remote ETag that produced it. Eviction is revision guarded and
 * refuses active descriptor leases; a process restart may forget leases, but Linux/Android keeps an
 * already-open descriptor readable even if its directory entry is later removed.
 */
internal class AndroidVirtualFileCache(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.cacheDir, CACHE_DIRECTORY)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(STORE_LOCK) { reconcileHydrationStaging() }
    }

    fun acquire(
        session: dev.obiente.nextcloudnative.app.NextcloudSession,
        path: String,
        expectedRemoteEtag: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AndroidVirtualFileLease? = synchronized(STORE_LOCK) {
        require(nowEpochMillis >= 0L)
        val accountId = NextcloudDocumentIds.accountKey(session)
        val key = FileOfflineKey(accountId, path)
        val state = load(accountId)
        val record = state.entries.firstOrNull { entry ->
            entry.path == key.relativePath &&
                (expectedRemoteEtag == null || entry.remoteEtag == expectedRemoteEtag)
        } ?: return null
        val blob = File(accountDirectory(accountId), record.blobName)
        if (!runCatching { record.isValidBlob(blob) }.getOrDefault(false)) {
            removeInvalidRecord(accountId, state, record, blob)
            return null
        }
        val touched = record.copy(lastAccessedAtEpochMillis = maxOf(record.lastAccessedAtEpochMillis, nowEpochMillis))
        save(accountId, state.copy(entries = state.entries.replace(touched)))
        activeLeases[key] = activeLeases.getOrDefault(key, 0) + 1
        var released = false
        return AndroidVirtualFileLease(
            file = touched.toNextcloudFile(),
            content = blob,
            localRevision = touched.localRevision,
            release = {
                synchronized(STORE_LOCK) {
                    if (!released) {
                        released = true
                        val remaining = activeLeases.getOrDefault(key, 1) - 1
                        if (remaining <= 0) activeLeases.remove(key) else activeLeases[key] = remaining
                    }
                }
            },
        )
    }

    fun cachedEntry(
        session: dev.obiente.nextcloudnative.app.NextcloudSession,
        path: String,
    ): NextcloudFile? = synchronized(STORE_LOCK) {
        acquire(session, path)?.let { lease ->
            try {
                lease.file
            } finally {
                lease.release()
            }
        }
    }

    fun createHydrationStagingFile(): File = synchronized(STORE_LOCK) {
        val directory = File(root, STAGING_DIRECTORY).apply {
            check(isDirectory || mkdirs()) { "Could not create virtual file hydration staging." }
        }
        return File.createTempFile("hydrate-", ".part", directory).also { staging ->
            activeHydrationStages += staging.activeHydrationKey()
        }
    }

    fun discardHydrationStagingFile(staging: File) = synchronized(STORE_LOCK) {
        activeHydrationStages -= staging.activeHydrationKey()
        staging.delete()
    }

    fun canCacheHydration(sizeBytes: Long): Boolean = sizeBytes >= 0L

    fun prepareHydration(
        session: dev.obiente.nextcloudnative.app.NextcloudSession,
        sizeBytes: Long,
    ): File? = synchronized(STORE_LOCK) {
        if (!canCacheHydration(sizeBytes)) return null
        check(root.isDirectory || root.mkdirs()) { "Could not create virtual file cache storage." }
        val policy = loadPolicy()
        val availableBefore = root.usableSpace.coerceAtLeast(0L)
        val requiredBeforeHydration = if (sizeBytes > Long.MAX_VALUE - policy.minimumFreeSpaceBytes) {
            Long.MAX_VALUE
        } else {
            sizeBytes + policy.minimumFreeSpaceBytes
        }
        val requestedBytes = (requiredBeforeHydration - availableBefore).coerceAtLeast(0L)
        if (requestedBytes > 0L && policy.automaticCleanup) {
            applyEviction(NextcloudDocumentIds.accountKey(session), requestedBytes)
        }
        return if (
            androidHydrationFitsCapacity(
                sizeBytes = sizeBytes,
                availableBytes = root.usableSpace.coerceAtLeast(0L),
                reserveBytes = policy.minimumFreeSpaceBytes,
            )
        ) {
            createHydrationStagingFile()
        } else {
            null
        }
    }

    fun publishHydration(
        session: dev.obiente.nextcloudnative.app.NextcloudSession,
        file: NextcloudFile,
        staging: File,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(STORE_LOCK) {
        require(!file.isDirectory)
        require(nowEpochMillis >= 0L)
        val remoteEtag = file.etag?.takeIf(String::isNotBlank) ?: return false
        if (!staging.isFile) return false
        val accountId = NextcloudDocumentIds.accountKey(session)
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the Android virtual file cache." }
        }
        var current = load(accountId)
        if (current.entries.none { it.path == file.path } && current.entries.size >= MAX_ENTRIES) {
            val eviction = current.entries
                .asSequence()
                .filter { cached ->
                    activeLeases.getOrDefault(FileOfflineKey(accountId, cached.path), 0) == 0
                }
                .minWithOrNull(compareBy<CachedVirtualFile> { it.lastAccessedAtEpochMillis }.thenBy { it.path })
                ?: return false
            current = current.copy(entries = current.entries - eviction)
            save(accountId, current)
        }
        val digest = staging.sha256Hex()
        val localRevision = "sha256:$digest"
        val blobName = "${sha256Hex("${file.path}\u0000$remoteEtag")}.blob"
        val destination = File(directory, blobName)
        publishAtomically(staging, destination)
        activeHydrationStages -= staging.activeHydrationKey()
        val next = current.copy(
            entries = current.entries.filterNot { it.path == file.path } + CachedVirtualFile(
                path = file.path,
                displayName = file.name,
                remoteEtag = remoteEtag,
                localRevision = localRevision,
                mimeType = file.mimeType,
                sizeBytes = destination.length(),
                blobName = blobName,
                cachedAtEpochMillis = nowEpochMillis,
                lastAccessedAtEpochMillis = nowEpochMillis,
            ),
        )
        try {
            save(accountId, next)
        } catch (failure: Throwable) {
            if (current.entries.none { it.blobName == blobName }) destination.delete()
            throw failure
        }
        applyEviction(accountId, requestedBytesToFree = 0L, nowEpochMillis = nowEpochMillis)
        return load(accountId).entries.any { it.path == file.path && it.localRevision == localRevision }
    }

    fun summary(
        session: dev.obiente.nextcloudnative.app.NextcloudSession,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AndroidVirtualFileCacheSummary = synchronized(STORE_LOCK) {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val entries = load(accountId).entries.toDomain(accountId)
        val plan = planVirtualFileEviction(
            entries = entries,
            policy = loadPolicy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = nowEpochMillis,
        )
        return AndroidVirtualFileCacheSummary(
            policy = loadPolicy(),
            cachedBytes = plan.cachedBytes,
            reclaimableBytes = plan.reclaimableBytes,
            entryCount = entries.size,
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            lastEvictionPlan = plan,
        )
    }

    fun savePolicy(policy: VirtualFileCachePolicy) = synchronized(STORE_LOCK) {
        preferences.edit()
            .putBoolean(KEY_AUTOMATIC, policy.automaticCleanup)
            .putLong(KEY_MAXIMUM_BYTES, policy.maximumCacheBytes ?: UNLIMITED_SENTINEL)
            .putLong(KEY_MINIMUM_FREE_BYTES, policy.minimumFreeSpaceBytes)
            .putLong(KEY_UNUSED_AGE, policy.unusedFileAgeMillis ?: UNLIMITED_SENTINEL)
            .apply()
        root.listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            if (directory.name.isAccountId()) applyEviction(directory.name, requestedBytesToFree = 0L)
        }
    }

    fun freeUp(
        session: dev.obiente.nextcloudnative.app.NextcloudSession,
        requestedBytesToFree: Long,
    ): VirtualFileEvictionPlan = synchronized(STORE_LOCK) {
        require(requestedBytesToFree >= 0L)
        return applyEviction(NextcloudDocumentIds.accountKey(session), requestedBytesToFree)
    }

    fun invalidate(
        session: dev.obiente.nextcloudnative.app.NextcloudSession,
        path: String,
    ) = synchronized(STORE_LOCK) {
        val accountId = NextcloudDocumentIds.accountKey(session)
        val normalized = FileOfflineKey(accountId, path).relativePath
        val current = load(accountId)
        val removed = current.entries.filter { entry ->
            entry.path == normalized || entry.path.startsWith("$normalized/")
        }
        removed.forEach { entry -> File(accountDirectory(accountId), entry.blobName).delete() }
        if (removed.isNotEmpty()) {
            save(accountId, current.copy(entries = current.entries.filterNot { it in removed }))
        }
    }

    fun clearAccount(accountId: String) = synchronized(STORE_LOCK) {
        deleteAndroidAccountPrivateCache(root, accountId)
    }

    fun loadPolicy(): VirtualFileCachePolicy = VirtualFileCachePolicy(
        automaticCleanup = preferences.getBoolean(KEY_AUTOMATIC, true),
        maximumCacheBytes = preferences.getLong(
            KEY_MAXIMUM_BYTES,
            dev.obiente.nextcloudnative.app.DEFAULT_VIRTUAL_FILE_CACHE_BYTES,
        ).optionalPositiveOrDefault(dev.obiente.nextcloudnative.app.DEFAULT_VIRTUAL_FILE_CACHE_BYTES),
        minimumFreeSpaceBytes = preferences.getLong(
            KEY_MINIMUM_FREE_BYTES,
            dev.obiente.nextcloudnative.app.DEFAULT_VIRTUAL_FILE_MINIMUM_FREE_BYTES,
        ).coerceAtLeast(0L),
        unusedFileAgeMillis = preferences.getLong(
            KEY_UNUSED_AGE,
            dev.obiente.nextcloudnative.app.DEFAULT_VIRTUAL_FILE_UNUSED_AGE_MILLIS,
        ).optionalPositiveOrDefault(dev.obiente.nextcloudnative.app.DEFAULT_VIRTUAL_FILE_UNUSED_AGE_MILLIS),
    )

    private fun Long.optionalPositiveOrDefault(defaultValue: Long): Long? = when {
        this == UNLIMITED_SENTINEL -> null
        this > 0L -> this
        else -> defaultValue
    }

    private fun applyEviction(
        accountId: String,
        requestedBytesToFree: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): VirtualFileEvictionPlan {
        val current = load(accountId)
        val domain = current.entries.toDomain(accountId)
        val plan = planVirtualFileEviction(
            entries = domain,
            policy = loadPolicy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = nowEpochMillis,
            requestedBytesToFree = requestedBytesToFree,
        )
        val byPath = current.entries.associateBy(CachedVirtualFile::path)
        val removedPaths = plan.evictions.mapNotNullTo(mutableSetOf()) { eviction ->
            val currentRecord = byPath[eviction.key.relativePath] ?: return@mapNotNullTo null
            val active = activeLeases.getOrDefault(eviction.key, 0)
            if (active != 0 || currentRecord.localRevision != eviction.expectedLocalRevision) {
                return@mapNotNullTo null
            }
            val blob = File(accountDirectory(accountId), currentRecord.blobName)
            if (!blob.exists() || blob.delete()) currentRecord.path else null
        }
        if (removedPaths.isNotEmpty()) {
            save(accountId, current.copy(entries = current.entries.filterNot { it.path in removedPaths }))
        }
        return plan
    }

    private fun removeInvalidRecord(accountId: String, state: CacheState, record: CachedVirtualFile, blob: File) {
        blob.delete()
        save(accountId, state.copy(entries = state.entries.filterNot { it.path == record.path }))
    }

    private fun load(accountId: String): CacheState {
        val index = File(accountDirectory(accountId), INDEX_FILE_NAME)
        if (!index.isFile || index.length() !in 1L..MAX_INDEX_BYTES) return CacheState()
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(index))).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == FORMAT_VERSION)
                val count = input.readInt()
                require(count in 0..MAX_ENTRIES)
                val entries = List(count) { input.readRecord() }
                require(input.read() == -1)
                require(entries.map(CachedVirtualFile::path).distinct().size == entries.size)
                CacheState(entries)
            }
        } catch (_: EOFException) {
            CacheState()
        } catch (_: Exception) {
            CacheState()
        }
    }

    private fun save(accountId: String, state: CacheState) {
        require(state.entries.size <= MAX_ENTRIES)
        state.entries.forEach(CachedVirtualFile::requireValid)
        require(state.entries.map(CachedVirtualFile::path).distinct().size == state.entries.size)
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the Android virtual file cache." }
        }
        val temporary = File.createTempFile("index-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeInt(state.entries.size)
                    state.entries.sortedBy(CachedVirtualFile::path).forEach { record ->
                        output.writeRecord(record)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            require(temporary.length() <= MAX_INDEX_BYTES)
            publishAtomically(temporary, File(directory, INDEX_FILE_NAME))
        } finally {
            temporary.delete()
        }
        val referenced = state.entries.mapTo(hashSetOf(), CachedVirtualFile::blobName)
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "blob" && it.name !in referenced }
            .forEach(File::delete)
    }

    private fun DataOutputStream.writeRecord(record: CachedVirtualFile) {
        writeString(record.path)
        writeString(record.displayName)
        writeString(record.remoteEtag)
        writeString(record.localRevision)
        writeNullableString(record.mimeType)
        writeLong(record.sizeBytes)
        writeString(record.blobName)
        writeLong(record.cachedAtEpochMillis)
        writeLong(record.lastAccessedAtEpochMillis)
    }

    private fun DataInputStream.readRecord(): CachedVirtualFile = CachedVirtualFile(
        path = readString(),
        displayName = readString(),
        remoteEtag = readString(),
        localRevision = readString(),
        mimeType = readNullableString(),
        sizeBytes = readLong(),
        blobName = readString(),
        cachedAtEpochMillis = readLong(),
        lastAccessedAtEpochMillis = readLong(),
    ).also(CachedVirtualFile::requireValid)

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(StandardCharsets.UTF_8).also { decoded ->
            require(decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes))
        }
    }

    private fun CachedVirtualFile.isValidBlob(file: File): Boolean =
        file.isFile &&
            file.length() == sizeBytes &&
            localRevision == "sha256:${file.sha256Hex()}"

    private fun CachedVirtualFile.toNextcloudFile(): NextcloudFile = NextcloudFile(
        path = path,
        name = displayName,
        isDirectory = false,
        mimeType = mimeType,
        size = sizeBytes,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        etag = remoteEtag,
    )

    private fun List<CachedVirtualFile>.toDomain(accountId: String): List<VirtualFileCacheEntry> = map { record ->
        val key = FileOfflineKey(accountId, record.path)
        VirtualFileCacheEntry(
            key = key,
            remoteRevision = record.remoteEtag,
            localRevision = record.localRevision,
            sizeBytes = record.sizeBytes,
            cachedAtEpochMillis = record.cachedAtEpochMillis,
            lastAccessedAtEpochMillis = record.lastAccessedAtEpochMillis,
            retention = VirtualFileRetention.Automatic,
            activeLeaseCount = activeLeases.getOrDefault(key, 0),
            activity = VirtualFileActivity.Idle,
        )
    }

    private fun List<CachedVirtualFile>.replace(record: CachedVirtualFile): List<CachedVirtualFile> =
        filterNot { it.path == record.path } + record

    private fun accountDirectory(accountId: String): File {
        require(accountId.isAccountId()) { "Virtual file cache account identity is invalid." }
        return File(root, accountId)
    }

    private fun reconcileHydrationStaging() {
        val directory = File(root, STAGING_DIRECTORY)
        if (!directory.isDirectory) return
        directory.listFiles().orEmpty()
            .filter { staging ->
                staging.isFile &&
                    staging.name.startsWith("hydrate-") &&
                    staging.name.endsWith(".part") &&
                    staging.activeHydrationKey() !in activeHydrationStages
            }
            .forEach(File::delete)
    }

    private fun File.activeHydrationKey(): String = absoluteFile.normalize().path

    private fun String.isAccountId(): Boolean = length == 32 && all { it in '0'..'9' || it in 'a'..'f' }

    private fun File.sha256Hex(): String = inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class CacheState(val entries: List<CachedVirtualFile> = emptyList())

    private data class CachedVirtualFile(
        val path: String,
        val displayName: String,
        val remoteEtag: String,
        val localRevision: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val blobName: String,
        val cachedAtEpochMillis: Long,
        val lastAccessedAtEpochMillis: Long,
    ) {
        fun requireValid() {
            FileOfflineKey("00000000000000000000000000000000", path)
            require(displayName.isNotBlank() && displayName.toByteArray().size <= MAX_STRING_BYTES)
            require(remoteEtag.isNotBlank() && remoteEtag.toByteArray().size <= MAX_STRING_BYTES)
            require(localRevision.startsWith("sha256:") && localRevision.length == 71)
            require(localRevision.removePrefix("sha256:").all { it in '0'..'9' || it in 'a'..'f' })
            require(mimeType == null || mimeType.toByteArray().size <= MAX_STRING_BYTES)
            require(sizeBytes >= 0L)
            require(blobName.length == 69 && blobName.endsWith(".blob"))
            require(blobName.removeSuffix(".blob").all { it in '0'..'9' || it in 'a'..'f' })
            require(cachedAtEpochMillis >= 0L)
            require(lastAccessedAtEpochMillis >= cachedAtEpochMillis)
        }
    }

    private companion object {
        val STORE_LOCK = Any()
        val activeLeases = mutableMapOf<FileOfflineKey, Int>()
        val activeHydrationStages = mutableSetOf<String>()
        const val CACHE_DIRECTORY = "virtual-files-v1"
        const val STAGING_DIRECTORY = "staging"
        const val INDEX_FILE_NAME = "index-v1.bin"
        const val PREFERENCES_NAME = "virtual-file-cache-v1"
        const val KEY_AUTOMATIC = "automatic"
        const val KEY_MAXIMUM_BYTES = "maximum-bytes"
        const val KEY_MINIMUM_FREE_BYTES = "minimum-free-bytes"
        const val KEY_UNUSED_AGE = "unused-age"
        const val UNLIMITED_SENTINEL = -1L
        const val MAGIC = 0x4e435646 // NCVF
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 20_000
        const val MAX_INDEX_BYTES = 8L * 1024L * 1024L
        const val MAX_STRING_BYTES = 16 * 1024
    }
}

internal fun androidHydrationFitsCapacity(
    sizeBytes: Long,
    availableBytes: Long,
    reserveBytes: Long,
): Boolean =
    sizeBytes >= 0L &&
        availableBytes >= 0L &&
        reserveBytes >= 0L &&
        availableBytes >= sizeBytes &&
        availableBytes - sizeBytes >= reserveBytes
