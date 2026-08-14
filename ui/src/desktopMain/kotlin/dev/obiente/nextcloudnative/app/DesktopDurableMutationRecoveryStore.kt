package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal class DesktopDurableMutationRecoveryStore(
    private val root: File,
) {
    fun load(accountScope: String, kind: DurableMutationRecoveryKind): String? {
        val target = target(accountScope, kind)
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val privacy = requirePrivateDirectory(root)
        check(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Mutation recovery state is not a regular file."
        }
        requirePrivatePath(target, privacy, directory = false)
        check(target.length() in 1..MAX_DURABLE_MUTATION_RECOVERY_BYTES.toLong()) {
            "Mutation recovery state has an invalid size."
        }
        return target.readText().also { encoded ->
            check(encoded.encodeToByteArray().size <= MAX_DURABLE_MUTATION_RECOVERY_BYTES) {
                "Mutation recovery state exceeds its size limit."
            }
        }
    }

    fun save(accountScope: String, kind: DurableMutationRecoveryKind, encoded: String): Boolean {
        val bytes = encoded.encodeToByteArray()
        if (bytes.isEmpty() || bytes.size > MAX_DURABLE_MUTATION_RECOVERY_BYTES) return false
        val target = target(accountScope, kind)
        return runCatching {
            val privacy = createOrRepairPrivateDirectory(root)
            val temporary = File(root, ".${target.name}.part")
            if (Files.deleteIfExists(temporary.toPath())) syncDirectory(root, privacy)
            createPrivateFile(temporary, privacy)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            requirePrivatePath(temporary, privacy, directory = false)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            check(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Could not publish mutation recovery state."
            }
            requirePrivatePath(target, privacy, directory = false)
            syncDirectory(root, privacy)
        }.isSuccess
    }

    fun clear(accountScope: String, kind: DurableMutationRecoveryKind): Boolean {
        val target = target(accountScope, kind)
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            val privacy = requirePrivateDirectory(root)
            val temporary = File(root, ".${target.name}.part")
            val changed = Files.deleteIfExists(temporary.toPath()) or Files.deleteIfExists(target.toPath())
            if (changed) syncDirectory(root, privacy)
            check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
            check(!Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS))
        }.isSuccess
    }

    private fun target(accountScope: String, kind: DurableMutationRecoveryKind): File {
        require(accountScope.isCanonicalGroupwareMutationAccountScope()) { "The mutation account scope is invalid." }
        return File(root, "${kind.storageKey}-$accountScope.json")
    }
}

private enum class DesktopRecoveryPrivacy { Posix, Acl }

private val PRIVATE_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
private val PRIVATE_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

private fun createOrRepairPrivateDirectory(directory: File): DesktopRecoveryPrivacy {
    val path = directory.toPath()
    check(!Files.isSymbolicLink(path)) { "Mutation recovery storage must not be a symbolic link." }
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        val parent = requireNotNull(directory.parentFile) { "Mutation recovery storage needs a parent directory." }
        Files.createDirectories(parent.toPath())
        val posix = Files.getFileAttributeView(parent.toPath(), PosixFileAttributeView::class.java) != null
        if (posix) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS))
            syncPosixDirectory(parent)
        } else {
            Files.createDirectory(path)
        }
    }
    check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Mutation recovery storage is not a directory." }
    val privacy = privacyMode(directory)
    applyOwnerOnlyPrivacy(directory, privacy, directory = true)
    requirePrivatePath(directory, privacy, directory = true)
    return privacy
}

private fun requirePrivateDirectory(directory: File): DesktopRecoveryPrivacy {
    val path = directory.toPath()
    check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "Mutation recovery storage is not a private directory."
    }
    return privacyMode(directory).also { privacy ->
        requirePrivatePath(directory, privacy, directory = true)
    }
}

private fun createPrivateFile(file: File, privacy: DesktopRecoveryPrivacy) {
    val path = file.toPath()
    when (privacy) {
        DesktopRecoveryPrivacy.Posix ->
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS))
        DesktopRecoveryPrivacy.Acl -> {
            Files.createFile(path)
            applyOwnerOnlyPrivacy(file, privacy, directory = false)
        }
    }
    requirePrivatePath(file, privacy, directory = false)
}

private fun privacyMode(path: File): DesktopRecoveryPrivacy = when {
    Files.getFileAttributeView(path.toPath(), PosixFileAttributeView::class.java) != null ->
        DesktopRecoveryPrivacy.Posix
    Files.getFileAttributeView(path.toPath(), AclFileAttributeView::class.java) != null ->
        DesktopRecoveryPrivacy.Acl
    else -> error("The filesystem cannot enforce private mutation recovery permissions.")
}

private fun applyOwnerOnlyPrivacy(path: File, privacy: DesktopRecoveryPrivacy, directory: Boolean) {
    when (privacy) {
        DesktopRecoveryPrivacy.Posix -> Files.setPosixFilePermissions(
            path.toPath(),
            if (directory) PRIVATE_DIRECTORY_PERMISSIONS else PRIVATE_FILE_PERMISSIONS,
        )
        DesktopRecoveryPrivacy.Acl -> {
            val view = requireNotNull(
                Files.getFileAttributeView(path.toPath(), AclFileAttributeView::class.java),
            ) { "The filesystem ACL is unavailable." }
            val owner = view.owner
            val ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(AclEntryPermission.entries.toSet())
                .build()
            view.acl = listOf(ownerEntry)
        }
    }
}

private fun requirePrivatePath(path: File, privacy: DesktopRecoveryPrivacy, directory: Boolean) {
    check(!Files.isSymbolicLink(path.toPath())) { "Mutation recovery storage must not be a symbolic link." }
    when (privacy) {
        DesktopRecoveryPrivacy.Posix -> {
            val actual = Files.getPosixFilePermissions(path.toPath(), LinkOption.NOFOLLOW_LINKS)
            val expected: Set<PosixFilePermission> =
                if (directory) PRIVATE_DIRECTORY_PERMISSIONS else PRIVATE_FILE_PERMISSIONS
            check(actual == expected) { "Mutation recovery storage permissions are not owner-only." }
        }
        DesktopRecoveryPrivacy.Acl -> {
            val view = requireNotNull(
                Files.getFileAttributeView(path.toPath(), AclFileAttributeView::class.java),
            ) { "The filesystem ACL is unavailable." }
            val owner = view.owner
            check(view.acl.none { entry ->
                entry.type() == AclEntryType.ALLOW && entry.principal() != owner
            }) { "Mutation recovery storage grants access outside its owner." }
        }
    }
}

private fun syncDirectory(directory: File, privacy: DesktopRecoveryPrivacy) {
    if (privacy == DesktopRecoveryPrivacy.Posix) syncPosixDirectory(directory)
}

private fun syncPosixDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
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
