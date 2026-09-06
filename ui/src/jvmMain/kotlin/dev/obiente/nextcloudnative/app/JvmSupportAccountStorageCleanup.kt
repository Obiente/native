package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Deletes only durable support artifacts whose descriptor proves ownership by one account. */
class JvmSupportAccountStorageCleanup(
    private val root: File,
    private val directorySync: (File) -> Unit,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun removeAccount(accountIdentity: String, inMemoryArchive: File?) {
        require(accountIdentity.matches(ACCOUNT_IDENTITY))
        if (!root.exists()) return
        check(root.isDirectory) { "Private support submission storage is unavailable." }
        var changed = false
        val pending = File(root, "pending.json")
        if (pending.exists() && descriptorAccount(pending, MAX_PENDING_DESCRIPTOR_BYTES) == accountIdentity) {
            val archiveName = descriptorString(pending, "archiveName")
            archiveName?.let { name ->
                require(name.matches(SUPPORT_ARCHIVE))
                deletePrivate(File(root, name))
            }
            deleteDurably(pending)
            changed = true
        }
        root.listFiles()?.filter { it.name.matches(COMPLETED_DESCRIPTOR) }?.forEach { descriptor ->
            if (descriptorAccount(descriptor, MAX_COMPLETED_DESCRIPTOR_BYTES) == accountIdentity) {
                deleteDurably(descriptor)
                changed = true
            }
        } ?: throw IOException("Could not inspect private support submission storage.")
        inMemoryArchive?.let { archive ->
            require(archive.absoluteFile.normalize().parentFile == root.absoluteFile.normalize())
            changed = changed || archive.exists()
            deletePrivate(archive)
        }
        if (changed) directorySync(root)
    }

    private fun descriptorAccount(descriptor: File, maximumBytes: Long): String {
        val attributes = Files.readAttributes(descriptor.toPath(), BasicFileAttributes::class.java)
        require(attributes.isRegularFile && attributes.size() in 1..maximumBytes)
        return descriptorString(descriptor, "originAccountIdentity")
            ?.takeIf { it.matches(ACCOUNT_IDENTITY) }
            ?: error("The private support recovery descriptor is invalid.")
    }

    private fun descriptorString(descriptor: File, name: String): String? =
        json.parseToJsonElement(descriptor.readText()).jsonObject[name]?.jsonPrimitive?.content

    private fun deleteDurably(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    private fun deletePrivate(file: File) {
        check(!file.exists() || deleteFile(file) || !file.exists()) {
            "Could not clear private support submission storage."
        }
    }

    private companion object {
        val ACCOUNT_IDENTITY = Regex("[0-9a-f]{32}(?:[0-9a-f]{32})?")
        val SUPPORT_ARCHIVE = Regex("support-[0-9a-f-]{36}\\.zip")
        val COMPLETED_DESCRIPTOR = Regex("completed-[0-9a-f-]{36}\\.json")
        const val MAX_PENDING_DESCRIPTOR_BYTES = 4L * 1024L * 1024L
        const val MAX_COMPLETED_DESCRIPTOR_BYTES = 64L * 1024L
    }
}
