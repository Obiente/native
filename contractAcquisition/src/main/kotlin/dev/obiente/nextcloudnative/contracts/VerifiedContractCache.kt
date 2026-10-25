package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Cache for contracts that have already passed package signature and source validation.
 *
 * The key includes the installed app and server versions, so an app or Nextcloud upgrade cannot
 * accidentally reuse a contract selected for an older runtime. Contracts contain public API
 * metadata only. Account URLs, user names, passwords, and API response data are never stored here.
 */
interface VerifiedContractCache {
    fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract?

    /**
     * Persists [contract] for [request]. Returns [VerifiedContractStore.InvalidationNotCleared] without
     * persisting when an earlier invalidation of the entry cannot be cleared, because a later load
     * would discard the new contract. Other storage failures throw.
     */
    fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract): VerifiedContractStore

    /**
     * Forgets only the contract cached for [request]; other entries stay. A discarded entry is never
     * served again, even when it cannot be deleted; the result says how that was ensured.
     */
    fun remove(request: ContractAcquisitionRequest): VerifiedContractRemoval
}

/** Whether a verified contract was persisted. Bounded and typed, for local diagnostics. */
enum class VerifiedContractStore {
    Stored,

    /** An invalidation marker for the entry could not be removed, so the contract was not persisted. */
    InvalidationNotCleared,
}

/** How a cached verified contract was discarded. Bounded and typed, for local diagnostics. */
enum class VerifiedContractRemoval {
    /** The entry was deleted, or nothing was cached. */
    Removed,

    /** The entry could not be deleted; an invalidation marker beside it makes every read a miss. */
    MarkedInvalid,

    /**
     * Neither deletion nor the marker succeeded; reads in this process treat the entry as a miss,
     * and deletion is retried on each later read or store.
     */
    InvalidatedForProcess,
}

class MemoryVerifiedContractCache : VerifiedContractCache {
    private val entries = ConcurrentHashMap<ContractAcquisitionRequest, VerifiedOpenApiContract>()

    override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract? = entries[request]

    override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract): VerifiedContractStore {
        entries[request] = contract
        return VerifiedContractStore.Stored
    }

    override fun remove(request: ContractAcquisitionRequest): VerifiedContractRemoval {
        entries.remove(request)
        return VerifiedContractRemoval.Removed
    }
}

/**
 * Bounded process-independent cache for verified contracts.
 *
 * Signature verification and archive parsing can be much more expensive than rendering a screen.
 * Reusing the verified result makes the first app open after a process restart local and
 * deterministic. Entries expire after seven days and are keyed with SHA-256 rather than exposing
 * app or version details in filenames.
 */
