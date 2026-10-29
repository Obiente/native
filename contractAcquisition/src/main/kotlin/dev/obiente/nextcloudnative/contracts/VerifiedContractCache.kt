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
     * writing when an invalidated earlier entry or its invalidation cannot be removed. Other storage
     * failures throw, and never leave an invalidated or replaced entry readable.
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

    /**
     * An invalidated earlier entry, or its marker, could not be removed, so nothing was written and
     * the invalidation stays in force.
     */
    InvalidationNotCleared,

    /** Writing or moving the new entry failed, for example on a full disk; it is served from memory. */
    Failed,
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
 *
 * Each key has an entry file E, an optional `.invalid` marker M beside it, an optional in-process
 * record P, and, while a store runs, a temporary file T that loads never read. All operations hold
 * one lock, so a concurrent refresh in this process is serialized with them.
 *
 * | State          | Load                   | Invalidate                    | Store                                       |
 * |----------------|------------------------|-------------------------------|---------------------------------------------|
 * | Absent         | miss                   | no change                     | write T, move T to E: valid                 |
 * | Valid E        | hit                    | delete E: absent. If that     | write T, delete E, move T to E: valid. If   |
 * |                |                        | fails, add P and write M:     | any step fails before the move, delete the  |
 * |                |                        | invalidated                   | old E, or else invalidate it; then throw    |
 * | E + M and/or P | delete E, then clear M | retry as for a valid E; stays | delete E; if that fails: no change, refused |
 * |                | and P; miss            | invalidated until E is gone   | Then clear M and P; if that fails: M only,  |
 * |                |                        |                               | refused. Then write T and move T to E       |
 * | M without E    | clear M and P; miss    | clear M and P                 | clear M and P (if that fails: refused),     |
 * |                |                        |                               | then write T and move T to E                |
 * | Leftover T     | ignored                | ignored                       | ignored; each store uses a new T            |
 *
 * The invalidation is removed only after the invalidated entry is gone, and a new entry is written
 * only after the invalidation is gone. So process death or a failed write (such as a full disk) at
 * any step leaves either the invalidated entry with its marker, or no invalidated entry at all; an
 * invalidated contract never becomes readable again. When a valid entry cannot be replaced, it is
 * deleted or invalidated, so an older contract is not served after a restart in place of a newer
 * one. Only an entry whose deletion and marker both failed relies on the in-process record alone.
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
            if (isInvalidated(target)) {
                // The invalidated entry goes first, and its invalidation stays until it is gone.
                if (target.exists() && !files.delete(target)) return VerifiedContractStore.InvalidationNotCleared
                // A marker left with no entry is harmless, but a new entry beside it would be deleted.
                if (!clearInvalidation(target)) return VerifiedContractStore.InvalidationNotCleared
            }
            val temporary = File(directory, "${target.name}.tmp-${nowMillis()}")
            var published = false
            try {
                files.write(temporary, bytes)
                check(!target.exists() || files.delete(target)) { "Could not replace the cached verified contract." }
                check(temporary.renameTo(target)) { "Could not publish the cached verified contract." }
                published = true
                check(target.setLastModified(nowMillis())) { "Could not timestamp the cached verified contract." }
            } catch (failure: Exception) {
                // An older valid entry that could not be replaced must not outlive the newer contract.
                if (!published && !discard(target)) invalidate(target)
                throw failure
            } finally {
                if (temporary.exists()) temporary.delete()
            }
            VerifiedContractStore.Stored
        }

    override fun remove(request: ContractAcquisitionRequest): VerifiedContractRemoval = synchronized(lock) {
        val file = cacheFile(request)
        if (discard(file)) return VerifiedContractRemoval.Removed
        // Deletion failed, so the entry must not be served again until it is gone.
        invalidate(file)
    }

    /** Records that [file] must not be read: in this process always, and on disk when the marker can be written. */
    private fun invalidate(file: File): VerifiedContractRemoval {
        invalidatedForProcess += file.absolutePath
        val marker = invalidationMarker(file)
        return if (marker.exists() || files.createMarker(marker)) {
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

/** File operations, a seam for deletion, marker, and write failures in tests. */
internal interface VerifiedContractFileOperations {
    fun delete(file: File): Boolean
    fun createMarker(file: File): Boolean

    /** Writes [bytes] to [file]; a failure such as a full disk throws [IOException]. */
    fun write(file: File, bytes: ByteArray) {
        file.outputStream().buffered().use { output ->
            output.write(bytes)
            output.flush()
        }
    }

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
