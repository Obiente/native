package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.prefs.Preferences
import kotlin.concurrent.withLock

internal class DesktopHomeWorkspaceLayoutStorage(
    private val preferences: Preferences,
    private val lockFile: File,
) : HomeWorkspaceLayoutStorage {
    override fun read(persistenceKey: String): String? = preferences.get(persistenceKey, null)

    override fun write(persistenceKey: String, encodedSnapshot: String) {
        withExclusiveAccess {
            preferences.sync()
            preferences.put(persistenceKey, encodedSnapshot)
            preferences.flush()
        }
    }

    override fun writeIfAbsent(persistenceKey: String, encodedSnapshot: String): Boolean =
        withExclusiveAccess {
            preferences.sync()
            if (preferences.get(persistenceKey, null) != null) {
                false
            } else {
                preferences.put(persistenceKey, encodedSnapshot)
                preferences.flush()
                true
            }
        }

    fun removeAccount(accountScopeDigest: String, legacyAccountScopeDigest: String? = null) {
        val keys = homeWorkspaceAccountPersistenceKeys(accountScopeDigest, legacyAccountScopeDigest)
        withExclusiveAccess {
            preferences.sync()
            keys.forEach(preferences::remove)
            preferences.flush()
        }
    }

    private fun <T> withExclusiveAccess(operation: () -> T): T {
        val path = lockFile.toPath().toAbsolutePath().normalize()
        return desktopHomeWorkspaceProcessLocks.computeIfAbsent(path.toString()) { ReentrantLock() }.withLock {
            val parent = requireNotNull(path.parent) { "Home workspace storage needs a parent directory." }
            Files.createDirectories(parent)
            check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
                "Home workspace storage must use a real directory."
            }
            check(!Files.isSymbolicLink(path)) { "The home workspace lock cannot be a symbolic link." }
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { operation() }
            }
        }
    }
}

internal fun desktopHomeWorkspaceLockFile(
    osName: String = System.getProperty("os.name").lowercase(),
    userHome: File = File(System.getProperty("user.home")),
    environment: Map<String, String> = System.getenv(),
): File {
    val normalizedOsName = osName.lowercase()
    val stateRoot = when {
        normalizedOsName.contains("win") -> environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(userHome, "AppData/Local")
        normalizedOsName.contains("mac") -> File(userHome, "Library/Application Support")
        else -> environment["XDG_STATE_HOME"]?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(userHome, ".local/state")
    }
    return File(stateRoot, "Nextcloud Native/Home Workspace/preferences.lock")
}

private val desktopHomeWorkspaceProcessLocks = ConcurrentHashMap<String, ReentrantLock>()
