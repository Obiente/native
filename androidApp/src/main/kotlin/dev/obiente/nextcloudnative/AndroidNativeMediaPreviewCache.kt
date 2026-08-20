package dev.obiente.nextcloudnative

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class NativeMediaPreviewCacheKey(
    val accountId: String,
    val fileId: Long,
    val etag: String,
    val maximumDimension: Int,
    val decoderVersion: String,
) {
    init {
        require(accountId.matches(Regex("[0-9a-f]{64}")))
        require(fileId > 0L)
        require(etag.isNotBlank())
        require(maximumDimension > 0)
        require(decoderVersion.isNotBlank())
    }

    fun digest(): String = sha256Hex(
        listOf(accountId, fileId.toString(), etag, maximumDimension.toString(), decoderVersion)
            .joinToString(separator = "\n")
            .encodeToByteArray(),
    )
}

internal class AndroidNativeMediaPreviewCache(
    root: File,
    private val maximumBytes: Long = DEFAULT_NATIVE_MEDIA_PREVIEW_CACHE_BYTES,
) {
    private val root = root.canonicalFile

    init {
        require(maximumBytes > 0L)
        synchronized(CACHE_LOCK) {
            check(this.root.isDirectory || this.root.mkdirs()) {
                "Could not create the native media preview cache."
            }
            cleanupTemporaryFiles()
        }
    }

    fun load(key: NativeMediaPreviewCacheKey): ByteArray? = synchronized(CACHE_LOCK) {
        val file = cacheFile(key)
        if (!file.isFile || file.length() !in 1L..MAXIMUM_NATIVE_MEDIA_PREVIEW_ENTRY_BYTES) {
            file.delete()
            return@synchronized null
        }
        runCatching {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        }.getOrNull()
    }

    fun accountGeneration(accountId: String): Long = synchronized(CACHE_LOCK) {
        requireAccountId(accountId)
        ACCOUNT_GENERATIONS[generationKey(accountId)] ?: 0L
    }

    fun store(
        key: NativeMediaPreviewCacheKey,
        bytes: ByteArray,
        expectedAccountGeneration: Long,
    ): Boolean = synchronized(CACHE_LOCK) {
        require(bytes.size.toLong() in 1L..MAXIMUM_NATIVE_MEDIA_PREVIEW_ENTRY_BYTES)
        require(expectedAccountGeneration >= 0L)
        if ((ACCOUNT_GENERATIONS[generationKey(key.accountId)] ?: 0L) != expectedAccountGeneration) {
            return@synchronized false
        }
        val destination = cacheFile(key)
        val accountDirectory = requireNotNull(destination.parentFile)
        check(accountDirectory.isDirectory || accountDirectory.mkdirs()) {
            "Could not create the account preview cache."
        }
        prune(requiredBytes = bytes.size.toLong(), protected = destination)
        val temporary = File(accountDirectory, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.length() == bytes.size.toLong()) {
                "The generated media preview cache entry is incomplete."
            }
            publishAtomically(temporary, destination)
            destination.setLastModified(System.currentTimeMillis())
        } finally {
            temporary.delete()
        }
        true
    }

    fun clearAccount(accountId: String) {
        synchronized(CACHE_LOCK) {
            requireAccountId(accountId)
            val generationKey = generationKey(accountId)
            ACCOUNT_GENERATIONS[generationKey] = Math.addExact(
                ACCOUNT_GENERATIONS[generationKey] ?: 0L,
                1L,
            )
            val accountDirectory = File(root, accountId).canonicalFile
            check(accountDirectory.parentFile == root) {
                "Unsafe native media preview account cache."
            }
            if (accountDirectory.exists()) {
                check(accountDirectory.deleteRecursively()) {
                    "Could not clear the account preview cache."
                }
            }
        }
    }

    private fun cacheFile(key: NativeMediaPreviewCacheKey): File {
        val accountDirectory = File(root, key.accountId).canonicalFile
        check(accountDirectory.parentFile == root) {
            "Unsafe native media preview account cache."
        }
        val destination = File(accountDirectory, "${key.digest()}.jpg").canonicalFile
        check(destination.parentFile == accountDirectory) {
            "Unsafe native media preview cache key."
        }
        return destination
    }

    private fun generationKey(accountId: String): String = "${root.path}\n$accountId"

    private fun requireAccountId(accountId: String) {
        require(accountId.matches(ACCOUNT_ID_PATTERN))
    }

    private fun prune(requiredBytes: Long, protected: File) {
        cleanupTemporaryFiles()
        val entries = root.walkTopDown()
            .filter(File::isFile)
            .filterNot { it == protected }
            .sortedBy(File::lastModified)
            .toMutableList()
        var storedBytes = entries.sumOf(File::length)
        val iterator = entries.iterator()
        while (storedBytes + requiredBytes > maximumBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            val length = oldest.length()
            if (oldest.delete()) {
                storedBytes = (storedBytes - length).coerceAtLeast(0L)
                oldest.parentFile?.takeIf { it != root && it.list().isNullOrEmpty() }?.delete()
            }
        }
        check(storedBytes + requiredBytes <= maximumBytes) {
            "The native media preview cache has no room for this bounded entry."
        }
    }

    private fun cleanupTemporaryFiles() {
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .forEach(File::delete)
    }

    private companion object {
        val CACHE_LOCK = Any()
        val ACCOUNT_GENERATIONS = mutableMapOf<String, Long>()
        val ACCOUNT_ID_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val DEFAULT_NATIVE_MEDIA_PREVIEW_CACHE_BYTES = 192L * 1024L * 1024L
private const val MAXIMUM_NATIVE_MEDIA_PREVIEW_ENTRY_BYTES = 12L * 1024L * 1024L
