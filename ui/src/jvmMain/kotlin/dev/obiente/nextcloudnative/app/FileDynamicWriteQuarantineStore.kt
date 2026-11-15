package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-backed [DynamicWriteQuarantineStore]: one bounded JSON file per account identity digest.
 *
 * Every change is written to a temporary file in the same directory, flushed to the device, and
 * moved over the previous file, so a crash leaves either the old or the new state and never a
 * partial one. An unreadable file fails closed: reads throw, so callers refuse writes instead of
 * silently forgetting a quarantine or pending operation. Ordinary clear actions also fail closed.
 */
class FileDynamicWriteQuarantineStore(private val directory: File) : DynamicWriteQuarantineStore {
    private class Owner { val generations = mutableMapOf<String, Long>() }
    // All instances reload under one directory owner. No instance-local cache can overwrite a
    // reservation made by another instance or resurrect a retired account.
    private val lock = synchronized(owners) { owners.getOrPut(directory.canonicalFile.toPath()) { Owner() } }

    override suspend fun generation(accountDigest: String): Long = synchronized(lock) { lock.generations[accountDigest] ?: 0L }

    override suspend fun reserve(key: DynamicWriteRuleKey, pending: DynamicPendingRuleOperation, generation: Long): Boolean =
        change(key.accountDigest) {
            (lock.generations[key.accountDigest] ?: 0L) == generation && reserve(key, pending)
        }

    override suspend fun resolve(key: DynamicWriteRuleKey, operationId: String, resolution: DynamicPendingRuleResolution, nowEpochMillis: Long) =
        change(key.accountDigest) { resolve(key, operationId, resolution, nowEpochMillis) }

    override suspend fun record(key: DynamicWriteRuleKey) = read(key.accountDigest) { record(key) }

    override suspend fun records(accountDigest: String, appId: String) =
        read(accountDigest) { forApp(appId) }

    override suspend fun pendingOperationIds(accountDigest: String) = read(accountDigest) {
        all.mapNotNullTo(mutableSetOf()) { it.pendingVerification?.operationId }
    }

    override suspend fun beginProbation(key: DynamicWriteRuleKey, nowEpochMillis: Long, generation: Long?) =
        change(key.accountDigest, generation) { beginProbation(key, nowEpochMillis) }

    override suspend fun confirm(key: DynamicWriteRuleKey, nowEpochMillis: Long) =
        change(key.accountDigest) { confirm(key, nowEpochMillis) }

    override suspend fun quarantine(key: DynamicWriteRuleKey, reason: DynamicWriteQuarantineReason, nowEpochMillis: Long) =
        change(key.accountDigest) { quarantine(key, reason, nowEpochMillis) }

    override suspend fun clear(key: DynamicWriteRuleKey) {
        change(key.accountDigest) { clear(key) }
    }

    override suspend fun clearApp(accountDigest: String, appId: String) {
        change(accountDigest) { clearApp(appId) }
    }

    override suspend fun observeAppVersion(accountDigest: String, appId: String, appVersion: String) {
        change(accountDigest) { observeAppVersion(appId, appVersion) }
    }

    override suspend fun removeAccount(accountDigest: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            lock.generations[accountDigest] = (lock.generations[accountDigest] ?: 0L) + 1L
            val file = fileFor(accountDigest)
            check(!file.exists() || file.delete()) { "Could not remove write safety state." }
        }
    }

    private suspend fun <T> read(digest: String, block: DynamicWriteRuleRecords.() -> T): T =
        withContext(Dispatchers.IO) { synchronized(lock) { load(digest).block() } }

    private suspend fun <T> change(
        digest: String,
        generation: Long? = null,
        block: DynamicWriteRuleRecords.() -> T,
    ): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(generation == null || (lock.generations[digest] ?: 0L) == generation) { "The account changed before write safety state could be saved." }
            val records = load(digest)
            // Work on a copy so a failed write never leaves memory ahead of the device.
            val updated = DynamicWriteRuleRecords(records.all)
            val result = updated.block()
            if (updated.all != records.all) persist(digest, updated)
            result
        }
    }

    private fun load(digest: String): DynamicWriteRuleRecords {
        val file = fileFor(digest)
        val records = when {
            !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) -> DynamicWriteRuleRecords()
            else -> decode(file, digest) ?: error("Write safety state is unreadable.")
        }
        return records
    }

    private fun decode(file: File, digest: String): DynamicWriteRuleRecords? {
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) return null
        val stored = try {
            storeJson.decodeFromString(StoredRecords.serializer(), file.readText(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            // A record whose key violates its invariants is rejected as a whole.
            return null
        }
        if (stored.version != FORMAT_VERSION || stored.records.size > MAX_RECORDS_PER_ACCOUNT) return null
        if (stored.records.any { it.key.accountDigest != digest }) return null
        return DynamicWriteRuleRecords(stored.records)
    }

    private fun persist(digest: String, records: DynamicWriteRuleRecords) {
        val path = directory.toPath()
        Files.createDirectories(path)
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "The write safety directory is unsafe."
        }
        val target = fileFor(digest)
        if (records.all.isEmpty()) {
            check(!target.exists() || target.delete()) { "Could not clear write safety state." }
            return
        }
        val bytes = storeJson.encodeToString(StoredRecords.serializer(), StoredRecords(FORMAT_VERSION, records.all))
            .encodeToByteArray()
        check(bytes.size <= MAX_FILE_BYTES) { "Write safety state is too large." }
        val temporary = File.createTempFile("$digest.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun fileFor(digest: String): File {
        require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) { "The account digest is invalid." }
        return File(directory, "$digest.json")
    }

    @Serializable
    private data class StoredRecords(val version: Int, val records: List<DynamicWriteRuleRecord>)

    private companion object {
        val owners = mutableMapOf<Path, Owner>()
        const val FORMAT_VERSION = 1
        const val MAX_FILE_BYTES = 512L * 1024L
        val storeJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
    }
}
