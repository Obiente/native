package dev.obiente.nextcloudnative.contracts

import java.io.File
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
    fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract)
}

class MemoryVerifiedContractCache : VerifiedContractCache {
    private val entries = ConcurrentHashMap<ContractAcquisitionRequest, VerifiedOpenApiContract>()

    override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract? = entries[request]

    override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract) {
        entries[request] = contract
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
class FileVerifiedContractCache(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : VerifiedContractCache {
    override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract? = synchronized(lock) {
        val file = cacheFile(request)
        if (!file.isFile || nowMillis() - file.lastModified() !in 0..MAX_CACHE_AGE_MILLIS) return null
        if (file.length() !in 1..MAX_CONTRACT_CACHE_BYTES) return null
        runCatching {
            JSONObject(file.readText()).toVerifiedContract()
        }.getOrNull()?.takeIf { contract ->
            contract.appId == request.appId &&
                (request.installedAppVersion == null || contract.appVersion == request.installedAppVersion)
        }
    }

    override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract): Unit =
        synchronized(lock) {
            require(contract.appId == request.appId) { "The verified contract belongs to a different app." }
            val bytes = contract.toJson().toString().encodeToByteArray()
            require(bytes.size.toLong() in 1..MAX_CONTRACT_CACHE_BYTES) {
                "The verified contract is outside the cache limit."
            }
            check(directory.exists() || directory.mkdirs()) { "Could not create the verified contract cache." }
            val target = cacheFile(request)
            val temporary = File(directory, "${target.name}.tmp-${nowMillis()}")
            try {
                temporary.outputStream().buffered().use { output ->
                    output.write(bytes)
                    output.flush()
                }
                check(!target.exists() || target.delete()) { "Could not replace the cached verified contract." }
                check(temporary.renameTo(target)) { "Could not publish the cached verified contract." }
                check(target.setLastModified(nowMillis())) { "Could not timestamp the cached verified contract." }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }

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
        const val CACHE_FORMAT_VERSION = "3"
        const val MAX_CONTRACT_CACHE_BYTES = 32L * 1024L * 1024L
        const val MAX_CACHE_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
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
