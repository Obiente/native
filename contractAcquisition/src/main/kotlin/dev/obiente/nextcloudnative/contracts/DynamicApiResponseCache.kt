package dev.obiente.nextcloudnative.contracts

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

data class CachedDynamicApiResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String?,
    val etag: String?,
)

/**
 * Disposable account-isolated cache for successful authenticated GET responses.
 *
 * Callers provide an already hashed account identity and a canonical request identity. Entries are
 * short lived, individually bounded, atomically written, and globally quota bounded. This cache
 * contains response data, so it belongs in the platform's private cache directory rather than the
 * durable public-contract directory.
 */
class DynamicApiResponseCache(
    private val root: File,
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val freshForMillis: Long = DEFAULT_FRESH_FOR_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maximumBytes in 1..MAXIMUM_ALLOWED_BYTES)
        require(freshForMillis in 1..MAXIMUM_FRESH_FOR_MILLIS)
    }

    @Synchronized
    fun load(accountId: String, requestIdentity: String, maximumResponseBytes: Long): CachedDynamicApiResponse? {
        requireAccountId(accountId)
        requireRequestIdentity(requestIdentity)
        require(maximumResponseBytes > 0L)
        val file = entryFile(accountId, requestIdentity)
        if (!file.isFile || nowMillis() - file.lastModified() !in 0..freshForMillis) return null
        if (file.length() !in 1..minOf(MAXIMUM_ENTRY_BYTES, maximumResponseBytes + MAXIMUM_METADATA_BYTES)) {
            return null
        }
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readInt() == FORMAT_VERSION)
                val status = input.readInt()
                require(status in 200..299)
                val contentType = input.readNullableString()
                val etag = input.readNullableString()
                val bodySize = input.readInt()
                require(bodySize in 0..minOf(MAXIMUM_BODY_BYTES, maximumResponseBytes.toIntSafely()))
                val body = ByteArray(bodySize)
                input.readFully(body)
                require(input.read() == -1)
                file.setLastModified(nowMillis())
                CachedDynamicApiResponse(status, body, contentType, etag)
            }
        } catch (_: EOFException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun store(accountId: String, requestIdentity: String, response: CachedDynamicApiResponse): Boolean {
        requireAccountId(accountId)
        requireRequestIdentity(requestIdentity)
        if (response.status !in 200..299 || response.body.size > MAXIMUM_BODY_BYTES) return false
        requireMetadata(response.contentType)
        requireMetadata(response.etag)
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the dynamic API response cache." }
        }
        val target = entryFile(accountId, requestIdentity)
        val temporary = File.createTempFile("${target.name}.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeInt(response.status)
                    output.writeNullableString(response.contentType)
                    output.writeNullableString(response.etag)
                    output.writeInt(response.body.size)
                    output.write(response.body)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            if (temporary.length() > MAXIMUM_ENTRY_BYTES) return false
            check(!target.exists() || target.delete()) { "Could not replace a cached API response." }
            check(temporary.renameTo(target)) { "Could not publish a cached API response." }
            check(target.setLastModified(nowMillis()))
            prune()
            return target.isFile
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun invalidateAccount(accountId: String) {
        requireAccountId(accountId)
        val directory = accountDirectory(accountId)
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "The dynamic API response cache account path is unsafe."
        }
        val entries = checkNotNull(directory.listFiles()) {
            "Could not read the dynamic API response cache account directory."
        }
        entries.forEach { entry ->
            check(Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry.toPath())) {
                "The dynamic API response cache contains an unsafe entry."
            }
            check(entry.delete() && !Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Could not delete a dynamic API response cache entry."
            }
        }
        check(directory.delete() && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            "Could not delete the dynamic API response cache account directory."
        }
    }

    @Synchronized
    fun invalidate(accountId: String, requestIdentity: String) {
        requireAccountId(accountId)
        requireRequestIdentity(requestIdentity)
        entryFile(accountId, requestIdentity).delete()
        accountDirectory(accountId).takeIf { it.list().isNullOrEmpty() }?.delete()
    }

    private fun prune() {
        val files = root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .flatMap { directory -> directory.listFiles().orEmpty().filter(File::isFile) }
            .sortedByDescending(File::lastModified)
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            val expired = nowMillis() - file.lastModified() !in 0..freshForMillis
            val overQuota = index >= MAXIMUM_ENTRIES || retainedBytes + file.length() > maximumBytes
            if (expired || overQuota) {
                file.delete()
            } else {
                retainedBytes += file.length()
            }
        }
        root.listFiles().orEmpty().filter(File::isDirectory).filter { it.list().isNullOrEmpty() }.forEach(File::delete)
    }

    private fun entryFile(accountId: String, requestIdentity: String): File =
        File(accountDirectory(accountId), "${sha256(requestIdentity)}.bin")

    private fun accountDirectory(accountId: String): File = File(root, accountId)

    private fun requireAccountId(accountId: String) {
        require(accountId.length == 64 && accountId.all { it in "0123456789abcdef" }) {
            "The dynamic API cache account ID must be a SHA-256 digest."
        }
    }

    private fun requireRequestIdentity(identity: String) {
        require(identity.isNotBlank() && identity.length <= MAXIMUM_REQUEST_IDENTITY_CHARS)
        require(identity.none(Char::isISOControl))
    }

    private fun requireMetadata(value: String?) {
        require(value == null || value.length <= MAXIMUM_METADATA_CHARS && value.none(Char::isISOControl))
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        value?.let {
            val bytes = it.encodeToByteArray()
            writeInt(bytes.size)
            write(bytes)
        }
    }

    private fun DataInputStream.readNullableString(): String? {
        if (!readBoolean()) return null
        val size = readInt()
        require(size in 0..MAXIMUM_METADATA_BYTES.toInt())
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.decodeToString().also(::requireMetadata)
    }

    private fun Long.toIntSafely(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val MAGIC = 0x4E434441
        const val FORMAT_VERSION = 1
        const val MAXIMUM_BODY_BYTES = 16 * 1024 * 1024
        const val MAXIMUM_ENTRY_BYTES = MAXIMUM_BODY_BYTES.toLong() + 8 * 1024L
        const val MAXIMUM_METADATA_BYTES = 4 * 1024L
        const val MAXIMUM_METADATA_CHARS = 2 * 1024
        const val MAXIMUM_REQUEST_IDENTITY_CHARS = 16 * 1024
        const val MAXIMUM_ENTRIES = 96
        const val DEFAULT_MAXIMUM_BYTES = 64L * 1024L * 1024L
        const val MAXIMUM_ALLOWED_BYTES = 256L * 1024L * 1024L
        const val DEFAULT_FRESH_FOR_MILLIS = 5L * 60L * 1_000L
        const val MAXIMUM_FRESH_FOR_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
