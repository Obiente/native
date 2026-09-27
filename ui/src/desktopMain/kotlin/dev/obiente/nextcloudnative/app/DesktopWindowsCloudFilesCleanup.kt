package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.prefs.Preferences

internal fun windowsCloudFilesFailureAfterFallbackCleanup(
    providerFailure: String?,
    fallbackFailure: Throwable?,
    defaultMessage: String,
): String? = fallbackFailure?.let { failure ->
    providerFailure ?: failure.message ?: defaultMessage
}

internal const val KEY_WINDOWS_CLOUD_FILES_ROOT = "windows-cloud-files-root"
internal const val KEY_WINDOWS_CLOUD_FILES_ROOT_PREFIX = "wcfr."
internal const val WINDOWS_CLOUD_FILES_ROOT_SUFFIX = "-v2"
private const val WINDOWS_CLOUD_FILES_REMOVAL_RECOVERY_SUFFIX = "-removal-recovery"

internal fun desktopWindowsCloudFilesRoot(
    accountId: String,
    userHome: File = File(System.getProperty("user.home")),
): File {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    return File(File(userHome, "Nextcloud Native"), accountId + WINDOWS_CLOUD_FILES_ROOT_SUFFIX)
}

internal fun windowsCloudFilesRootPreferenceKey(accountId: String): String {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    return "$KEY_WINDOWS_CLOUD_FILES_ROOT_PREFIX$accountId".also { key ->
        check(key.length <= Preferences.MAX_KEY_LENGTH)
    }
}

internal fun desktopLegacyWindowsCloudFilesRoot(accountId: String, userHome: File): File =
    File(File(userHome, "Nextcloud Native"), accountId)

internal fun unregisterSupersededWindowsCloudFilesRoot(
    preferences: Preferences,
    accountId: String,
    userHome: File,
    api: WindowsCloudFilesApi,
) {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    val legacyRoot = validatedWindowsCloudFilesRoot(desktopLegacyWindowsCloudFilesRoot(accountId, userHome), userHome)
    api.unregisterSyncRoot(legacyRoot)
    clearWindowsCloudFilesRootPreferences(preferences, accountId, legacyRoot)
}

internal fun clearWindowsCloudFilesRootPreferences(
    preferences: Preferences,
    accountId: String,
    removedRoot: Path,
) {
    listOf(KEY_WINDOWS_CLOUD_FILES_ROOT, windowsCloudFilesRootPreferenceKey(accountId)).forEach { key ->
        val savedRoot = preferences.get(key, null)
            ?.let(::File)
            ?.toPath()
            ?.toAbsolutePath()
            ?.normalize()
        if (savedRoot == removedRoot) preferences.remove(key)
    }
}

internal fun unregisterWindowsCloudFilesRootForUninstall(
    preferences: Preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative"),
    userHome: File = File(System.getProperty("user.home")),
    apiFactory: () -> WindowsCloudFilesApi = ::JnaWindowsCloudFilesApi,
) {
    val rootsByPreference = linkedMapOf<Path, MutableSet<String>>()
    fun addRoot(root: File?, preferenceKey: String? = null) {
        if (root == null) return
        val validated = validatedWindowsCloudFilesRoot(root, userHome)
        rootsByPreference.getOrPut(validated) { linkedSetOf() }
            .apply { preferenceKey?.let(::add) }
    }
    addRoot(
        preferences.get(KEY_WINDOWS_CLOUD_FILES_ROOT, null)?.let(::File),
        KEY_WINDOWS_CLOUD_FILES_ROOT,
    )
    preferences.keys().filter { it.startsWith(KEY_WINDOWS_CLOUD_FILES_ROOT_PREFIX) }.forEach { key ->
        addRoot(preferences.get(key, null)?.let(::File), key)
    }
    val sessionAccountId = preferences.get("server", null)?.let { server ->
        preferences.get("login", null)?.let { login ->
            desktopFileCacheAccountId(NextcloudSession(server, login, "unused"))
        }
    }
    sessionAccountId?.let { accountId ->
        addRoot(
            desktopWindowsCloudFilesRoot(accountId, userHome),
            windowsCloudFilesRootPreferenceKey(accountId),
        )
        addRoot(desktopLegacyWindowsCloudFilesRoot(accountId, userHome))
    }
    if (rootsByPreference.isEmpty()) return
    val api = apiFactory()
    var firstFailure: Throwable? = null
    try {
        rootsByPreference.entries
            .sortedByDescending { (root) -> root.fileName.toString().endsWith(WINDOWS_CLOUD_FILES_ROOT_SUFFIX) }
            .forEach { (root, preferenceKeys) ->
                runCatching { api.unregisterSyncRoot(root) }
                    .onSuccess { preferenceKeys.forEach(preferences::remove) }
                    .onFailure { failure -> if (firstFailure == null) firstFailure = failure }
            }
    } finally {
        api.close()
    }
    firstFailure?.let { throw it }
}

