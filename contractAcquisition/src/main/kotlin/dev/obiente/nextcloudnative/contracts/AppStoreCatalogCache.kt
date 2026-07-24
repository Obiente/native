package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface AppStoreCatalogCache {
    fun load(url: String): ByteArray?
    fun store(url: String, bytes: ByteArray)
}

class MemoryAppStoreCatalogCache : AppStoreCatalogCache {
    private val entries = ConcurrentHashMap<String, ByteArray>()

    override fun load(url: String): ByteArray? = entries[url]?.copyOf()

    override fun store(url: String, bytes: ByteArray) {
        entries[url] = bytes.copyOf()
    }
}

/**
 * Bounded, process-independent cache for public App Store catalogs. Catalogs contain no account
 * data or credentials. A successful catalog is reused for six hours so opening many installed apps
 * cannot trigger the App Store's request rate limit.
 */
class FileAppStoreCatalogCache(
    private val directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AppStoreCatalogCache {
    override fun load(url: String): ByteArray? = synchronized(lock) {
        val file = cacheFile(url)
        if (!file.isFile || nowMillis() - file.lastModified() !in 0..MAX_CACHE_AGE_MILLIS) return null
        if (file.length() !in 1..MAX_CATALOG_CACHE_BYTES) return null
        runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    override fun store(url: String, bytes: ByteArray): Unit = synchronized(lock) {
        require(bytes.size.toLong() in 1..MAX_CATALOG_CACHE_BYTES) { "The App Store catalog is outside the cache limit." }
        check(directory.exists() || directory.mkdirs()) { "Could not create the App Store catalog cache." }
        val target = cacheFile(url)
        val temporary = File(directory, "${target.name}.tmp-${nowMillis()}")
        try {
            temporary.outputStream().buffered().use { output ->
                output.write(bytes)
                output.flush()
            }
            check(!target.exists() || target.delete()) { "Could not replace the cached App Store catalog." }
            check(temporary.renameTo(target)) { "Could not publish the cached App Store catalog." }
            check(target.setLastModified(nowMillis())) { "Could not timestamp the cached App Store catalog." }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun cacheFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, "$digest.json")
    }

    private companion object {
        val lock = Any()
        const val MAX_CATALOG_CACHE_BYTES = 32L * 1024L * 1024L
        const val MAX_CACHE_AGE_MILLIS = 6L * 60L * 60L * 1_000L
    }
}
