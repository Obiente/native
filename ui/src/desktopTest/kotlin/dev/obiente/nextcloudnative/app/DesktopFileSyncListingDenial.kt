package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Makes [directory] unlistable for the current user through an NTFS deny entry or POSIX mode.
 *
 * Returns the action that restores the original access, or null when this platform or user (for
 * example a privileged account) still lists the directory, so callers can skip the scenario.
 */
internal fun denyDesktopTestDirectoryListing(directory: Path): (() -> Unit)? {
    val restore = runCatching {
        val acl = Files.getFileAttributeView(directory, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (acl != null) denyAclListing(directory, acl) else denyPosixListing(directory)
    }.getOrNull() ?: return null
    val denied = runCatching { Files.newDirectoryStream(directory).use { it.iterator().hasNext() } }.isFailure
    if (!denied) restore()
    return restore.takeIf { denied }
}

private fun denyAclListing(directory: Path, acl: AclFileAttributeView): () -> Unit {
    val original = acl.acl
    val user = directory.fileSystem.userPrincipalLookupService
        .lookupPrincipalByName(System.getProperty("user.name"))
    val deny = AclEntry.newBuilder()
        .setType(AclEntryType.DENY)
        .setPrincipal(user)
        .setPermissions(AclEntryPermission.LIST_DIRECTORY)
        .build()
    acl.acl = listOf(deny) + original
    return { acl.acl = original }
}

private fun denyPosixListing(directory: Path): () -> Unit {
    val original = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("-wx------"))
    return { Files.setPosixFilePermissions(directory, original) }
}
