package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal data class VirtualRangeRevision(
    val relativePath: String,
    val remoteRevision: String,
    val fileSize: Long,
) {
    init {
        FileOfflineKey("account", relativePath)
        require(remoteRevision.isNotBlank() && remoteRevision.none(Char::isISOControl))
        require(fileSize > 0L)
    }
}

internal fun desktopFileCacheAccountId(session: NextcloudSession): String =
    MessageDigest.getInstance("SHA-256")
        .digest("${session.serverUrl}\u0000${session.loginName}".encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun defaultDesktopFileReadCache(): DesktopFileReadCache =
    DesktopFileReadCache(File(desktopCacheRoot(), "nextcloud-native/files"))

internal fun defaultDesktopVirtualRangeCache(
    policy: () -> VirtualFileCachePolicy,
): DesktopVirtualRangeCache = DesktopVirtualRangeCache(
    root = File(desktopCacheRoot(), "nextcloud-native/virtual-ranges"),
    policy = policy,
)

internal fun purgeDesktopAccountCacheDirectory(root: File, accountId: String) {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    val directory = File(root, accountId)
    if (Files.notExists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
    require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory.toPath())) {
        "The desktop cache account directory is invalid."
    }
    val entries = Files.newDirectoryStream(directory.toPath()).use { stream -> stream.toList() }
    require(entries.all { entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) }) {
        "The desktop cache account directory contains an unsafe entry."
    }
    entries.forEach(Files::delete)
    syncDesktopCacheDirectory(directory)
    Files.delete(directory.toPath())
    syncDesktopCacheDirectory(root)
}

internal suspend fun removeDesktopAccountPrivateStorage(
    accountId: String,
    syncEngine: DesktopFileSyncEngine,
    files: DesktopFileReadCache,
    ranges: DesktopVirtualRangeCache,
) {
    syncEngine.removeAccountPairs(accountId)
    files.removeAccount(accountId)
    ranges.removeAccount(accountId)
}

private fun desktopCacheRoot(): File {
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
    return xdgCache?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
}

private fun syncDesktopCacheDirectory(directory: File) {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
    }
}
