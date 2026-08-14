package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class AndroidExternalFileHandoffRecord(
    val documentId: String,
    val accountId: String,
    val userId: String,
    val file: NextcloudFile,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

internal class AndroidExternalFileHandoffLease internal constructor(
    val record: AndroidExternalFileHandoffRecord,
    private val onRelease: (AndroidExternalFileHandoffLease) -> Unit,
) {
    private val released = AtomicBoolean(false)
    private val callbackLock = Any()
    private var revoked = false
    private val revocationCallbacks = mutableListOf<() -> Unit>()

    fun isValid(): Boolean = synchronized(callbackLock) { !revoked && !released.get() }

    fun onRevoked(callback: () -> Unit) {
        val invokeImmediately = synchronized(callbackLock) {
            if (revoked || released.get()) {
                true
            } else {
                revocationCallbacks += callback
                false
            }
        }
        if (invokeImmediately) callback()
    }

    fun release() {
        if (released.compareAndSet(false, true)) onRelease(this)
    }

    internal fun revoke() {
        val callbacks = synchronized(callbackLock) {
            if (revoked) return@synchronized emptyList()
            revoked = true
            revocationCallbacks.toList().also { revocationCallbacks.clear() }
        }
        callbacks.forEach { callback -> runCatching(callback) }
    }
}

/**
 * Process-private authority for temporary external-reader capabilities.
 *
 * Records contain only account identity and remote metadata. Credentials remain in the encrypted
 * session store and are resolved by the provider only after Android verifies the URI grant.
 */
internal object AndroidExternalFileHandoffRegistry {
    private data class Entry(
        val record: AndroidExternalFileHandoffRecord,
        val readers: MutableSet<AndroidExternalFileHandoffLease> = linkedSetOf(),
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    fun register(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AndroidExternalFileHandoffRecord {
        require(userId.isNotBlank()) { "A resolved account is required for external file handoff." }
        require(!file.isDirectory) { "Folders cannot be registered for external file handoff." }
        require(file.size?.let { it >= 0L } == true) {
            "A known non-negative size is required for seekable external file handoff."
        }
        require(!file.etag.isNullOrBlank()) {
            "A current ETag is required for seekable external file handoff."
        }
        require(nowEpochMillis >= 0L)
        val expiredReaders = mutableListOf<AndroidExternalFileHandoffLease>()
        val record = synchronized(lock) {
            expiredReaders += pruneExpiredLocked(nowEpochMillis)
            if (entries.size >= MAX_RECORDS) {
                val disposable = entries.values.firstOrNull { entry -> entry.readers.isEmpty() }
                    ?: error("Too many external file handoffs are active.")
                entries.remove(disposable.record.documentId)
            }
            val documentId = generateSequence {
                HANDOFF_DOCUMENT_ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
            }.first { candidate -> candidate !in entries }
            AndroidExternalFileHandoffRecord(
                documentId = documentId,
                accountId = NextcloudDocumentIds.accountKey(session),
                userId = userId,
                file = file,
                createdAtEpochMillis = nowEpochMillis,
                expiresAtEpochMillis = (nowEpochMillis + RECORD_LIFETIME_MILLIS)
                    .takeIf { it >= nowEpochMillis }
                    ?: Long.MAX_VALUE,
            ).also { created -> entries[documentId] = Entry(created) }
        }
        expiredReaders.forEach(AndroidExternalFileHandoffLease::revoke)
        return record
    }

    fun isHandoffDocumentId(documentId: String): Boolean = HANDOFF_DOCUMENT_ID_PATTERN.matches(documentId)

    fun peek(
        documentId: String,
        session: NextcloudSession,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AndroidExternalFileHandoffRecord? {
        if (!isHandoffDocumentId(documentId) || nowEpochMillis < 0L) return null
        val expiredReaders = mutableListOf<AndroidExternalFileHandoffLease>()
        val record = synchronized(lock) {
            expiredReaders += pruneExpiredLocked(nowEpochMillis)
            entries[documentId]?.record?.takeIf { candidate ->
                candidate.accountId == NextcloudDocumentIds.accountKey(session)
            }
        }
        expiredReaders.forEach(AndroidExternalFileHandoffLease::revoke)
        return record
    }

    fun acquire(
        documentId: String,
        session: NextcloudSession,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): AndroidExternalFileHandoffLease? {
        if (!isHandoffDocumentId(documentId) || nowEpochMillis < 0L) return null
        val expiredReaders = mutableListOf<AndroidExternalFileHandoffLease>()
        val lease = synchronized(lock) {
            expiredReaders += pruneExpiredLocked(nowEpochMillis)
            val entry = entries[documentId]?.takeIf { candidate ->
                candidate.record.accountId == NextcloudDocumentIds.accountKey(session)
            } ?: return@synchronized null
            if (
                entry.readers.size >= MAX_READERS_PER_RECORD ||
                activeReaderCountLocked() >= MAX_ACTIVE_READERS
            ) {
                return@synchronized null
            }
            lateinit var created: AndroidExternalFileHandoffLease
            created = AndroidExternalFileHandoffLease(entry.record) { released ->
                synchronized(lock) { entries[documentId]?.readers?.remove(released) }
            }
            entry.readers += created
            created
        }
        expiredReaders.forEach(AndroidExternalFileHandoffLease::revoke)
        return lease
    }

    fun revoke(documentId: String) {
        val readers = synchronized(lock) { entries.remove(documentId)?.readers?.toList().orEmpty() }
        readers.forEach(AndroidExternalFileHandoffLease::revoke)
    }

    fun clear() {
        val readers = synchronized(lock) {
            entries.values.flatMap { entry -> entry.readers }.also { entries.clear() }
        }
        readers.forEach(AndroidExternalFileHandoffLease::revoke)
    }

    private fun pruneExpiredLocked(nowEpochMillis: Long): List<AndroidExternalFileHandoffLease> {
        val expired = entries.values.filter { entry -> entry.record.expiresAtEpochMillis <= nowEpochMillis }
        expired.forEach { entry ->
            entries.remove(entry.record.documentId)
        }
        return expired.flatMap(Entry::readers)
    }

    private fun activeReaderCountLocked(): Int = entries.values.sumOf { entry -> entry.readers.size }

    internal const val MAX_READERS_PER_RECORD = 4
    private const val MAX_ACTIVE_READERS = 8
    private const val MAX_RECORDS = 32
    private const val RECORD_LIFETIME_MILLIS = 24L * 60L * 60L * 1000L
    private const val HANDOFF_DOCUMENT_ID_PREFIX = "nch1:"
    private val HANDOFF_DOCUMENT_ID_PATTERN = Regex("nch1:[0-9a-f]{32}")
}
