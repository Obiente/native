package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal data class WindowsCloudFileIdentity(
    val accountId: String,
    val path: String,
    val remoteRevision: String,
    val size: Long,
    val directory: Boolean,
    val lastModifiedEpochMillis: Long? = null,
) {
    init {
        require(accountId.isNotBlank() && accountId.length <= MAX_ACCOUNT_ID_LENGTH)
        if (path.isNotEmpty()) FileOfflineKey(accountId, path)
        require(remoteRevision.isNotBlank() && remoteRevision.length <= MAX_REVISION_LENGTH)
        require(size >= 0L)
        require(!directory || size == 0L)
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis >= 0L)
    }

    private companion object {
        const val MAX_ACCOUNT_ID_LENGTH = 256
        const val MAX_REVISION_LENGTH = 1_024
    }
}

/** Versioned, checksummed and strictly bounded identity persisted in Windows placeholders. */
internal object WindowsCloudFileIdentityCodec {
    fun encode(identity: WindowsCloudFileIdentity): ByteArray {
        // Keep the registered root context byte-stable across upgrades. The padded envelope
        // applies to item reparse metadata, not the separate sync-root registration contract.
        val version = if (identity.directory && identity.path.isEmpty() && identity.remoteRevision == "root") 2 else VERSION
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(version)
                output.writeBoolean(identity.directory)
                output.writeLong(identity.size)
                output.writeLong(identity.lastModifiedEpochMillis ?: UNKNOWN_MODIFIED_TIME)
                output.writeBoundedUtf8(identity.accountId, MAX_ACCOUNT_BYTES)
                output.writeBoundedUtf8(identity.path, MAX_PATH_BYTES)
                output.writeBoundedUtf8(identity.remoteRevision, MAX_REVISION_BYTES)
                if (version >= 3) {
                    val padding = (MINIMUM_PAYLOAD_BYTES - bytes.size()).coerceAtLeast(0)
                    output.write(ByteArray(padding))
                }
            }
            bytes.toByteArray()
        }
        require(payload.size + DIGEST_BYTES <= MAX_IDENTITY_BYTES) { "The Windows placeholder identity is too large." }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return payload + digest
    }

    fun decode(bytes: ByteArray): WindowsCloudFileIdentity {
        require(bytes.size in MIN_IDENTITY_BYTES..MAX_IDENTITY_BYTES) {
            "The Windows placeholder identity has an invalid size."
        }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val expectedDigest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(expectedDigest)) {
            "The Windows placeholder identity checksum is invalid."
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "The Windows placeholder identity type is invalid." }
            val version = input.readUnsignedShort()
            require(version in MINIMUM_SUPPORTED_VERSION..VERSION) {
                "The Windows placeholder identity version is unsupported."
            }
            val directory = input.readBoolean()
            val size = input.readLong()
            val lastModifiedEpochMillis = if (version >= 2) {
                input.readLong().takeUnless { it == UNKNOWN_MODIFIED_TIME }
            } else {
                null
            }
            val accountId = input.readBoundedUtf8(MAX_ACCOUNT_BYTES)
            val path = input.readBoundedUtf8(MAX_PATH_BYTES)
            val revision = input.readBoundedUtf8(MAX_REVISION_BYTES)
            if (version >= 3) {
                val consumed = payload.size - input.available()
                val padding = (MINIMUM_PAYLOAD_BYTES - consumed).coerceAtLeast(0)
                require(input.available() == padding) { "The Windows placeholder identity padding has an invalid size." }
                repeat(padding) {
                    require(input.readByte() == 0.toByte()) { "The Windows placeholder identity padding is invalid." }
                }
            }
            require(input.available() == 0) { "The Windows placeholder identity has trailing data." }
            WindowsCloudFileIdentity(accountId, path, revision, size, directory, lastModifiedEpochMillis)
        }
    }

    private fun DataOutputStream.writeBoundedUtf8(value: String, maximumBytes: Int) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= maximumBytes)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedUtf8(maximumBytes: Int): String {
        val length = readUnsignedShort()
        require(length <= maximumBytes && length <= available()) {
            "The Windows placeholder identity field is invalid."
        }
        val bytes = ByteArray(length).also(::readFully)
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private const val MAGIC = 0x4E434656 // NCFV
    private const val VERSION = 3
    // Short directory identities reproduced ERROR_FILE_SYSTEM_VIRTUALIZATION_METADATA_CORRUPT
    // on Windows. A minimum 256-byte envelope passed the real CFAPI regression fixture.
    // This is our compatibility padding, not a documented CFAPI minimum.
    private const val MINIMUM_PAYLOAD_BYTES = 224
    private const val MINIMUM_SUPPORTED_VERSION = 1
    private const val UNKNOWN_MODIFIED_TIME = -1L
    private const val DIGEST_BYTES = 32
    private const val MAX_ACCOUNT_BYTES = 256
    private const val MAX_PATH_BYTES = 3_072
    private const val MAX_REVISION_BYTES = 1_024
    private const val MAX_IDENTITY_BYTES = 4_096
    private const val MIN_IDENTITY_BYTES = 4 + 2 + 1 + 8 + 2 + 2 + 2 + DIGEST_BYTES
}
