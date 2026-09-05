package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
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

internal data class AndroidCachedFileListing(
    val files: List<NextcloudFile>,
    val fetchedAtEpochMillis: Long,
)

/**
 * Disposable process-independent folder metadata cache for Android Files.
 *
 * Every account owns a separate bounded index. A complete index is fsynced and atomically
 * published, so a killed refresh leaves either the previous generation or the new one. Corrupt
 * metadata is treated as a cache miss and can never affect offline pin intent or content blobs.
 */
internal class AndroidFileReadCache(
    private val root: File,
    private val maximumListings: Int = DEFAULT_MAXIMUM_LISTINGS,
    private val maximumMetadataEntries: Int = DEFAULT_MAXIMUM_METADATA_ENTRIES,
) {
    init {
        require(maximumListings in 1..MAX_STORED_LISTINGS)
        require(maximumMetadataEntries in 1..MAX_STORED_METADATA_ENTRIES)
    }

    @Synchronized
    fun cachedListing(accountId: String, path: String): AndroidCachedFileListing? {
        val normalized = path.cachePath()
        return load(accountId).listings.firstOrNull { it.path == normalized }?.let {
            AndroidCachedFileListing(it.files, it.fetchedAtEpochMillis)
        }
    }

    @Synchronized
    fun storeListing(
        accountId: String,
        path: String,
        files: List<NextcloudFile>,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        require(nowEpochMillis >= 0L)
        require(files.size <= MAX_FILES_PER_LISTING) { "The folder contains too many cacheable entries." }
        val normalized = path.cachePath()
        files.forEach { file -> requireCacheableFile(file) }
        require(files.map(NextcloudFile::path).distinct().size == files.size) {
            "A folder listing cannot contain duplicate file paths."
        }
        require(files.all { it.path.substringBeforeLast('/', "") == normalized }) {
            "A folder listing cannot cache items outside that folder."
        }
        val current = load(accountId)
        val previous = current.listings.firstOrNull { it.path == normalized }
        val currentByPath = files.associateBy(NextcloudFile::path)
        val invalidatedDirectoryPrefixes = previous?.files.orEmpty()
            .asSequence()
            .filter(NextcloudFile::isDirectory)
            .filter { old ->
                val replacement = currentByPath[old.path]
                replacement == null || !replacement.isDirectory || replacement.etag != old.etag
            }
            .map(NextcloudFile::path)
            .toList()
        val retained = current.listings.filterNot { listing ->
            listing.path == normalized || invalidatedDirectoryPrefixes.any { prefix ->
                listing.path == prefix || listing.path.startsWith("$prefix/")
            }
        }
        save(
            accountId,
            CacheState(
                listOf(CachedListing(normalized, nowEpochMillis, files)) + retained,
            ).bounded(),
        )
    }

    @Synchronized
    fun invalidate(accountId: String, path: String) {
        val normalized = path.cachePath()
        val parent = normalized.substringBeforeLast('/', "")
        val current = load(accountId)
        save(
            accountId,
            current.copy(
                listings = current.listings.filterNot { listing ->
                    normalized.isEmpty() ||
                        listing.path == normalized ||
                        listing.path.startsWith("$normalized/") ||
                        listing.path == parent
                },
            ),
        )
    }

    @Synchronized
    fun clearAccount(accountId: String) = deleteAndroidAccountPrivateCache(root, accountId)

    private fun CacheState.bounded(): CacheState {
        var retainedEntries = 0
        val retained = listings
            .sortedByDescending(CachedListing::fetchedAtEpochMillis)
            .asSequence()
            .filter { listing ->
                if (retainedEntries + listing.files.size > maximumMetadataEntries) {
                    false
                } else {
                    retainedEntries += listing.files.size
                    true
                }
            }
            .take(maximumListings)
            .toList()
        return CacheState(retained)
    }

    private fun load(accountId: String): CacheState {
        val stateFile = stateFile(accountId)
        if (!stateFile.isFile || stateFile.length() !in 1..MAX_STATE_BYTES) return CacheState()
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(stateFile))).use { input ->
                requireStored(input.readInt() == MAGIC) { "Files cache header is invalid." }
                requireStored(input.readInt() == FORMAT_VERSION) { "Files cache version is unsupported." }
                var totalFiles = 0
                val listings = List(input.readCount("listing", MAX_STORED_LISTINGS)) {
                    input.readListing().also { listing ->
                        totalFiles += listing.files.size
                        requireStored(totalFiles <= MAX_STORED_METADATA_ENTRIES) {
                            "Files cache metadata count is invalid."
                        }
                    }
                }
                requireStored(input.read() == -1) { "Files cache contains trailing data." }
                CacheState(listings).requireValid().bounded()
            }
        } catch (_: EOFException) {
            CacheState()
        } catch (_: Exception) {
            CacheState()
        }
    }

    private fun save(accountId: String, state: CacheState) {
        val bounded = state.bounded()
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the Android Files cache." }
        }
        val temporary = File.createTempFile("$STATE_FILE_NAME.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = DataOutputStream(BufferedOutputStream(fileOutput))
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(bounded.listings.size)
                bounded.listings.forEach { listing -> output.writeListing(listing) }
                output.flush()
                fileOutput.fd.sync()
            }
            require(temporary.length() <= MAX_STATE_BYTES) { "The Android Files cache index is too large." }
            publishAtomically(temporary, File(directory, STATE_FILE_NAME))
        } finally {
            temporary.delete()
        }
    }

    private fun DataOutputStream.writeListing(listing: CachedListing) {
        writeString(listing.path)
        writeLong(listing.fetchedAtEpochMillis)
        writeInt(listing.files.size)
        listing.files.forEach { file -> writeFile(file) }
    }

    private fun DataInputStream.readListing(): CachedListing = CachedListing(
        path = readString().cachePath(),
        fetchedAtEpochMillis = readLong().also {
            requireStored(it >= 0L) { "Files cache timestamp is invalid." }
        },
        files = List(readCount("file", MAX_FILES_PER_LISTING)) { readFile() },
    )

    private fun DataOutputStream.writeFile(file: NextcloudFile) {
        writeString(file.path)
        writeString(file.name)
        writeCanonicalBoolean(file.isDirectory)
        writeNullableString(file.mimeType)
        writeNullableLong(file.size)
        writeNullableString(file.lastModified)
        writeNullableLong(file.fileId)
        writeCanonicalBoolean(file.hasPreview)
        writeNullableString(file.etag)
        writeCanonicalBoolean(file.favorite)
        writeNullableString(file.ownerId)
        writeNullableString(file.ownerDisplayName)
        writeInt(file.unreadComments)
        writeCanonicalBoolean(file.originalAccessAllowed)
        writeNullableString(file.permissions)
        writeInt(file.checksums.size)
        file.checksums.forEach { checksum -> writeString(checksum) }
    }

    private fun DataInputStream.readFile(): NextcloudFile = NextcloudFile(
        path = readString(),
        name = readString(),
        isDirectory = readCanonicalBoolean(),
        mimeType = readNullableString(),
        size = readNullableLong(),
        lastModified = readNullableString(),
        fileId = readNullableLong(),
        hasPreview = readCanonicalBoolean(),
        etag = readNullableString(),
        favorite = readCanonicalBoolean(),
        ownerId = readNullableString(),
        ownerDisplayName = readNullableString(),
        unreadComments = readInt().also {
            requireStored(it in 0..MAX_UNREAD_COMMENTS) { "Files cache unread comment count is invalid." }
        },
        originalAccessAllowed = readCanonicalBoolean(),
        permissions = readNullableString(),
        checksums = List(readCount("checksum", MAX_CHECKSUMS_PER_FILE)) { readString() },
    ).also { file -> requireCacheableFile(file) }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeCanonicalBoolean(value != null)
        value?.let { stored -> writeLong(stored) }
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readCanonicalBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeCanonicalBoolean(value != null)
        value?.let { stored -> writeString(stored) }
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readCanonicalBoolean()) readString() else null

    private fun DataOutputStream.writeCanonicalBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    private fun DataInputStream.readCanonicalBoolean(): Boolean = when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Files cache boolean $value is invalid.")
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Files cache field is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        requireStored(length in 0..MAX_STRING_BYTES) { "Files cache field length is invalid." }
        val bytes = ByteArray(length)
        readFully(bytes)
        val value = bytes.toString(StandardCharsets.UTF_8)
        requireStored(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "Files cache text is not canonical UTF-8."
        }
        return value
    }

    private fun DataInputStream.readCount(label: String, maximum: Int): Int = readInt().also {
        requireStored(it in 0..maximum) { "Files cache $label count is invalid." }
    }

    private fun CacheState.requireValid(): CacheState = also { state ->
        requireStored(state.listings.size <= MAX_STORED_LISTINGS) { "Files cache has too many listings." }
        requireStored(state.listings.map(CachedListing::path).distinct().size == state.listings.size) {
            "Files cache contains duplicate listings."
        }
        state.listings.forEach { listing ->
            requireStored(listing.path.cachePath() == listing.path) { "Files cache path is not canonical." }
            requireStored(listing.fetchedAtEpochMillis >= 0L) { "Files cache timestamp is invalid." }
            requireStored(listing.files.size <= MAX_FILES_PER_LISTING) { "Files cache listing is too large." }
            requireStored(listing.files.map(NextcloudFile::path).distinct().size == listing.files.size) {
                "Files cache listing contains duplicate paths."
            }
            listing.files.forEach { file -> requireCacheableFile(file) }
            requireStored(listing.files.all { file ->
                file.path.substringBeforeLast('/', "") == listing.path
            }) { "Files cache listing contains an item outside its folder." }
        }
    }

    private fun requireCacheableFile(file: NextcloudFile) {
        require(file.path.cachePath() == file.path) { "A cached file path is not canonical." }
        require(file.name.isNotBlank() && file.name.none(Char::isISOControl))
        require(file.name.toByteArray().size <= MAX_STRING_BYTES)
        file.size?.let { size -> require(size >= 0L) }
        file.fileId?.let { fileId -> require(fileId >= 0L) }
        require(file.unreadComments in 0..MAX_UNREAD_COMMENTS)
        listOf(
            file.mimeType,
            file.lastModified,
            file.etag,
            file.ownerId,
            file.ownerDisplayName,
            file.permissions,
        ).forEach { field ->
            require(field == null || field.none(Char::isISOControl) && field.toByteArray().size <= MAX_STRING_BYTES)
        }
        require(file.checksums.size <= MAX_CHECKSUMS_PER_FILE)
        file.checksums.forEach { checksum ->
            require(checksum.isNotBlank() && checksum.none(Char::isISOControl))
            require(checksum.toByteArray().size <= MAX_STRING_BYTES)
        }
    }

    private fun stateFile(accountId: String): File = File(accountDirectory(accountId), STATE_FILE_NAME)

    private fun accountDirectory(accountId: String): File {
        require(accountId.length == 32 && accountId.all { it.isLowerHexDigit() }) {
            "Files cache account identity is invalid."
        }
        return File(root, accountId)
    }

    private fun requireStored(condition: Boolean, message: () -> String) {
        if (!condition) throw IllegalArgumentException(message())
    }

    private fun String.cachePath(): String {
        require(length <= MAX_PATH_CHARACTERS)
        require(none { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' })
        val normalized = trim('/')
        if (normalized.isEmpty()) return ""
        require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." })
        return normalized
    }

    private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

    private data class CacheState(val listings: List<CachedListing> = emptyList())

    private data class CachedListing(
        val path: String,
        val fetchedAtEpochMillis: Long,
        val files: List<NextcloudFile>,
    )

    private companion object {
        const val MAGIC = 0x4e43464d // NCFM
        const val FORMAT_VERSION = 2
        const val MAX_UNREAD_COMMENTS = 1_000_000
        const val STATE_FILE_NAME = "listings-v1.bin"
        const val MAX_STATE_BYTES = 16L * 1024L * 1024L
        const val MAX_STORED_LISTINGS = 512
        const val MAX_STORED_METADATA_ENTRIES = 40_000
        const val MAX_FILES_PER_LISTING = 5_000
        const val MAX_CHECKSUMS_PER_FILE = 32
        const val MAX_STRING_BYTES = 16 * 1024
        const val MAX_PATH_CHARACTERS = 8_192
        const val DEFAULT_MAXIMUM_LISTINGS = 256
        const val DEFAULT_MAXIMUM_METADATA_ENTRIES = 20_000
    }
}
