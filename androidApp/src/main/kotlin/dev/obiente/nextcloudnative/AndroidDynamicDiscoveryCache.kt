package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.isSafeDynamicDiscoveryCacheAppId
import dev.obiente.nextcloudnative.app.MAX_PERSISTED_DYNAMIC_DISCOVERY_BYTES
import dev.obiente.nextcloudnative.app.DynamicNativeMemoryCacheProducer
import java.io.File
import java.io.FileOutputStream

/** Serializes persisted dynamic discovery publications with account retirement. */
internal class AndroidDynamicDiscoveryCache(private val root: File) {
    private val lock = Any()
    private val retiredAccounts = mutableSetOf<String>()
    private val accountIncarnations = mutableMapOf<String, Long>()

    fun load(accountStorageKey: String, cacheAccountId: String, appId: String): String? = synchronized(lock) {
        if (accountStorageKey in retiredAccounts) return@synchronized null
        val target = cacheFile(cacheAccountId, appId) ?: return@synchronized null
        if (!target.isFile || target.length() !in 1..MAX_PERSISTED_DYNAMIC_DISCOVERY_BYTES.toLong()) {
            return@synchronized null
        }
        runCatching(target::readText).getOrNull()
    }

    fun save(
        accountStorageKey: String,
        cacheAccountId: String,
        appId: String,
        encoded: String,
        producer: DynamicNativeMemoryCacheProducer?,
    ) = synchronized(lock) {
        val current = producer ?: return@synchronized
        require(current.accountStorageKey == accountStorageKey)
        if (
            accountStorageKey in retiredAccounts ||
            current.incarnation != (accountIncarnations[accountStorageKey] ?: 0L)
        ) {
            return@synchronized
        }
        val target = cacheFile(cacheAccountId, appId) ?: return@synchronized
        check(root.mkdirs() || root.isDirectory) { "Could not create the dynamic contract cache." }
        val temporary = File(root, "${target.name}.part")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded.encodeToByteArray())
                output.fd.sync()
            }
            check(temporary.renameTo(target) || runCatching {
                temporary.copyTo(target, overwrite = true)
                check(temporary.delete() || !temporary.exists())
            }.isSuccess) { "Could not publish the dynamic contract cache." }
        } finally {
            temporary.delete()
        }
    }

    fun retireAccount(accountStorageKey: String, cacheAccountId: String?) = synchronized(lock) {
        if (retiredAccounts.add(accountStorageKey)) {
            accountIncarnations[accountStorageKey] = (accountIncarnations[accountStorageKey] ?: 0L) + 1L
        }
        if (!root.exists()) return@synchronized
        check(root.isDirectory) { "The dynamic contract cache is unavailable." }
        val files = root.listFiles() ?: error("Could not inspect the dynamic contract cache.")
        files.forEach { file -> check(file.isFile && file.name.matches(ACCOUNT_CACHE_FILE)) {
            "The dynamic contract cache contains an unexpected entry."
        } }
        files.filter { cacheAccountId == null || it.name.startsWith("$cacheAccountId-") }
            .forEach { file ->
                check(file.delete() || !file.exists()) { "Could not clear the dynamic contract cache." }
            }
    }

    fun activateAccount(accountStorageKey: String) = synchronized(lock) {
        retiredAccounts -= accountStorageKey
    }

    private fun cacheFile(cacheAccountId: String, appId: String): File? {
        if (!cacheAccountId.matches(ACCOUNT_CACHE_ID) || !appId.isSafeDynamicDiscoveryCacheAppId()) return null
        return File(root, "$cacheAccountId-$appId.json")
    }

    private companion object {
        val ACCOUNT_CACHE_ID = Regex("[0-9a-f]{32}(?:[0-9a-f]{32})?")
        val ACCOUNT_CACHE_FILE = Regex("${ACCOUNT_CACHE_ID.pattern}-[A-Za-z0-9._-]+\\.json(?:\\.part)?")
    }
}

internal object AndroidDynamicDiscoveryCacheCoordinator {
    private val instances = mutableMapOf<String, AndroidDynamicDiscoveryCache>()

    fun get(root: File): AndroidDynamicDiscoveryCache = synchronized(this) {
        val key = root.absoluteFile.normalize().path
        instances.getOrPut(key) { AndroidDynamicDiscoveryCache(root) }
    }
}
