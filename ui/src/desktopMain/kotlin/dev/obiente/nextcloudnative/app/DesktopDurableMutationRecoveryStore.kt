package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

internal class DesktopDurableMutationRecoveryStore(
    private val root: File,
) {
    fun load(accountScope: String, kind: DurableMutationRecoveryKind): String? {
        val target = target(accountScope, kind)
        if (!target.isFile || target.length() !in 1..MAX_DURABLE_MUTATION_RECOVERY_BYTES.toLong()) return null
        return runCatching { target.readText() }
            .getOrNull()
            ?.takeIf { encoded -> encoded.encodeToByteArray().size <= MAX_DURABLE_MUTATION_RECOVERY_BYTES }
    }

    fun save(accountScope: String, kind: DurableMutationRecoveryKind, encoded: String): Boolean {
        val bytes = encoded.encodeToByteArray()
        if (bytes.isEmpty() || bytes.size > MAX_DURABLE_MUTATION_RECOVERY_BYTES) return false
        val target = target(accountScope, kind)
        return runCatching {
            check(root.mkdirs() || root.isDirectory) { "Could not create mutation recovery storage." }
            applyPrivatePermissions(root, "rwx------")
            val temporary = File(root, ".${target.name}.part")
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            applyPrivatePermissions(temporary, "rw-------")
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(target.isFile) { "Could not publish mutation recovery state." }
            applyPrivatePermissions(target, "rw-------")
        }.isSuccess
    }

    fun clear(accountScope: String, kind: DurableMutationRecoveryKind): Boolean {
        val target = target(accountScope, kind)
        val temporary = File(root, ".${target.name}.part")
        return runCatching {
            if (temporary.exists()) check(temporary.delete()) { "Could not clear temporary mutation recovery state." }
            if (target.exists()) check(target.delete()) { "Could not clear mutation recovery state." }
        }.isSuccess && !target.exists() && !temporary.exists()
    }

    private fun target(accountScope: String, kind: DurableMutationRecoveryKind): File {
        require(accountScope.isCanonicalGroupwareMutationAccountScope()) { "The mutation account scope is invalid." }
        return File(root, "${kind.storageKey}-$accountScope.json")
    }
}

private fun applyPrivatePermissions(target: File, permissions: String) {
    runCatching {
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(permissions))
    }
}

internal fun defaultDesktopDurableMutationRecoveryRoot(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val root = when {
        os.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Local")
        os.contains("mac") -> File(System.getProperty("user.home"), "Library/Application Support")
        else -> System.getenv("XDG_STATE_HOME")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/state")
    }
    return File(root, "nextcloud-native/mutation-recovery-v1")
}