internal fun unregisterWindowsCloudFilesRootsForAccountRemoval(
    preferences: Preferences,
    accountId: String,
    userHome: File = File(System.getProperty("user.home")),
    apiFactory: () -> WindowsCloudFilesApi = ::JnaWindowsCloudFilesApi,
) {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    val currentRoot = validatedWindowsCloudFilesRoot(desktopWindowsCloudFilesRoot(accountId, userHome), userHome)
    val legacyRoot = validatedWindowsCloudFilesRoot(desktopLegacyWindowsCloudFilesRoot(accountId, userHome), userHome)
    val roots = listOf(currentRoot, legacyRoot)
    val recoveryRoot = windowsCloudFilesRemovalRecoveryRoot(accountId, userHome)
    val existingRecoveryRoot = persistedWindowsCloudFilesPreservedRoot(preferences, accountId)
    check(existingRecoveryRoot == null || existingRecoveryRoot == recoveryRoot) {
        "Review and acknowledge the previous preserved Windows Cloud Files folder before removing this account."
    }
    val api = apiFactory()
    var firstFailure: Throwable? = null
    try {
        roots.forEach { root ->
            runCatching { api.unregisterSyncRoot(root) }
                .onFailure { failure -> if (firstFailure == null) firstFailure = failure }
        }
        firstFailure?.let { throw it }
        removeOrPreserveWindowsCloudFilesRoots(
            preferences = preferences,
            accountId = accountId,
            recoveryRoot = recoveryRoot,
            roots = roots,
            api = api,
        )
        roots.forEach { root -> clearWindowsCloudFilesRootPreferences(preferences, accountId, root) }
    } finally {
        api.close()
    }
}

private fun windowsCloudFilesRemovalRecoveryRoot(accountId: String, userHome: File): Path =
    File(File(userHome, "Nextcloud Native"), accountId + WINDOWS_CLOUD_FILES_REMOVAL_RECOVERY_SUFFIX)
        .toPath()
        .toAbsolutePath()
        .normalize()

private fun removeOrPreserveWindowsCloudFilesRoots(
    preferences: Preferences,
    accountId: String,
    recoveryRoot: Path,
    roots: List<Path>,
    api: WindowsCloudFilesApi,
) {
    val stagedRoots = roots.mapIndexed { index, root -> root to recoveryRoot.resolve("root-$index") }
    val recoveryRegistered = persistedWindowsCloudFilesPreservedRoot(preferences, accountId) == recoveryRoot
    if (stagedRoots.none { (root, staged) ->
            Files.exists(root, LinkOption.NOFOLLOW_LINKS) || Files.exists(staged, LinkOption.NOFOLLOW_LINKS)
        } && !recoveryRegistered
    ) return
    if (Files.notExists(recoveryRoot, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectory(recoveryRoot)
        try {
            persistWindowsCloudFilesPreservedRoot(preferences, accountId, recoveryRoot)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(recoveryRoot) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    } else {
        check(recoveryRegistered) { "The Windows Cloud Files removal recovery folder is not owned by this account." }
        check(Files.isDirectory(recoveryRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(recoveryRoot)) {
            "The Windows Cloud Files removal recovery folder is invalid."
        }
        persistWindowsCloudFilesPreservedRoot(preferences, accountId, recoveryRoot)
    }
    stagedRoots.forEach { (root, staged) ->
        val sourceExists = Files.exists(root, LinkOption.NOFOLLOW_LINKS)
        val stagedExists = Files.exists(staged, LinkOption.NOFOLLOW_LINKS)
        check(!sourceExists || !stagedExists) { "Windows Cloud Files removal found duplicate recovery roots." }
        if (sourceExists) Files.move(root, staged)
        if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS) && windowsCloudFilesTreeIsDisposable(staged, api)) {
            deleteWindowsCloudFilesTree(staged)
        }
    }
    Files.list(recoveryRoot).use { entries ->
        if (entries.findAny().isPresent) return
    }
    Files.delete(recoveryRoot)
    acknowledgeWindowsCloudFilesPreservedRoot(preferences, accountId)
}

private fun windowsCloudFilesTreeIsDisposable(root: Path, api: WindowsCloudFilesApi): Boolean =
    runCatching {
        Files.walk(root).use { entries ->
            entries.filter { path -> path != root }.allMatch { path ->
                !Files.isSymbolicLink(path) &&
                    api.inspectPlaceholder(path).state == WindowsCloudPlaceholderEntryState.InSync
            }
        }
    }.getOrDefault(false)

private fun deleteWindowsCloudFilesTree(root: Path) {
    Files.walk(root).use { entries ->
        entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

internal fun validatedWindowsCloudFilesRoot(root: File, userHome: File): Path {
    val expectedParent = File(userHome, "Nextcloud Native").toPath().toAbsolutePath().normalize()
    val normalizedRoot = root.toPath().toAbsolutePath().normalize()
    val name = normalizedRoot.fileName.toString()
    val accountId = name.removeSuffix(WINDOWS_CLOUD_FILES_ROOT_SUFFIX)
    check(
        normalizedRoot.parent == expectedParent &&
            accountId.length == 64 &&
            accountId.all { it in '0'..'9' || it in 'a'..'f' } &&
            (name == accountId || name == accountId + WINDOWS_CLOUD_FILES_ROOT_SUFFIX),
    ) { "The stored Windows Cloud Files root is invalid." }
    return normalizedRoot
}
