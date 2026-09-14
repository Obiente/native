package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal suspend fun <T> clearDesktopDynamicApiState(
    accountId: String,
    coalescer: DynamicApiRequestCoalescer<T>,
    cache: DynamicApiResponseCache,
    accountStorageKey: String? = null,
    memoryCache: DynamicNativeMemoryCache = sharedDynamicNativeMemoryCache,
) = coalescer.fenceAccount(accountId) {
    accountStorageKey?.let(memoryCache::retireAccount)
    cache.invalidateAccount(accountId)
}

internal fun desktopPendingDynamicMutationDirectory(
    osName: String = System.getProperty("os.name").orEmpty(),
    environment: Map<String, String> = System.getenv(),
    userHome: File = File(System.getProperty("user.home")),
): File = when {
    osName.startsWith("Windows", ignoreCase = true) -> {
        val localAppData = environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(userHome, "AppData/Local")
        File(localAppData, "Nextcloud Native/State/Pending Mutations")
    }
    osName.startsWith("Mac", ignoreCase = true) ->
        File(userHome, "Library/Application Support/Nextcloud Native/Pending Mutations")
    else -> {
        val stateRoot = environment["XDG_STATE_HOME"]?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(userHome, ".local/state")
        File(stateRoot, "nextcloud-native/pending-mutations-v1")
    }
}.absoluteFile

private val PENDING_MUTATION_DIRECTORY_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)
private val PENDING_MUTATION_FILE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

internal fun ensurePrivatePendingMutationDirectory(directory: File) {
    Files.createDirectories(directory.toPath())
    setPendingMutationPosixPermissions(directory.toPath(), PENDING_MUTATION_DIRECTORY_PERMISSIONS)
}

internal fun setPrivatePendingMutationFilePermissions(file: File) {
    setPendingMutationPosixPermissions(file.toPath(), PENDING_MUTATION_FILE_PERMISSIONS)
}

private fun setPendingMutationPosixPermissions(path: Path, permissions: Set<PosixFilePermission>) {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, permissions)
    }
}

private fun createPrivatePendingMutationTemporary(directory: File, targetName: String): Path {
    val directoryPath = directory.toPath()
    return if (Files.getFileStore(directoryPath).supportsFileAttributeView("posix")) {
        Files.createTempFile(
            directoryPath,
            "$targetName-",
            ".part",
            PosixFilePermissions.asFileAttribute(PENDING_MUTATION_FILE_PERMISSIONS),
        )
    } else {
        Files.createTempFile(directoryPath, "$targetName-", ".part")
    }
}

internal fun writePrivatePendingMutationFile(directory: File, target: File, bytes: ByteArray) {
    require(target.parentFile?.absoluteFile == directory.absoluteFile) {
        "The pending mutation target must be inside its private directory."
    }
    ensurePrivatePendingMutationDirectory(directory)
    val temporary = createPrivatePendingMutationTemporary(directory, target.name)
    try {
        FileOutputStream(temporary.toFile()).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        setPrivatePendingMutationFilePermissions(target)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun removeDesktopPendingDynamicMutations(directory: File, accountId: String) {
    require(accountId.isCanonicalGroupwareMutationAccountScope()) {
        "The pending mutation cleanup account identity is invalid."
    }
    val directoryPath = directory.toPath().toAbsolutePath().normalize()
    if (!Files.exists(directoryPath, LinkOption.NOFOLLOW_LINKS)) return
    check(Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directoryPath)) {
        "The pending mutation store is not a safe directory."
    }
    val ownedPrefix = "$accountId-"
    val ownedName = Regex(
        "^$accountId-[A-Za-z0-9._:-]{1,256}-[0-9a-f]{64}\\.json(?:-[^/]{1,128}\\.part)?$",
    )
    var deleted = false
    Files.newDirectoryStream(directoryPath).use { entries ->
        entries.forEach { entry ->
            val name = entry.fileName.toString()
            if (!name.startsWith(ownedPrefix)) return@forEach
            check(ownedName.matches(name)) { "The pending mutation store contains an unsafe account entry." }
            check(entry.toAbsolutePath().normalize().parent == directoryPath) {
                "The pending mutation entry escapes its private directory."
            }
            check(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry)) {
                "The pending mutation account entry is not a regular file."
            }
            check(Files.deleteIfExists(entry)) { "Could not delete a pending mutation account entry." }
            deleted = true
        }
    }
    if (deleted && Files.getFileStore(directoryPath).supportsFileAttributeView("posix")) {
        FileChannel.open(directoryPath, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }
    Files.newDirectoryStream(directoryPath).use { entries ->
        check(entries.none { it.fileName.toString().startsWith(ownedPrefix) }) {
            "Could not remove all pending mutation state for the account."
        }
    }
}