class FileVerifiedContractCache internal constructor(
    private val directory: File,
    private val nowMillis: () -> Long,
    private val files: VerifiedContractFileOperations,
) : VerifiedContractCache {
    constructor(directory: File, nowMillis: () -> Long = System::currentTimeMillis) :
        this(directory, nowMillis, VerifiedContractFileOperations.Default)

    /** Entries whose deletion failed while this cache was in use; guarded by [lock]. */
    private val invalidatedForProcess = mutableSetOf<String>()

    override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract? = synchronized(lock) {
        val file = cacheFile(request)
        if (isInvalidated(file)) {
            discard(file)
            return null
        }
        if (!file.isFile || nowMillis() - file.lastModified() !in 0..MAX_CACHE_AGE_MILLIS) return null
        if (file.length() !in 1..MAX_CONTRACT_CACHE_BYTES) return null
        runCatching {
            JSONObject(file.readText()).toVerifiedContract()
        }.getOrNull()?.takeIf { contract ->
            contract.appId == request.appId &&
                (request.installedAppVersion == null || contract.appVersion == request.installedAppVersion)
        }
    }

    override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract): VerifiedContractStore =
        synchronized(lock) {
            require(contract.appId == request.appId) { "The verified contract belongs to a different app." }
            val bytes = contract.toJson().toString().encodeToByteArray()
            require(bytes.size.toLong() in 1..MAX_CONTRACT_CACHE_BYTES) {
                "The verified contract is outside the cache limit."
            }
            check(directory.exists() || directory.mkdirs()) { "Could not create the verified contract cache." }
            val target = cacheFile(request)
            // A marker is cleared before the replacement is published, so a marker always predates the
            // entry it invalidates. A marker that cannot be cleared would make the next load delete the
            // new contract, so the contract is not persisted and the caller keeps it in memory.
            if (!clearInvalidation(target)) return VerifiedContractStore.InvalidationNotCleared
            val temporary = File(directory, "${target.name}.tmp-${nowMillis()}")
            try {
                temporary.outputStream().buffered().use { output ->
                    output.write(bytes)
                    output.flush()
                }
                check(!target.exists() || files.delete(target)) { "Could not replace the cached verified contract." }
                check(temporary.renameTo(target)) { "Could not publish the cached verified contract." }
                check(target.setLastModified(nowMillis())) { "Could not timestamp the cached verified contract." }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
            VerifiedContractStore.Stored
        }

    override fun remove(request: ContractAcquisitionRequest): VerifiedContractRemoval = synchronized(lock) {
        val file = cacheFile(request)
        if (discard(file)) return VerifiedContractRemoval.Removed
        // Deletion failed, so the entry must not be served again until it is gone.
        invalidatedForProcess += file.absolutePath
        val marker = invalidationMarker(file)
        if (marker.exists() || files.createMarker(marker)) {
            VerifiedContractRemoval.MarkedInvalid
        } else {
            VerifiedContractRemoval.InvalidatedForProcess
        }
    }

    private fun isInvalidated(file: File): Boolean =
        file.absolutePath in invalidatedForProcess || invalidationMarker(file).exists()

    /** Deletes [file] and then any invalidation of it. Returns false while the entry still exists. */
    private fun discard(file: File): Boolean {
        if (file.exists() && !files.delete(file)) return false
        clearInvalidation(file)
        return true
    }

    /**
     * Removes any invalidation of [file] and returns true when none remains. The process record stays
     * while a marker cannot be removed, so the entry remains a miss.
     */
    private fun clearInvalidation(file: File): Boolean {
        val marker = invalidationMarker(file)
        val cleared = !marker.exists() || files.delete(marker)
        if (cleared) invalidatedForProcess -= file.absolutePath
        return cleared
    }

    private fun invalidationMarker(file: File): File = File(file.parentFile, "${file.name}$INVALIDATION_SUFFIX")

    private fun cacheFile(request: ContractAcquisitionRequest): File {
        val identity = listOf(
            CACHE_FORMAT_VERSION,
            request.appId,
            request.serverVersion,
            request.installedAppVersion.orEmpty(),
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, "$digest.json")
    }

    private companion object {
        val lock = Any()
        const val INVALIDATION_SUFFIX = ".invalid"
        const val CACHE_FORMAT_VERSION = "4"
        const val MAX_CONTRACT_CACHE_BYTES = 32L * 1024L * 1024L
        const val MAX_CACHE_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

/** File operations that can fail without throwing; a seam for deletion failures in tests. */
internal interface VerifiedContractFileOperations {
    fun delete(file: File): Boolean
    fun createMarker(file: File): Boolean

    object Default : VerifiedContractFileOperations {
        override fun delete(file: File): Boolean = try {
            file.delete()
        } catch (_: SecurityException) {
            false
        }

        override fun createMarker(file: File): Boolean = try {
            file.createNewFile() || file.isFile
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private fun VerifiedOpenApiContract.toJson(): JSONObject = JSONObject()
    .put("appId", appId)
    .put("appVersion", appVersion)
    .put("contractVersion", contractVersion)
    .put("specFile", specFile)
    .put("document", document)
    .put("catalogUrl", catalogUrl)
    .put("packageUrl", packageUrl)
    .put("sourceUrl", sourceUrl)
    .put("sourceKind", sourceKind.name)
    .put("contractKind", contractKind.name)

private fun JSONObject.toVerifiedContract(): VerifiedOpenApiContract = VerifiedOpenApiContract(
    appId = getString("appId"),
    appVersion = getString("appVersion"),
    contractVersion = getString("contractVersion"),
    specFile = getString("specFile"),
    document = getString("document"),
    catalogUrl = getString("catalogUrl"),
    packageUrl = getString("packageUrl"),
    sourceUrl = getString("sourceUrl"),
    sourceKind = OpenApiContractSourceKind.valueOf(getString("sourceKind")),
    contractKind = VerifiedContractKind.valueOf(getString("contractKind")),
)
