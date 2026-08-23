package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.CachedDynamicApiResponse
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.FileVerifiedContractCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal fun talkMessageHistoryPath(
    token: String,
    olderCursor: Long?,
    limit: Int,
): String {
    require(limit in 1..MAX_TALK_MESSAGE_PAGE_SIZE) {
        "Talk message page size must be between 1 and $MAX_TALK_MESSAGE_PAGE_SIZE."
    }
    require(olderCursor == null || olderCursor >= 0L) {
        "Talk history cursor must not be negative."
    }
    val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20")
    return "/ocs/v2.php/apps/spreed/api/v1/chat/$encodedToken" +
        "?format=json&lookIntoFuture=0&limit=$limit&lastKnownMessageId=${olderCursor ?: 0L}" +
        "&includeLastKnown=0&setReadMarker=0&markNotificationsAsRead=0&noStatusUpdate=1"
}

internal const val NOTES_LIST_RELATIVE_PATH = "/index.php/apps/notes/api/v1/notes?exclude=content"

internal fun <T> invokeOnSwingEventThread(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val outcome = AtomicReference<Result<T>>()
    SwingUtilities.invokeAndWait { outcome.set(runCatching(action)) }
    return outcome.get().getOrThrow()
}

internal fun notesDetailRelativePath(noteId: Long): String {
    require(noteId >= 0L) { "The note ID is invalid." }
    return "/index.php/apps/notes/api/v1/notes/$noteId"
}

internal fun notesConditionalHeaders(expectedEtag: String?): Map<String, String> =
    expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-None-Match" to it) }.orEmpty()

internal fun resolvedNoteEtag(responseEtag: String?, documentEtag: String?): String? =
    responseEtag?.takeIf(String::isNotBlank) ?: documentEtag?.takeIf(String::isNotBlank)

internal const val DIRECT_EDITING_INFO_RELATIVE_PATH =
    "/ocs/v2.php/apps/files/api/v1/directEditing?format=json"

internal const val NEXTCLOUD_CAPABILITIES_RELATIVE_PATH =
    "/ocs/v1.php/cloud/capabilities?format=json"

internal fun resolveDesktopNextcloudRedirectLocation(
    requestUrl: HttpUrl,
    serverUrl: String,
    location: String?,
): String? {
    val target = location?.let(requestUrl::resolve) ?: return null
    if (target.fragment != null) return null
    val account = serverUrl.toHttpUrlOrNull() ?: return null
    if (
        target.scheme != account.scheme ||
        target.host != account.host ||
        target.port != account.port
    ) {
        return null
    }
    val accountPath = account.encodedPath.trimEnd('/').takeUnless { it == "/" }.orEmpty()
    if (
        accountPath.isNotEmpty() &&
        target.encodedPath != accountPath &&
        !target.encodedPath.startsWith("$accountPath/")
    ) {
        return null
    }
    val relativePath = target.encodedPath.removePrefix(accountPath)
    if (!relativePath.startsWith('/') || relativePath.startsWith("//")) return null
    return buildString {
        append(relativePath)
        target.encodedQuery?.let { query ->
            append('?')
            append(query)
        }
    }
}

internal const val DIRECT_EDITING_OPEN_RELATIVE_PATH =
    "/ocs/v2.php/apps/files/api/v1/directEditing/open?format=json"

private enum class DesktopFileSyncRunSource {
    Background,
    Resume,
    Tray,
}

private const val MAX_DOCUMENT_TEMPLATE_ID_LENGTH = 256
private const val MAX_DOCUMENT_TEMPLATE_NAME_LENGTH = 512
private const val MAX_DOCUMENT_TEMPLATE_EXTENSION_LENGTH = 32
private const val VIRTUAL_FOLDER_HYDRATION_CHUNK_BYTES = 1024 * 1024
private const val MAX_VIRTUAL_FOLDER_DISCOVERED_ENTRIES = 100_000
private const val MAX_VIRTUAL_FOLDER_STABILITY_ATTEMPTS = 3
private const val VIRTUAL_FOLDER_REFRESH_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L
private const val VIRTUAL_FOLDER_REFRESH_RETRY_MILLIS = 30L * 60L * 1_000L
private const val KEY_WINDOWS_CLOUD_FILES_ROOT = "windows-cloud-files-root"
private const val KEY_WINDOWS_CLOUD_FILES_ROOT_PREFIX = "wcfr."
private const val KEY_WINDOWS_CLOUD_FILES_PRESERVED_ROOT_PREFIX = "wcfpr."
private const val KEY_WINDOWS_CLOUD_FILES_RECOVERY_CURSOR = "windows-cloud-files-recovery-cursor"
private const val MAX_WINDOWS_CLOUD_FILES_RECOVERY_ROOTS_PER_ATTEMPT = 16
private const val KEY_VIRTUAL_FILE_ROOT_PREFIX = "vfp-root."
private const val KEY_VIRTUAL_FILE_PRIMARY_CACHE_PREFIX = "vfpc-primary."
private const val KEY_VIRTUAL_FILE_OVERFLOW_CACHE_PREFIX = "vfpc-overflow."
private const val VIRTUAL_FILE_PRIMARY_PREFERENCE_VERSION = "v2"
private const val VIRTUAL_FILE_OVERFLOW_PREFERENCE_VERSION = "v2"
private const val WINDOWS_CLOUD_FILES_ROOT_SUFFIX = "-v2"

private fun isLinuxDesktop(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("linux")

private fun desktopVirtualFileProviderLocation(
    preferences: Preferences,
    accountId: String,
    userHome: File = File(System.getProperty("user.home")),
): VirtualFileProviderLocation {
    val stored = preferences.get(virtualFileProviderRootPreferenceKey(accountId), null)
        ?.takeIf { path -> path.length <= Preferences.MAX_VALUE_LENGTH }
        ?.let(::File)
        ?.absoluteFile
        ?.normalize()
    val folderName = stored?.name?.takeIf(String::isValidVirtualFileProviderFolderName)
    val parent = stored?.parentFile
    return if (folderName != null && parent != null) {
        VirtualFileProviderLocation(parent.absolutePath, folderName)
    } else {
        VirtualFileProviderLocation(userHome.absolutePath, "Nextcloud Native")
    }
}

private fun desktopLinuxVirtualFileMountPoint(
    preferences: Preferences,
    accountId: String,
): File = desktopVirtualFileProviderLocation(preferences, accountId).let { location ->
    File(location.parentPath, location.folderName).absoluteFile.normalize()
}

private fun virtualFileProviderRootPreferenceKey(accountId: String): String {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    return "$KEY_VIRTUAL_FILE_ROOT_PREFIX$accountId".also { key -> check(key.length <= Preferences.MAX_KEY_LENGTH) }
}

private fun virtualFileCachePreferenceKey(prefix: String, accountId: String): String {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    return "$prefix$accountId".also { key -> check(key.length <= Preferences.MAX_KEY_LENGTH) }
}

private data class DesktopVirtualFileCacheTiers(
    val configuration: VirtualFileCacheTierConfiguration,
    val primaryIdentity: String?,
    val primaryIdentityRequired: Boolean,
    val overflowIdentity: String?,
)

private fun encodeDesktopVirtualFilePrimaryPreference(path: String, identity: String): String {
    require(identity.isValidDesktopVirtualCacheRootIdentity())
    return "$VIRTUAL_FILE_PRIMARY_PREFERENCE_VERSION:$identity:$path".also { encoded ->
        require(encoded.length <= Preferences.MAX_VALUE_LENGTH) { "The selected primary cache path is too long." }
    }
}

private fun decodeDesktopVirtualFilePrimaryPreference(value: String): Pair<String, String?>? {
    val prefix = "$VIRTUAL_FILE_PRIMARY_PREFERENCE_VERSION:"
    if (!value.startsWith(prefix)) return value to null
    val identityEnd = value.indexOf(':', prefix.length)
    if (identityEnd < 0) return null
    val identity = value.substring(prefix.length, identityEnd)
    val path = value.substring(identityEnd + 1)
    return if (identity.isValidDesktopVirtualCacheRootIdentity() && path.isNotBlank()) {
        path to identity
    } else {
        null
    }
}

private fun encodeDesktopVirtualFileOverflowPreference(path: String, identity: String): String {
    require(identity.isValidDesktopVirtualCacheRootIdentity())
    return "$VIRTUAL_FILE_OVERFLOW_PREFERENCE_VERSION:$identity:$path".also { encoded ->
        require(encoded.length <= Preferences.MAX_VALUE_LENGTH) { "The selected overflow cache path is too long." }
    }
}

private fun decodeDesktopVirtualFileOverflowPreference(value: String): Pair<String, String?>? {
    val prefix = "$VIRTUAL_FILE_OVERFLOW_PREFERENCE_VERSION:"
    if (!value.startsWith(prefix)) return value to null
    val identityEnd = value.indexOf(':', prefix.length)
    if (identityEnd < 0) return null
    val identity = value.substring(prefix.length, identityEnd)
    val path = value.substring(identityEnd + 1)
    return if (identity.isValidDesktopVirtualCacheRootIdentity() && path.isNotBlank()) {
        path to identity
    } else {
        null
    }
}

private fun desktopVirtualFileCacheTiers(
    preferences: Preferences,
    accountId: String,
): DesktopVirtualFileCacheTiers {
    val providerLocation = desktopVirtualFileProviderLocation(preferences, accountId)
    val defaultPrimary = File(providerLocation.parentPath, INTERNAL_VIRTUAL_FILE_CACHE_FOLDER_NAME)
        .absoluteFile.normalize().path
    fun normalizedStoredPath(value: String?): String? = value
        ?.takeIf { it.length <= Preferences.MAX_VALUE_LENGTH }
        ?.let(::File)
        ?.absoluteFile
        ?.normalize()
        ?.path
    val storedPrimaryPreference = preferences
        .get(virtualFileCachePreferenceKey(KEY_VIRTUAL_FILE_PRIMARY_CACHE_PREFIX, accountId), null)
        ?.takeIf { it.length <= Preferences.MAX_VALUE_LENGTH }
        ?.let(::decodeDesktopVirtualFilePrimaryPreference)
    val storedPrimary = normalizedStoredPath(storedPrimaryPreference?.first)
    var primaryIdentity = storedPrimaryPreference?.second
    if (storedPrimary != null && primaryIdentity == null) {
        primaryIdentity = DesktopVirtualRangeCache.adoptPrimaryRootIdentity(File(storedPrimary))
        if (primaryIdentity != null) {
            preferences.put(
                virtualFileCachePreferenceKey(KEY_VIRTUAL_FILE_PRIMARY_CACHE_PREFIX, accountId),
                encodeDesktopVirtualFilePrimaryPreference(storedPrimary, primaryIdentity),
            )
            preferences.flush()
        }
    }
    val storedOverflow = preferences
        .get(virtualFileCachePreferenceKey(KEY_VIRTUAL_FILE_OVERFLOW_CACHE_PREFIX, accountId), null)
        ?.takeIf { it.length <= Preferences.MAX_VALUE_LENGTH }
        ?.let(::decodeDesktopVirtualFileOverflowPreference)
    val overflowPath = normalizedStoredPath(storedOverflow?.first)
    var overflowIdentity = storedOverflow?.second
    if (overflowPath != null && overflowIdentity == null) {
        overflowIdentity = DesktopVirtualRangeCache.adoptOverflowRootIdentity(File(overflowPath))
        if (overflowIdentity != null) {
            preferences.put(
                virtualFileCachePreferenceKey(KEY_VIRTUAL_FILE_OVERFLOW_CACHE_PREFIX, accountId),
                encodeDesktopVirtualFileOverflowPreference(overflowPath, overflowIdentity),
            )
            preferences.flush()
        }
    }
    return DesktopVirtualFileCacheTiers(
        configuration = VirtualFileCacheTierConfiguration(
            primaryPath = storedPrimary ?: defaultPrimary,
            overflowPath = overflowPath,
        ),
        primaryIdentity = primaryIdentity,
        primaryIdentityRequired = storedPrimary != null,
        overflowIdentity = overflowIdentity,
    )
}

internal fun validateDesktopVirtualFileCacheTierPath(path: String): Path {
    require(path.isValidVirtualFileCachePath()) { "Choose a valid local cache folder." }
    val target = File(path).toPath().toAbsolutePath().normalize()
    require(target.toString().length <= Preferences.MAX_VALUE_LENGTH) { "The selected cache path is too long." }
    val parent = target.parent ?: error("Choose a cache folder below a local drive root.")
    require(Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
        "Choose a cache folder on an available local drive, not a symbolic link."
    }
    require(Files.isWritable(parent)) { "The selected cache drive is not writable." }
    if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        require(Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
            "The selected cache location is not a regular directory."
        }
        require(Files.isWritable(target)) { "The selected cache location is not writable." }
    }
    return target
}

internal fun desktopVirtualFileCacheTierPathsOverlap(first: Path, second: Path): Boolean {
    fun filesystemPath(path: Path): Path = if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        path.toRealPath()
    } else {
        requireNotNull(path.parent).toRealPath().resolve(path.fileName).normalize()
    }
    fun sameExistingDirectoryOrAncestor(ancestor: Path, candidate: Path): Boolean {
        if (!Files.exists(ancestor) || !Files.exists(candidate)) return false
        var current: Path? = candidate
        while (current != null) {
            if (runCatching { Files.isSameFile(ancestor, current) }.getOrDefault(false)) return true
            current = current.parent
        }
        return false
    }
    val resolvedFirst = filesystemPath(first)
    val resolvedSecond = filesystemPath(second)
    return resolvedFirst == resolvedSecond ||
        resolvedFirst.startsWith(resolvedSecond) ||
        resolvedSecond.startsWith(resolvedFirst) ||
        sameExistingDirectoryOrAncestor(first, second) ||
        sameExistingDirectoryOrAncestor(second, first)
}

internal fun validateDesktopVirtualFileProviderLocation(location: VirtualFileProviderLocation): Path {
    val parent = File(location.parentPath).toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
        "Choose an existing local drive or folder, not a symbolic link."
    }
    require(Files.isWritable(parent)) { "The selected location is not writable." }
    val target = parent.resolve(location.folderName).normalize()
    require(target.parent == parent) { "The virtual file folder must stay inside the selected location." }
    require(target.toString().length <= Preferences.MAX_VALUE_LENGTH) { "The selected location path is too long." }
    requireValidDesktopVirtualFileCacheRoot(parent)
    if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        require(Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
            "The selected virtual file folder is not a regular directory."
        }
        require(Files.list(target).use { entries -> !entries.findAny().isPresent }) {
            "The selected virtual file folder must be empty before it can be connected."
        }
    }
    return target
}

internal fun desktopVirtualFileCacheRootChanges(
    current: VirtualFileProviderLocation,
    target: Path,
): Boolean = File(current.parentPath).toPath().toAbsolutePath().normalize() != target.parent

internal fun requireValidDesktopVirtualFileCacheRoot(parent: Path) {
    val cacheRoot = parent.resolve(INTERNAL_VIRTUAL_FILE_CACHE_FOLDER_NAME)
    if (Files.notExists(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    require(
        Files.isDirectory(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(cacheRoot),
    ) { "The selected location contains an invalid Nextcloud Native cache folder." }
}

internal fun hasInvalidDesktopVirtualFileCacheRoot(parent: Path): Boolean {
    if (
        !Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(parent)
    ) return false
    val cacheRoot = parent.resolve(INTERNAL_VIRTUAL_FILE_CACHE_FOLDER_NAME)
    return Files.exists(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
        (
            !Files.isDirectory(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(cacheRoot)
            )
}

internal fun virtualFileLocationActionMessage(prefix: String, targetPath: String): String {
    require(prefix.isNotBlank())
    require(targetPath.isNotBlank())
    val available = MAX_VIRTUAL_FILE_ACTION_MESSAGE_LENGTH - prefix.length - 1
    require(available >= 4)
    val displayedTarget = if (targetPath.length <= available) {
        targetPath
    } else {
        val tailLength = (available - 3).coerceAtLeast(0)
        "...${targetPath.takeLast(tailLength)}"
    }
    return "$prefix$displayedTarget."
}

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

internal fun windowsCloudFilesPreservedRootPreferenceKey(accountId: String): String {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    return "$KEY_WINDOWS_CLOUD_FILES_PRESERVED_ROOT_PREFIX$accountId".also { key ->
        check(key.length <= Preferences.MAX_KEY_LENGTH)
    }
}

internal fun persistWindowsCloudFilesPreservedRoot(
    preferences: Preferences,
    accountId: String,
    preservedRoot: Path,
) {
    val normalized = preservedRoot.toAbsolutePath().normalize()
    require(
        normalized.toString().length <= Preferences.MAX_VALUE_LENGTH &&
            Files.isDirectory(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(normalized),
    ) { "The preserved Windows Cloud Files root is not a safe local directory." }
    val existing = persistedWindowsCloudFilesPreservedRoot(preferences, accountId)
    require(existing == null || existing == normalized) {
        "Review and acknowledge the previous preserved Windows Cloud Files folder before retrying recovery."
    }
    val key = windowsCloudFilesPreservedRootPreferenceKey(accountId)
    preferences.put(key, normalized.toString())
    try {
        preferences.flush()
    } catch (failure: Throwable) {
        preferences.remove(key)
        runCatching(preferences::flush)
        throw failure
    }
}

internal fun persistedWindowsCloudFilesPreservedRoot(
    preferences: Preferences,
    accountId: String,
): Path? = preferences.get(windowsCloudFilesPreservedRootPreferenceKey(accountId), null)
    ?.takeIf { value ->
        value.isNotBlank() && value.length <= Preferences.MAX_VALUE_LENGTH && value.none(Char::isISOControl)
    }
    ?.let { value -> runCatching { File(value).toPath().normalize() }.getOrNull() }
    ?.takeIf(Path::isAbsolute)

internal fun acknowledgeWindowsCloudFilesPreservedRoot(
    preferences: Preferences,
    accountId: String,
) {
    preferences.remove(windowsCloudFilesPreservedRootPreferenceKey(accountId))
    preferences.flush()
}

internal fun windowsCloudFilesRecoveryNoticeMessage(preservedRoot: Path): String = virtualFileLocationActionMessage(
    prefix = "Windows found unreadable Cloud Files metadata and preserved existing local data at ",
    targetPath = preservedRoot.toAbsolutePath().normalize().toString(),
)

internal fun persistedWindowsCloudFilesRecoveryNotice(
    preferences: Preferences,
    accountId: String,
): String? = persistedWindowsCloudFilesPreservedRoot(preferences, accountId)
    ?.let(::windowsCloudFilesRecoveryNoticeMessage)

internal fun persistedWindowsCloudFilesRecoveryRoots(
    preferences: Preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative"),
): Map<String, Path> = preferences.keys()
    .asSequence()
    .filter { key -> key.startsWith(KEY_WINDOWS_CLOUD_FILES_ROOT_PREFIX) }
    .mapNotNull { key ->
        val accountId = key.removePrefix(KEY_WINDOWS_CLOUD_FILES_ROOT_PREFIX)
        if (accountId.length != 64 || accountId.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return@mapNotNull null
        }
        val value = preferences.get(key, null)
            ?.takeIf { it.length <= Preferences.MAX_VALUE_LENGTH }
            ?: return@mapNotNull null
        val path = runCatching { File(value).toPath().normalize() }.getOrNull()
            ?.takeIf(Path::isAbsolute)
            ?: return@mapNotNull null
        if (
            !Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path)
        ) {
            return@mapNotNull null
        }
        accountId to path
    }
    .toMap()

internal fun pageWindowsCloudFilesRecoveryRoots(
    roots: Map<String, Path>,
    startAfterAccountId: String?,
    limit: Int = MAX_WINDOWS_CLOUD_FILES_RECOVERY_ROOTS_PER_ATTEMPT,
): Map<String, Path> {
    require(limit > 0)
    if (roots.isEmpty()) return emptyMap()
    val ordered = roots.entries.sortedBy(Map.Entry<String, Path>::key)
    val startIndex = startAfterAccountId
        ?.let { cursor -> ordered.indexOfFirst { it.key > cursor } }
        ?.takeIf { it >= 0 }
        ?: 0
    return (0 until minOf(limit, ordered.size))
        .map { offset -> ordered[(startIndex + offset) % ordered.size] }
        .associate(Map.Entry<String, Path>::toPair)
}

internal fun pagedPersistedWindowsCloudFilesRecoveryRoots(
    preferences: Preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative"),
): Map<String, Path> {
    val page = pageWindowsCloudFilesRecoveryRoots(
        roots = persistedWindowsCloudFilesRecoveryRoots(preferences),
        startAfterAccountId = preferences.get(KEY_WINDOWS_CLOUD_FILES_RECOVERY_CURSOR, null),
    )
    if (page.isEmpty()) {
        preferences.remove(KEY_WINDOWS_CLOUD_FILES_RECOVERY_CURSOR)
    } else {
        preferences.put(KEY_WINDOWS_CLOUD_FILES_RECOVERY_CURSOR, page.keys.last())
    }
    return page
}

private fun desktopLegacyWindowsCloudFilesRoot(accountId: String, userHome: File): File =
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

private fun clearWindowsCloudFilesRootPreferences(
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

private fun validatedWindowsCloudFilesRoot(root: File, userHome: File): Path {
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

internal fun virtualFileProviderPreferenceKey(accountId: String): String {
    require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
    return "vfp-active.$accountId".also { key ->
        check(key.length <= Preferences.MAX_KEY_LENGTH)
    }
}

internal fun documentTemplatesRelativePath(editorId: String, creatorId: String): String {
    require(editorId.isSafeDocumentCapabilityId()) { "The document editor ID is invalid." }
    require(creatorId.isSafeDocumentCapabilityId()) { "The document creator ID is invalid." }
    return "/ocs/v2.php/apps/files/api/v1/directEditing/templates/$editorId/$creatorId?format=json"
}

internal fun legacyRichdocumentsTemplatesRelativePath(creatorId: String): String {
    require(creatorId.isSafeDocumentCapabilityId()) { "The document creator ID is invalid." }
    return "/ocs/v2.php/apps/richdocuments/api/v1/templates/$creatorId?format=json"
}

internal fun documentEditingConditionalHeaders(expectedEtag: String?): Map<String, String> =
    expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-None-Match" to it) }.orEmpty()

internal fun parseDesktopDocumentEditingCapabilities(
    body: String,
    supportsFileId: Boolean = false,
): NextcloudDocumentEditingCapabilities {
    val data = JSONObject(body).getJSONObject("ocs").getJSONObject("data")
    val editorObject = data.optJSONObject("editors") ?: JSONObject()
    val creatorObject = data.optJSONObject("creators") ?: JSONObject()
    val editors = editorObject.keys().asSequence().mapNotNull { key ->
        val item = editorObject.optJSONObject(key) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        id to NextcloudDocumentEditorCapability(
            id = id,
            displayName = item.optString("name").ifBlank { id },
            mimeTypes = item.optJSONArray("mimetypes").toStringSet(),
            optionalMimeTypes = item.optJSONArray("optionalMimetypes").toStringSet(),
            secure = item.optBoolean("secure", false),
        )
    }.toMap()
    val creators = creatorObject.keys().asSequence().mapNotNull { key ->
        val item = creatorObject.optJSONObject(key) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val editorId = item.optString("editor").takeIf(String::isNotBlank) ?: return@mapNotNull null
        id to NextcloudDocumentCreatorCapability(
            id = id,
            editorId = editorId,
            displayName = item.optString("name").ifBlank { id },
            extension = item.optString("extension"),
            templates = item.optBoolean("templates", false),
            mimeType = item.optString("mimetype").takeIf(String::isNotBlank)
                ?: item.optJSONArray("mimetypes")?.optString(0)?.takeIf(String::isNotBlank),
        )
    }.toMap()
    return NextcloudDocumentEditingCapabilities(editors, creators, supportsFileId)
}

internal fun parseDesktopDirectEditingSupportsFileId(body: String): Boolean =
    JSONObject(body)
        .getJSONObject("ocs")
        .getJSONObject("data")
        .getJSONObject("capabilities")
        .optJSONObject("files")
        ?.optJSONObject("directEditing")
        ?.optBoolean("supportsFileId", false)
        ?: false

internal fun parseDesktopDocumentTemplates(
    body: String,
    creatorId: String,
): List<NextcloudDocumentTemplate> {
    require(creatorId.isSafeDocumentCapabilityId()) { "The document creator ID is invalid." }
    val data = JSONObject(body).getJSONObject("ocs").get("data")
    val templates = if (data is JSONObject && data.has("templates")) data.get("templates") else data
    val items = when (templates) {
        is JSONArray -> buildList {
            for (index in 0 until templates.length()) {
                templates.optJSONObject(index)?.let(::add)
            }
        }
        is JSONObject -> templates.keys().asSequence().mapNotNull { key ->
            templates.optJSONObject(key)?.also { item ->
                if (!item.has("id")) item.put("id", key)
            }
        }.toList()
        else -> emptyList()
    }
    return items.mapNotNull { item ->
        val id = item.opt("id")?.toString()?.takeIf {
            it.isNotBlank() && it.length <= MAX_DOCUMENT_TEMPLATE_ID_LENGTH && it.none(Char::isISOControl)
        } ?: return@mapNotNull null
        val displayName = (item.optString("title").ifBlank { item.optString("name") })
            .takeIf { it.isNotBlank() && it.length <= MAX_DOCUMENT_TEMPLATE_NAME_LENGTH && it.none(Char::isISOControl) }
            ?: "Template"
        val extension = item.optString("extension")
            .trim()
            .trimStart('.')
            .takeIf { it.length <= MAX_DOCUMENT_TEMPLATE_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit) }
            .orEmpty()
        NextcloudDocumentTemplate(
            id = id,
            displayName = displayName,
            extension = extension,
            creatorId = creatorId,
            mimeType = item.optString("mimetype").takeIf(String::isNotBlank)
                ?: item.optString("mimeType").takeIf(String::isNotBlank),
        )
    }
}

internal fun directEditingOpenForm(request: NextcloudDocumentEditSessionRequest): String {
    require(request.path.isSafeDocumentLookupPath()) { "The document path is unsafe." }
    require(request.fileId >= 0L) { "The document ID is invalid." }
    require(request.editorId in TRUSTED_DIRECT_EDITING_EDITOR_IDS) {
        "The document editor is not trusted."
    }
    require(request.expectedEtag.isNotBlank()) { "The document version is missing." }
    return listOf(
        "path" to request.path,
        "editorId" to request.editorId,
        "fileId" to request.fileId.toString(),
    ).joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

private val TRUSTED_DIRECT_EDITING_EDITOR_IDS = setOf(
    OFFICE_DIRECT_EDITOR_ID,
    WHITEBOARD_DIRECT_EDITOR_ID,
)

internal fun validatedDirectEditingHandoffUrl(serverUrl: String, candidate: String): String {
    require(candidate.isNotBlank() && candidate.none(Char::isISOControl)) {
        "Nextcloud returned an invalid direct-editing handoff."
    }
    val server = URI(serverUrl.trimEnd('/') + "/")
    require(server.scheme.equals("https", ignoreCase = true) && !server.host.isNullOrBlank()) {
        "The Nextcloud account origin is invalid."
    }
    val resolved = server.resolve(candidate)
    require(
        resolved.scheme.equals(server.scheme, ignoreCase = true) &&
            resolved.host.equals(server.host, ignoreCase = true) &&
            resolved.effectivePort() == server.effectivePort() &&
            resolved.userInfo == null &&
            resolved.rawQuery == null &&
            resolved.rawFragment == null,
    ) {
        "Nextcloud returned a cross-origin direct-editing handoff."
    }
    val routePrefix = server.rawPath.trimEnd('/') + "/index.php/apps/files/directEditing/"
    val rawPath = resolved.rawPath
    val token = rawPath.removePrefix(routePrefix)
    require(
        rawPath.startsWith(routePrefix) &&
            token.isNotBlank() &&
            '/' !in token &&
            '\\' !in token &&
            !token.contains("%2e", ignoreCase = true) &&
            !token.contains("%2f", ignoreCase = true) &&
            !token.contains("%5c", ignoreCase = true),
    ) {
        "Nextcloud returned an unexpected direct-editing handoff route."
    }
    return resolved.toASCIIString()
}

private fun URI.effectivePort(): Int = if (port >= 0) port else when (scheme.lowercase()) {
    "https" -> 443
    "http" -> 80
    else -> -1
}

private fun JSONArray?.toStringSet(): Set<String> = buildSet {
    val source = this@toStringSet ?: return@buildSet
    for (index in 0 until source.length()) {
        source.optString(index).trim().lowercase().takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun desktopContractCacheDirectory(name: String): File {
    require(name.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "The contract cache name is invalid." }
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
    val cacheRoot = xdgCache?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/contracts/$name")
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

internal const val DESKTOP_PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS = 10L
internal const val DESKTOP_PROJECT_CONTENT_READ_TIMEOUT_SECONDS = 30L
internal const val DESKTOP_PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS = 30L
internal const val DESKTOP_PROJECT_CONTENT_CALL_TIMEOUT_SECONDS = 10L * 60L

internal fun buildDesktopProjectContentHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(DESKTOP_PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DESKTOP_PROJECT_CONTENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DESKTOP_PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(DESKTOP_PROJECT_CONTENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

internal fun publishDesktopProjectContentCache(temporary: File, destination: File) {
    require(temporary.isFile)
    destination.parentFile?.mkdirs()
    try {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

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

internal fun writePrivatePendingMutationFile(
    directory: File,
    target: File,
    bytes: ByteArray,
) {
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
            Files.move(
                temporary,
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary,
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        setPrivatePendingMutationFilePermissions(target)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal suspend fun executeDesktopDynamicApiGet(
    accountId: String,
    requestIdentity: String,
    cachePolicy: NextcloudApiCachePolicy,
    coalescer: DynamicApiRequestCoalescer<NextcloudApiResponse>,
    loadCached: () -> NextcloudApiResponse?,
    invalidateCached: () -> Unit,
    executeNetwork: suspend () -> NextcloudApiResponse,
    commit: (NextcloudApiResponse) -> Unit,
): NextcloudApiResponse {
    when (cachePolicy) {
        NextcloudApiCachePolicy.PreferCache -> loadCached()?.let { return it }
        NextcloudApiCachePolicy.RefreshNetwork ->
            coalescer.invalidateRequest(accountId, requestIdentity) {}
        NextcloudApiCachePolicy.ForceNetwork ->
            coalescer.invalidateRequest(accountId, requestIdentity, invalidateCached)
    }
    return coalescer.execute(
        accountId = accountId,
        requestIdentity = requestIdentity,
        load = {
            if (cachePolicy != NextcloudApiCachePolicy.PreferCache) {
                executeNetwork()
            } else {
                loadCached() ?: executeNetwork()
            }
        },
        commit = commit,
    )
}

internal fun combinedAutomaticCacheExcess(
    maximumBytes: Long,
    completeFileBytes: Long,
    rangeBytes: Long,
    windowsCachedBytes: Long,
    windowsPinnedBytes: Long,
): Long {
    require(maximumBytes > 0L)
    require(listOf(completeFileBytes, rangeBytes, windowsCachedBytes, windowsPinnedBytes).all { it >= 0L })
    require(windowsPinnedBytes <= windowsCachedBytes)
    val total = listOf(
        completeFileBytes,
        rangeBytes,
        windowsCachedBytes - windowsPinnedBytes,
    ).fold(0L) { accumulated, bytes ->
        if (bytes > Long.MAX_VALUE - accumulated) Long.MAX_VALUE else accumulated + bytes
    }
    return (total - maximumBytes).coerceAtLeast(0L)
}

internal class DesktopSessionPublicationGuard {
    private val monitor = Any()

    fun <Result> serialize(action: () -> Result): Result = synchronized(monitor, action)
}

internal fun closeVirtualFileProviderForReplacement(
    provider: AutoCloseable?,
    detach: () -> Unit,
): Throwable? = runCatching { provider?.close() }
    .onSuccess { detach() }
    .exceptionOrNull()

class DesktopNextcloudServices(
    private val onThemePreferenceChanged: (ThemePreference) -> Unit = {},
    private val onKeepRunningInBackgroundChanged: (Boolean) -> Unit = {},
    private val onDesktopUpdateInstallerOpened: (String) -> Unit = {},
    supportDiagnosticsRoot: File? = null,
    providedSupportDiagnostics: AsyncJvmSupportDiagnostics? = null,
    mutationRecoveryRoot: File = defaultDesktopDurableMutationRecoveryRoot(),
    supportIntakeRoot: File? = null,
) : NextcloudPlatformServices, AutoCloseable {
    private val preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative")
    private val ownsTemporarySupportDiagnosticsRoot = providedSupportDiagnostics == null && supportDiagnosticsRoot == null
    private val resolvedSupportDiagnosticsRoot = supportDiagnosticsRoot ?: if (providedSupportDiagnostics == null) {
        Files.createTempDirectory("nextcloud-native-test-diagnostics").toFile()
    } else {
        null
    }
    private val supportDiagnostics = providedSupportDiagnostics ?: createDesktopSupportDiagnostics(
        requireNotNull(resolvedSupportDiagnosticsRoot),
    )
    private val supportBundleExporter = DesktopSupportBundleExporter(supportDiagnostics)
    private val durableMutationRecovery = DesktopDurableMutationRecoveryStore(mutationRecoveryRoot)
    private val ownsTemporarySupportIntakeRoot = supportIntakeRoot == null && resolvedSupportDiagnosticsRoot == null
    private val resolvedSupportIntakeRoot = supportIntakeRoot
        ?: resolvedSupportDiagnosticsRoot?.resolve("support-submissions")
        ?: Files.createTempDirectory("nextcloud-native-test-support-intake").toFile()
    private val secretStore = defaultDesktopSecretStore()
    private val sessionPublicationGuard = DesktopSessionPublicationGuard()
    private val appUpdater = DesktopAppUpdater(
        preferences = preferences.node("app-updates-v1"),
        onInstallerConfirmationOpened = { target -> onDesktopUpdateInstallerOpened(target.platform) },
    )
    private val httpClient = OkHttpClient.Builder().trackJvmNetworkFailures().build()
    private val supportIntake = JvmSupportIntake(
        diagnostics = supportDiagnostics,
        temporaryRoot = resolvedSupportIntakeRoot,
        environment = desktopSupportDiagnosticsEnvironment(),
        client = httpClient.newBuilder().retryOnConnectionFailure(false).build(),
    )
    private val loginPollHttpClient = httpClient.newBuilder().retryOnConnectionFailure(false).build()
    private val loginPollFallbackTokens = ConcurrentHashMap.newKeySet<String>()
    private val loginPollPendingTokens = ConcurrentHashMap.newKeySet<String>()
    private val fileMutationHttpExecutor = DesktopHttpMutationExecutor(httpClient)
    private val noRedirectHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val noRedirectFileMutationHttpExecutor = DesktopHttpMutationExecutor(noRedirectHttpClient)
    private val projectContentHttpClient = buildDesktopProjectContentHttpClient()
    private val contractAcquirer = SignedAppStoreContractAcquirer(
        catalogCache = FileAppStoreCatalogCache(desktopContractCacheDirectory("catalogs")),
        verifiedContractCache = FileVerifiedContractCache(desktopContractCacheDirectory("verified")),
    )
    private val dynamicDiscoveryCacheDirectory = desktopContractCacheDirectory("discoveries-v1")
    private val pendingDynamicMutationDirectory = desktopPendingDynamicMutationDirectory()
    private val fileReadCache = defaultDesktopFileReadCache()
    private val virtualRangeCaches = mutableMapOf<String, DesktopVirtualRangeCache>()
    private val virtualFolderHydrationJobs = mutableMapOf<String, Job>()
    private val virtualFolderHydrationMutex = Mutex()
    private val virtualFolderRetentionMutex = Mutex()
    private val virtualFolderMutationLock = Any()
    private val virtualFolderMutationGenerationsByJob = mutableMapOf<String, Long>()
    private val virtualFolderCompletedGenerations = mutableMapOf<String, Long>()
    private val virtualFolderRetryAtEpochMillis = mutableMapOf<String, Long>()
    private val activeFileRangeSessions = mutableSetOf<NextcloudFileRangeSession>()
    private val fileRangeSessionLock = Any()
    @Volatile
    private var sessionClearing = false
    private val virtualFileProviderLock = Any()
    private val virtualFileCacheTierMutations = mutableSetOf<String>()
    private var linuxVirtualFileSystem: LinuxNextcloudVirtualFileSystem? = null
    private var linuxVirtualMetadataBackend: CachingLinuxVirtualFileBackend? = null
    private var linuxVirtualFileMountIdentity: String? = null
    private var linuxVirtualFileFailure: String? = null
    @Volatile
    private var windowsCloudFilesProvider: WindowsCloudFilesProvider? = null
    @Volatile
    private var windowsCloudFilesIdentity: String? = null
    @Volatile
    private var windowsCloudFilesFailure: String? = null
    private val dynamicApiReadCache = DynamicApiResponseCache(
        desktopContractCacheDirectory("responses"),
    )
    private val dynamicApiRequestCoalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
    private val mediaTimelineCarryoverStore = MediaTimelineDavCarryoverStore()
    private val memoriesTimeline = MemoriesPreferredTimelineReadService { session, request ->
        executeNextcloudApi(session, request)
    }
    private val externalFileHandoff = DesktopExternalFileHandoff()
    private val externalUrlLauncher = DesktopExternalUrlLauncher()

    init {
        require(providedSupportDiagnostics == null || supportDiagnosticsRoot == null)
        supportDiagnostics.registerPrivateValue(System.getProperty("user.home"))
    }

    private fun virtualRangeCache(accountId: String): DesktopVirtualRangeCache {
        val cache = synchronized(virtualRangeCaches) {
            virtualRangeCaches.getOrPut(accountId) {
                if (isLinuxDesktop()) {
                    val tiers = desktopVirtualFileCacheTiers(preferences, accountId)
                    DesktopVirtualRangeCache(
                        root = File(tiers.configuration.primaryPath),
                        overflowRoot = tiers.configuration.overflowPath?.let(::File),
                        expectedPrimaryIdentity = tiers.primaryIdentity,
                        requirePrimaryIdentity = tiers.primaryIdentityRequired,
                        expectedOverflowIdentity = tiers.overflowIdentity,
                        policy = fileReadCache::loadPolicy,
                        createParentDirectories = false,
                    )
                } else {
                    defaultDesktopVirtualRangeCache(fileReadCache::loadPolicy)
                }
            }
        }
        if (isLinuxDesktop()) cache.requireAvailable()
        return cache
    }

    private fun scheduleVirtualFolderHydration(
        session: NextcloudSession,
        userId: String,
        relativePath: String,
        accountId: String,
        cache: DesktopVirtualRangeCache,
    ) {
        if (sessionClearing) return
        if (synchronized(virtualFileProviderLock) { accountId in virtualFileCacheTierMutations }) return
        if (cache.hasUnavailableRetainedOverflowRecords(accountId, relativePath)) return
        val jobKey = "$accountId\u0000$relativePath"
        synchronized(virtualFolderHydrationJobs) {
            if (sessionClearing) return
            if (virtualFolderHydrationJobs[jobKey].occupiesVirtualFolderHydrationSlot()) return
        }
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val generationState = synchronized(virtualFolderMutationLock) {
            val retryAt = virtualFolderRetryAtEpochMillis[jobKey]
            if (retryAt != null && now < retryAt) return
            val generation = virtualFolderMutationGenerationsByJob.getOrDefault(jobKey, 0L)
            generation to (generation > virtualFolderCompletedGenerations.getOrDefault(jobKey, 0L))
        }
        val (generation, mutationRefreshPending) = generationState
        val persistedStatus = cache.loadFolderHydrationStatus(accountId, relativePath)
        if (!mutationRefreshPending && !shouldScheduleVirtualFolderHydration(persistedStatus, now)) return
        val currentStatus = cache.loadValidatedFolderHydrationStatus(accountId, relativePath)
        if (!mutationRefreshPending && !shouldScheduleVirtualFolderHydration(currentStatus, now)) return
        if (currentStatus?.phase == VirtualFolderHydrationPhase.AvailableOffline) {
            cache.setFolderHydrationStatus(
                accountId,
                currentStatus.copy(
                    refreshFailure = null,
                    refreshing = true,
                    refreshRetryAtEpochMillis = null,
                ),
            )
        } else {
            cache.setFolderHydrationStatus(
                accountId,
                VirtualFolderHydrationStatus(relativePath, VirtualFolderHydrationPhase.Queued),
            )
        }
        val wasAvailableOffline = currentStatus?.phase == VirtualFolderHydrationPhase.AvailableOffline
        lateinit var job: Job
        job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    virtualFolderHydrationMutex.withLock {
                        check(!cache.hasUnavailableRetainedOverflowRecords(accountId, relativePath)) {
                            "Reconnect the overflow cache drive before refreshing kept folders."
                        }
                        if (!wasAvailableOffline) {
                            cache.setFolderHydrationStatus(
                                accountId,
                                VirtualFolderHydrationStatus(relativePath, VirtualFolderHydrationPhase.Downloading),
                            )
                        }
                        val tree = DesktopFileSyncRemoteTree(session, userId, "")
                        val writebacks = defaultDesktopLinuxWritebackStore(session)
                        val metadataStore = DesktopLinuxVirtualMetadataStore(fileReadCache, accountId)
                        var stableListings: LinkedHashMap<String, List<DesktopRemoteSyncDocument>>? = null
                        var stableRetention: VirtualFolderRetentionState? = null
                        var stableMissingRetainedRoots: Set<String>? = null
                        var attempt = 0
                        while (stableListings == null && attempt < MAX_VIRTUAL_FOLDER_STABILITY_ATTEMPTS) {
                            attempt += 1
                            val retentionSnapshot = cache.loadFolderRetention(accountId)
                            if (
                                retentionSnapshot.rules.none { rule ->
                                    rule.relativePath == relativePath &&
                                        rule.retention == VirtualFolderRetention.KeepOnDevice
                                }
                            ) return@withLock
                            val backend = DesktopNextcloudVirtualFileBackend(
                                session = session,
                                userId = userId,
                                services = this@DesktopNextcloudServices,
                                rangeCache = cache,
                                writebacks = writebacks,
                                tree = tree,
                                requireDurableCacheWrites = true,
                                retentionSnapshot = retentionSnapshot,
                            )
                            val listings = linkedMapOf<String, List<DesktopRemoteSyncDocument>>()
                            val ancestorTargets = linkedMapOf<String, Set<String>>()
                            val missingRetainedRoots = linkedSetOf<String>()
                            val survivingRetainedListings = cache.retainedListingCountSurvivingPublication(
                                accountId,
                                relativePath,
                                (retainedFolderAncestorListings(relativePath) + relativePath).toSet(),
                            )
                            var retainedMetadataEntries = 0
                            fun loadListing(parent: String): List<DesktopRemoteSyncDocument> {
                                check(parent !in listings) {
                                    "The selected virtual folder contains a repeated directory path."
                                }
                                requireVirtualFolderListingCapacity(survivingRetainedListings + listings.size)
                                val documents = tree.list(parent)
                                retainedMetadataEntries = nextVirtualFolderRetainedMetadataCount(
                                    retainedMetadataEntries,
                                    documents.size,
                                )
                                listings[parent] = documents
                                return documents
                            }

                            val ancestors = retainedFolderAncestorListings(relativePath)
                            val retainedRoots = retentionSnapshot.rules.asSequence()
                                .filter { rule -> rule.retention == VirtualFolderRetention.KeepOnDevice }
                                .map(VirtualFolderRetentionRule::relativePath)
                                .toList()
                            ancestors.forEachIndexed { index, parent ->
                                val currentTarget = ancestors.getOrNull(index + 1) ?: relativePath
                                val targets = retainedRoots.mapNotNullTo(linkedSetOf()) { retainedRoot ->
                                    retainedFolderNavigationChild(parent, retainedRoot)
                                }
                                check(currentTarget in targets)
                                requireVirtualFolderListingCapacity(survivingRetainedListings + listings.size)
                                val targetDocuments = tree.list(parent).filter { document ->
                                    document.entry.relativePath in targets && document.isDirectory
                                }
                                val availableTargets = retainedFolderAvailableNavigationTargets(
                                    currentTarget,
                                    targetDocuments,
                                )
                                missingRetainedRoots += retainedRootsMissingNavigationTarget(
                                    parent,
                                    retainedRoots,
                                    availableTargets,
                                )
                                retainedMetadataEntries = nextVirtualFolderRetainedMetadataCount(
                                    retainedMetadataEntries,
                                    targetDocuments.size,
                                )
                                ancestorTargets[parent] = targets
                                listings[parent] = targetDocuments
                            }
                            val pending = ArrayDeque<String>().apply { add(relativePath) }
                            while (pending.isNotEmpty()) {
                                currentCoroutineContext().ensureActive()
                                val parent = pending.removeFirst()
                                if (
                                    retentionSnapshot.retentionFor(parent) !=
                                    VirtualFolderRetention.KeepOnDevice
                                ) continue
                                loadListing(parent).forEach { document ->
                                    val fullPath = document.entry.relativePath
                                    if (
                                        retentionSnapshot.retentionFor(fullPath) !=
                                        VirtualFolderRetention.KeepOnDevice
                                    ) return@forEach
                                    if (document.isDirectory) {
                                        pending.add(fullPath)
                                        return@forEach
                                    }
                                    requireNotNull(document.entry.size) {
                                        "The server did not provide a size for $fullPath."
                                    }
                                }
                            }
                            val retainedFiles = listings.values.asSequence().flatten()
                                .filterNot(DesktopRemoteSyncDocument::isDirectory)
                                .filter { document ->
                                    retentionSnapshot.retentionFor(document.entry.relativePath) ==
                                        VirtualFolderRetention.KeepOnDevice
                                }
                                .toList()
                            val expectedRevisions = retainedFiles.mapNotNull { document ->
                                val size = requireNotNull(document.entry.size)
                                if (size == 0L) null else VirtualRangeRevision(
                                    document.entry.relativePath,
                                    document.entry.etag,
                                    size,
                                )
                            }
                            val completeRevisions = cache.completeRevisions(accountId, expectedRevisions)
                            cache.requireRevisionsCapacity(
                                accountId = accountId,
                                revisions = expectedRevisions,
                                blockBytes = VIRTUAL_FOLDER_HYDRATION_CHUNK_BYTES,
                                retention = retentionSnapshot,
                                pendingRevisions = expectedRevisions.filterNot(completeRevisions::contains),
                            )
                            retainedFiles.forEach { document ->
                                val fullPath = document.entry.relativePath
                                val size = requireNotNull(document.entry.size)
                                val revision = if (size == 0L) null else VirtualRangeRevision(
                                    fullPath,
                                    document.entry.etag,
                                    size,
                                )
                                if (revision == null || revision in completeRevisions) return@forEach
                                check(!cache.hasUnavailableRetainedOverflowRecords(accountId, relativePath)) {
                                    "Reconnect the overflow cache drive before refreshing kept folders."
                                }
                                cache.requireRevisionCapacity(
                                    accountId,
                                    fullPath,
                                    size,
                                    VIRTUAL_FOLDER_HYDRATION_CHUNK_BYTES,
                                    retentionSnapshot,
                                )
                                cache.freeUp(accountId, requestedBytes = 0L)
                                check(cache.hasRetainedRevisionStorageCapacity(accountId, fullPath, size)) {
                                    "There is not enough free space to finish keeping $relativePath offline."
                                }
                                val node = document.toLinuxVirtualFileNode()
                                backend.open(node).use { handle ->
                                    var offset = 0L
                                    while (offset < size) {
                                        currentCoroutineContext().ensureActive()
                                        val length = minOf(
                                            VIRTUAL_FOLDER_HYDRATION_CHUNK_BYTES.toLong(),
                                            size - offset,
                                        ).toInt()
                                        handle.read(offset, length)
                                        offset += length
                                    }
                                }
                                cache.freeUp(accountId, requestedBytes = 0L)
                            }
                            val stable = listings.all { (parent, documents) ->
                                currentCoroutineContext().ensureActive()
                                val refreshed = tree.list(parent)
                                val targets = ancestorTargets[parent]
                                val comparable = if (targets == null) {
                                    refreshed
                                } else {
                                    refreshed.filter { document -> document.entry.relativePath in targets }
                                }
                                comparable.hydrationGeneration() == documents.hydrationGeneration()
                            }
                            if (stable) {
                                stableListings = listings
                                stableRetention = retentionSnapshot
                                stableMissingRetainedRoots = missingRetainedRoots
                            }
                        }
                        val verifiedListings = checkNotNull(stableListings) {
                            "The selected folder kept changing while it was prepared for offline use. Try again shortly."
                        }
                        val verifiedRetention = checkNotNull(stableRetention)
                        val missingRetainedRoots = checkNotNull(stableMissingRetainedRoots)
                        val expectedPublishedRevisions = verifiedListings.values.asSequence().flatten()
                            .filterNot(DesktopRemoteSyncDocument::isDirectory)
                            .filter { document ->
                                verifiedRetention.retentionFor(document.entry.relativePath) ==
                                    VirtualFolderRetention.KeepOnDevice
                            }
                            .mapNotNull { document ->
                                val size = requireNotNull(document.entry.size)
                                if (size == 0L) null else VirtualRangeRevision(
                                    document.entry.relativePath,
                                    document.entry.etag,
                                    size,
                                )
                            }.toList()
                        check(
                            cache.completeRevisions(accountId, expectedPublishedRevisions).size ==
                                expectedPublishedRevisions.distinct().size
                        ) { "Offline file validation failed before the folder could be published." }
                        val publishedAt = System.currentTimeMillis()
                        val snapshots = verifiedListings.mapValues { (parent, documents) ->
                            LinuxVirtualDirectorySnapshot(
                                nodes = documents.map(DesktopRemoteSyncDocument::toLinuxVirtualFileNode),
                                fetchedAtEpochMillis = publishedAt,
                                complete = isCompleteRetainedTreeListing(parent, relativePath),
                            )
                        }
                        synchronized(virtualFolderMutationLock) {
                            if (
                                virtualFolderMutationGenerationsByJob.getOrDefault(jobKey, 0L) != generation ||
                                cache.loadFolderRetention(accountId) != verifiedRetention
                            ) {
                                throw VirtualFolderRefreshSupersededException()
                            }
                            cache.publishRetainedListings(accountId, relativePath, snapshots)
                            cache.publishRetainedRevisions(
                                accountId,
                                relativePath,
                                expectedPublishedRevisions,
                                verifiedRetention,
                            )
                            publishDesktopLinuxFallbackMetadataBestEffort(metadataStore, snapshots)
                            val protectedPaths = writebacks.pendingWritebacks()
                                .mapTo(hashSetOf(), DesktopLinuxPendingWriteback::path)
                            verifiedListings.forEach { (parent, documents) ->
                                if (isCompleteRetainedTreeListing(parent, relativePath)) {
                                    reconcileVirtualRangeChildren(cache, accountId, parent, documents, protectedPaths)
                                }
                            }
                            cache.setFolderHydrationStatus(
                                accountId,
                                VirtualFolderHydrationStatus(
                                    relativePath,
                                    VirtualFolderHydrationPhase.AvailableOffline,
                                    verifiedAtEpochMillis = publishedAt,
                                ),
                            )
                            missingRetainedRoots.forEach { missingRoot ->
                                val previous = cache.loadFolderHydrationStatus(accountId, missingRoot)
                                val stillAvailable = runCatching {
                                    cache.hasCompleteRetainedFolder(accountId, missingRoot)
                                }.getOrDefault(false)
                                cache.setFolderHydrationStatus(
                                    accountId,
                                    if (stillAvailable) {
                                        VirtualFolderHydrationStatus(
                                            missingRoot,
                                            VirtualFolderHydrationPhase.AvailableOffline,
                                            refreshFailure =
                                                "The retained folder is no longer available at its saved path.",
                                            verifiedAtEpochMillis = previous?.verifiedAtEpochMillis,
                                        )
                                    } else {
                                        VirtualFolderHydrationStatus(
                                            missingRoot,
                                            VirtualFolderHydrationPhase.Failed,
                                            "The retained folder is no longer available at its saved path.",
                                        )
                                    },
                                )
                            }
                            virtualFolderCompletedGenerations[jobKey] = generation
                            virtualFolderRetryAtEpochMillis.remove(jobKey)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    restoreVirtualFolderStatusAfterCancellation(
                        cache,
                        accountId,
                        relativePath,
                        wasAvailableOffline,
                        currentStatus,
                    )
                    throw cancellation
                } catch (failure: Throwable) {
                    if (!currentCoroutineContext().isActive) {
                        restoreVirtualFolderStatusAfterCancellation(
                            cache,
                            accountId,
                            relativePath,
                            wasAvailableOffline,
                            currentStatus,
                        )
                        throw CancellationException("Virtual folder hydration was canceled.").also { cancellation ->
                            cancellation.initCause(failure)
                        }
                    }
                    if (failure is VirtualFolderRefreshSupersededException) {
                        runCatching {
                            cache.setFolderHydrationStatus(
                                accountId,
                                VirtualFolderHydrationStatus(relativePath, VirtualFolderHydrationPhase.Queued),
                            )
                        }
                        return@launch
                    }
                    val retryAt = virtualFolderRefreshRetryAt(System.currentTimeMillis().coerceAtLeast(0L))
                    synchronized(virtualFolderMutationLock) {
                        virtualFolderRetryAtEpochMillis[jobKey] = retryAt
                    }
                    val safeFailure = failure.message?.filterNot(Char::isISOControl)?.take(256)
                        ?.takeIf(String::isNotBlank) ?: "Offline download failed and can be retried."
                    val stillAvailableOffline = wasAvailableOffline && runCatching {
                        cache.hasCompleteRetainedFolder(accountId, relativePath)
                    }.getOrDefault(false)
                    if (stillAvailableOffline) runCatching {
                        cache.setFolderHydrationStatus(
                            accountId,
                            VirtualFolderHydrationStatus(
                                relativePath,
                                VirtualFolderHydrationPhase.AvailableOffline,
                                refreshFailure = safeFailure,
                                verifiedAtEpochMillis = currentStatus.verifiedAtEpochMillis,
                                refreshRetryAtEpochMillis = retryAt,
                            ),
                        )
                    } else runCatching {
                        cache.setFolderHydrationStatus(
                            accountId,
                            VirtualFolderHydrationStatus(
                                relativePath,
                                VirtualFolderHydrationPhase.Failed,
                                safeFailure,
                            ),
                        )
                    }
                } finally {
                    val removedOwnedJob = synchronized(virtualFolderHydrationJobs) {
                        removeVirtualFolderHydrationJobIfOwned(virtualFolderHydrationJobs, jobKey, job)
                    }
                    val rerun = synchronized(virtualFolderMutationLock) {
                        virtualFolderMutationGenerationsByJob.getOrDefault(jobKey, 0L) >
                            virtualFolderCompletedGenerations.getOrDefault(jobKey, 0L) &&
                            System.currentTimeMillis().coerceAtLeast(0L) >=
                            virtualFolderRetryAtEpochMillis.getOrDefault(jobKey, 0L)
                    }
                    if (removedOwnedJob && rerun && !sessionClearing) {
                        scheduleVirtualFolderHydration(session, userId, relativePath, accountId, cache)
                    }
                }
        }
        val accepted = synchronized(virtualFileProviderLock) {
            synchronized(virtualFolderHydrationJobs) {
                if (
                    sessionClearing ||
                    accountId in virtualFileCacheTierMutations ||
                    virtualFolderHydrationJobs[jobKey].occupiesVirtualFolderHydrationSlot()
                ) false
                else true.also { virtualFolderHydrationJobs[jobKey] = job }
            }
        }
        if (accepted) job.start() else job.cancel()
    }

    private fun restoreVirtualFolderStatusAfterCancellation(
        cache: DesktopVirtualRangeCache,
        accountId: String,
        relativePath: String,
        wasAvailableOffline: Boolean,
        previousStatus: VirtualFolderHydrationStatus?,
    ) {
        runCatching {
            if (
                cache.loadFolderRetention(accountId).rules.none { rule ->
                    rule.relativePath == relativePath && rule.retention == VirtualFolderRetention.KeepOnDevice
                }
            ) return@runCatching
            cache.setFolderHydrationStatus(
                accountId,
                VirtualFolderHydrationStatus(
                    relativePath,
                    if (wasAvailableOffline) {
                        VirtualFolderHydrationPhase.AvailableOffline
                    } else {
                        VirtualFolderHydrationPhase.Queued
                    },
                    verifiedAtEpochMillis = previousStatus?.verifiedAtEpochMillis,
                ),
            )
        }
    }

    private fun refreshRetainedFoldersAfterMutation(
        session: NextcloudSession,
        userId: String,
        accountId: String,
        path: String,
    ) {
        synchronized(virtualFileProviderLock) {
            runCatching { invalidateDesktopFileMetadata(accountId, path) }
            val cache = runCatching { virtualRangeCache(accountId) }.getOrNull() ?: return
            val roots = runCatching {
                cache.retainedFoldersAffectedByListingChanges(accountId, listOf(path))
            }
                .getOrDefault(emptyList())
            synchronized(virtualFolderMutationLock) {
                advanceAffectedVirtualFolderGenerations(
                    virtualFolderMutationGenerationsByJob,
                    virtualFolderCompletedGenerations,
                    accountId,
                    roots,
                )
            }
            runCatching { cache.invalidate(accountId, path) }
            runCatching { cache.queueRetainedFoldersForRefresh(accountId, path) }
            roots.forEach { root ->
                runCatching { scheduleVirtualFolderHydration(session, userId, root, accountId, cache) }
            }
        }
    }

    private fun refreshRetainedFoldersAfterRemoteListing(
        session: NextcloudSession,
        userId: String,
        accountId: String,
        changedPaths: Set<String>,
    ) {
        synchronized(virtualFileProviderLock) {
            val cache = runCatching { virtualRangeCache(accountId) }.getOrNull() ?: return
            val roots = runCatching { cache.queueRetainedFoldersForListingRefresh(accountId, changedPaths) }
                .getOrDefault(emptyList())
            synchronized(virtualFolderMutationLock) {
                advanceAffectedVirtualFolderGenerations(
                    virtualFolderMutationGenerationsByJob,
                    virtualFolderCompletedGenerations,
                    accountId,
                    roots,
                )
            }
            roots.forEach { root ->
                runCatching { scheduleVirtualFolderHydration(session, userId, root, accountId, cache) }
            }
        }
    }

    private suspend fun cancelVirtualFolderHydration(accountId: String, relativePath: String) {
        val exact = "$accountId\u0000$relativePath"
        val descendants = "$exact/"
        val jobs = synchronized(virtualFolderHydrationJobs) {
            virtualFolderHydrationJobs.filterKeys { key -> key == exact || key.startsWith(descendants) }.values.toList()
        }
        jobs.forEach { job -> job.cancelAndJoin() }
    }

    private fun cancelAllVirtualFolderHydration(accountId: String): List<Job> {
        val prefix = "$accountId\u0000"
        val jobs = synchronized(virtualFolderHydrationJobs) {
            virtualFolderHydrationJobs.filterKeys { key -> key.startsWith(prefix) }.values.toList()
        }
        jobs.forEach(Job::cancel)
        return jobs
    }

    private suspend fun reconcileConfiguredVirtualFolders(session: NextcloudSession?) {
        if (!isLinuxDesktop()) return
        session ?: return
        val accountId = desktopFileCacheAccountId(session)
        val cache = virtualRangeCache(accountId)
        val kept = cache.loadFolderRetention(accountId).rules.filter { rule ->
            rule.retention == VirtualFolderRetention.KeepOnDevice
        }
        if (kept.isEmpty()) return
        val userId = loadServerInfo(session).userId
        kept.forEach { rule -> scheduleVirtualFolderHydration(session, userId, rule.relativePath, accountId, cache) }
    }
    private val localUploadPicker = DesktopLocalUploadPicker()
    private val deckCardDrafts = DesktopDeckCardDraftStore()
    private val fileSyncEngine = DesktopFileSyncEngine(
        minimumFreeSpaceBytes = { fileReadCache.loadPolicy().minimumFreeSpaceBytes },
        onRemoteMutationCommitted = { session, userId, path ->
            refreshRetainedFoldersAfterMutation(
                session,
                userId,
                desktopFileCacheAccountId(session),
                path,
            )
        },
    )
    private val startOnLoginController = DesktopStartOnLoginController()
    private val fileSyncRunLock = Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backgroundFileSyncJob: Job? = null
    private val mutableFileSyncTraySnapshot = MutableStateFlow(
        DesktopFileSyncTraySnapshot(
            phase = if (preferences.getBoolean(KEY_FILE_SYNC_PAUSED, false)) {
                DesktopFileSyncTrayPhase.Paused
            } else {
                DesktopFileSyncTrayPhase.Idle
            },
        ),
    )
    val fileSyncTraySnapshot: StateFlow<DesktopFileSyncTraySnapshot> =
        mutableFileSyncTraySnapshot.asStateFlow()
    private val projectNewsCache = File(
        desktopContractCacheDirectory("responses").parentFile,
        "project-content/news-feed-v1.json",
    )
    private val projectNewsImageDirectory = File(projectNewsCache.parentFile, "news-images")

    suspend fun restoreVirtualFileProviderIfEnabled() {
        val session = loadSession() ?: return
        val accountId = desktopFileCacheAccountId(session)
        if (!preferences.getBoolean(virtualFileProviderPreferenceKey(accountId), false)) return
        val userId = loadServerInfo(session).userId
        loadVirtualFileStorage(session, userId)
    }

    fun startDesktopSyncLifecycle() {
        synchronized(this) {
            if (backgroundFileSyncJob?.isActive == true) return
            backgroundFileSyncJob = serviceScope.launch {
                restoreConfirmedStartOnLoginRegistration()
                while (isActive) {
                    if (!isFileSyncPaused()) {
                        runCatching { syncAllFileSyncPairs(DesktopFileSyncRunSource.Background) }
                    }
                    val virtualFolderSession = loadSession()
                    runCatching { reconcileConfiguredVirtualFolders(virtualFolderSession) }
                        .onFailure { failure ->
                            publishFileSyncRunFailure(
                                virtualFolderSession?.let(::desktopFileCacheAccountId),
                                DesktopFileSyncRunSource.Background,
                                failure,
                            )
                        }
                    delay(DESKTOP_FILE_SYNC_INTERVAL_MILLIS)
                }
            }
        }
    }

    override val externalFileHandoffSupport: ExternalFileHandoffSupport = ExternalFileHandoffSupport.Available(
        ExternalFileHandoffCapability(
            supportedActions = setOf(ExternalFileHandoffAction.OpenWith),
            maximumFileBytes = MAX_EXTERNAL_FILE_HANDOFF_BYTES,
        ),
    )

    override val supportsBidirectionalFileSync: Boolean = true
    override val supportsVirtualFileStorage: Boolean = true
    override val supportsRecursiveFileOfflineStorage: Boolean get() = isLinuxDesktop()

    override suspend fun loadVirtualFileStorage(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageSnapshot = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        val providerPreferenceKey = virtualFileProviderPreferenceKey(accountId)
        if (
            (isLinuxDesktop() || isWindowsDesktop()) &&
            preferences.getBoolean(providerPreferenceKey, false) &&
            synchronized(virtualFileProviderLock) {
                linuxVirtualFileMountIdentity != accountId && windowsCloudFilesIdentity != accountId && windowsCloudFilesAutomaticActivationAllowed(isWindowsDesktop(), windowsCloudFilesFailure)
            }
        ) {
            runCatching { activateVirtualFileProvider(session, userId) }
        }
        val windowsCloudFilesRecoveryNotice = if (isWindowsDesktop()) {
            persistedWindowsCloudFilesRecoveryNotice(preferences, accountId)
        } else {
            null
        }
        runCatching { enforceCombinedVirtualFileCachePolicy(accountId, fileReadCache.loadPolicy()) }
        val cache = fileReadCache.virtualFileSummary(accountId)
        val rangeCacheResult = runCatching {
            val current = virtualRangeCache(accountId)
            current.requireAvailable()
            Triple(current, current.loadFolderRetention(accountId), current.summary(accountId))
        }
        val rangeCache = rangeCacheResult.getOrNull()?.first
        val folderRetention = rangeCacheResult.getOrNull()?.second ?: VirtualFolderRetentionState()
        val unavailableRetainedRoots = if (rangeCache == null) {
            emptySet()
        } else {
            folderRetention.rules.asSequence()
                .filter { rule -> rule.retention == VirtualFolderRetention.KeepOnDevice }
                .filter { rule -> rangeCache.hasUnavailableRetainedOverflowRecords(accountId, rule.relativePath) }
                .mapTo(linkedSetOf(), VirtualFolderRetentionRule::relativePath)
        }
        unavailableRetainedRoots.forEach { retainedRoot ->
            cancelVirtualFolderHydration(accountId, retainedRoot)
        }
        if (rangeCache != null) {
            folderRetention.rules.filter { rule ->
                rule.retention == VirtualFolderRetention.KeepOnDevice
            }.forEach { rule ->
                runCatching {
                    scheduleVirtualFolderHydration(session, userId, rule.relativePath, accountId, rangeCache)
                }
            }
        }
        val ranges = rangeCacheResult.getOrNull()?.third
        val linux = isLinuxDesktop()
        val windows = isWindowsDesktop()
        val cacheTiers = if (linux) desktopVirtualFileCacheTiers(preferences, accountId).configuration else null
        val overflowUnavailable = ranges != null && ranges.overflowCachedBytes > 0L && !ranges.overflowAvailable
        val windowsFailure = windowsCloudFilesFailure
        val active = synchronized(virtualFileProviderLock) {
            (linux && linuxVirtualFileSystem != null && linuxVirtualFileMountIdentity == accountId) ||
                (windows && windowsFailure == null && windowsCloudFilesProvider != null &&
                    windowsCloudFilesIdentity == accountId)
        }
        val windowsSummary = if (windowsFailure == null) windowsVirtualFileSummary(accountId) else null
        val writebacks = defaultDesktopLinuxWritebackStore(session).pendingWritebacks()
        VirtualFileStorageSnapshot(
            support = if (linux || windows) VirtualFileStorageSupport.Available else VirtualFileStorageSupport.CacheOnly,
            integration = when {
                linux -> VirtualFilePlatformIntegration.LinuxFilesystemMount
                windows -> VirtualFilePlatformIntegration.WindowsCloudFiles
                else -> VirtualFilePlatformIntegration.InAppOnDemandCache
            },
            policy = cache.policy,
            cachedBytes = cache.cachedBytes + (ranges?.cachedBytes ?: 0L) + (windowsSummary?.cachedBytes ?: 0L),
            reclaimableBytes = cache.reclaimableBytes + (ranges?.reclaimableBytes ?: 0L) +
                (windowsSummary?.reclaimableBytes ?: 0L),
            pinnedBytes = (ranges?.pinnedBytes ?: 0L) + (windowsSummary?.pinnedBytes ?: 0L),
            hydratedFileCount = cache.entryCount + (ranges?.fileCount ?: 0) +
                (windowsSummary?.hydratedFileCount ?: 0),
            pinnedFileCount = (ranges?.pinnedFileCount ?: 0) + (windowsSummary?.pinnedFileCount ?: 0),
            availableFreeBytes = listOfNotNull(
                cache.availableFreeBytes,
                ranges?.availableFreeBytes,
                windowsSummary?.availableFreeBytes,
            ).minOrNull(),
            storageCapacityBytes = null,
            limitations = buildList {
                add("Range blocks and complete files share the managed automatic-cleanup policy.")
                rangeCacheResult.exceptionOrNull()?.let { failure ->
                    add("The selected virtual-file storage drive is unavailable: ${failure.message ?: "unknown error"}")
                }
                if (overflowUnavailable) {
                    add("Reconnect the overflow cache drive to open files stored there.")
                }
                ranges?.tierAttention?.let { failure ->
                    add("Cache tier movement needs attention: $failure")
                }
                linuxVirtualFileFailure?.let { add("The last Linux mount attempt failed: $it") }
                windowsFailure?.let { add("The Windows Cloud Files integration needs recovery: $it") }
                if (windows) {
                    add("Windows can dehydrate in-sync placeholders automatically when space is needed.")
                }
                if (writebacks.isNotEmpty()) {
                    add("${writebacks.size} staged writeback(s) need recovery before local edits can be discarded.")
                }
                if ((windowsSummary?.pendingWritebackCount ?: 0) > 0) {
                    add("${windowsSummary?.pendingWritebackCount} Windows edit(s) are waiting for conflict-safe writeback.")
                }
                if ((windowsSummary?.failedWritebackCount ?: 0) > 0) {
                    add("${windowsSummary?.failedWritebackCount} Windows edit(s) need attention after bounded retries.")
                }
            },
            providerState = when {
                (windowsSummary?.failedWritebackCount ?: 0) > 0 || windowsCloudFilesRecoveryNotice != null ->
                    VirtualFileProviderState.NeedsAttention
                overflowUnavailable || ranges?.tierAttention != null -> VirtualFileProviderState.NeedsAttention
                rangeCacheResult.isFailure || linuxVirtualFileFailure != null || windowsFailure != null ->
                    VirtualFileProviderState.NeedsAttention
                active -> VirtualFileProviderState.Active
                linux || windows -> VirtualFileProviderState.Inactive
                else -> VirtualFileProviderState.NotApplicable
            },
            providerActive = active,
            providerLocation = when {
                linux -> desktopLinuxVirtualFileMountPoint(preferences, accountId).absolutePath
                windows -> "Nextcloud Native in File Explorer"
                else -> null
            },
            providerLocationConfiguration = if (linux) {
                desktopVirtualFileProviderLocation(preferences, accountId)
            } else {
                null
            },
            providerLocationCanChange = linux && !active,
            providerRecoveryNotice = windowsCloudFilesRecoveryNotice,
            folderRetentionRules = if (linux) {
                folderRetention.rules
            } else {
                emptyList()
            },
            folderHydrationStatuses = if (linux) {
                rangeCache?.loadFolderHydrationStatuses(accountId).orEmpty().filter { status ->
                    folderRetention.rules.any { rule ->
                        rule.relativePath == status.relativePath &&
                            rule.retention == VirtualFolderRetention.KeepOnDevice
                    }
                }.map { status ->
                    virtualFolderHydrationStatusForStorageAvailability(
                        status,
                        status.relativePath in unavailableRetainedRoots,
                    )
                }
            } else {
                emptyList()
            },
            pendingWritebackCount = writebacks.size + (windowsSummary?.pendingWritebackCount ?: 0),
            cacheTiers = cacheTiers,
            primaryCache = cacheTiers?.let { configured ->
                VirtualFileCacheTierSnapshot(
                    path = configured.primaryPath,
                    cachedBytes = ranges?.primaryCachedBytes ?: 0L,
                    reclaimableBytes = ranges?.primaryReclaimableBytes ?: 0L,
                    pinnedBytes = ranges?.primaryPinnedBytes ?: 0L,
                    managedAutomaticBytes = cache.cachedBytes +
                        ((ranges?.primaryCachedBytes ?: 0L) - (ranges?.primaryPinnedBytes ?: 0L)),
                    availableFreeBytes = ranges?.availableFreeBytes,
                    available = rangeCacheResult.isSuccess,
                )
            },
            overflowCache = cacheTiers?.overflowPath?.let { overflowPath ->
                VirtualFileCacheTierSnapshot(
                    path = overflowPath,
                    cachedBytes = ranges?.overflowCachedBytes ?: 0L,
                    reclaimableBytes = ranges?.overflowReclaimableBytes ?: 0L,
                    pinnedBytes = ranges?.overflowPinnedBytes ?: 0L,
                    managedAutomaticBytes = (ranges?.overflowCachedBytes ?: 0L) -
                        (ranges?.overflowPinnedBytes ?: 0L),
                    availableFreeBytes = ranges?.overflowAvailableFreeBytes,
                    available = ranges?.overflowAvailable == true,
                )
            },
        )
    }

    override suspend fun saveVirtualFileCachePolicy(
        session: NextcloudSession,
        userId: String,
        policy: VirtualFileCachePolicy,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        fileReadCache.savePolicy(policy)
        enforceCombinedVirtualFileCachePolicy(desktopFileCacheAccountId(session), policy)
        VirtualFileStorageActionResult.Completed("Virtual file storage rules saved.")
    }

    override suspend fun freeUpVirtualFileSpace(
        session: NextcloudSession,
        userId: String,
        requestedBytes: Long,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        require(requestedBytes >= 0L)
        val accountId = desktopFileCacheAccountId(session)
        val before = fileReadCache.virtualFileSummary(accountId).cachedBytes +
            virtualRangeCache(accountId).summary(accountId).cachedBytes
        val windowsFreed = synchronized(virtualFileProviderLock) {
            windowsCloudFilesProvider?.takeIf { windowsCloudFilesIdentity == accountId }
                ?.freeUpSpace(requestedBytes)
        } ?: 0L
        val rangePlan = virtualRangeCache(accountId).freeUp(accountId, (requestedBytes - windowsFreed).coerceAtLeast(0L))
        val remaining = (requestedBytes - windowsFreed - rangePlan.plannedFreedBytes).coerceAtLeast(0L)
        fileReadCache.freeUpVirtualFiles(accountId, remaining)
        val after = fileReadCache.virtualFileSummary(accountId).cachedBytes +
            virtualRangeCache(accountId).summary(accountId).cachedBytes
        val freed = windowsFreed + (before - after).coerceAtLeast(0L)
        VirtualFileStorageActionResult.Completed(
            message = if (freed > 0L) {
                "Freed ${formatVirtualFileBytes(freed)} of disposable virtual file content."
            } else {
                "No disposable virtual file content could be freed. Active files were kept."
            },
            freedBytes = freed,
        )
    }

    override suspend fun activateVirtualFileProvider(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        if (!isLinuxDesktop() && !isWindowsDesktop()) {
            return@withContext VirtualFileStorageActionResult.Unsupported(
                "This desktop build does not have a system virtual-file adapter for the current operating system.",
            )
        }
        val accountId = desktopFileCacheAccountId(session)
        var windowsCloudFilesRecoveryNotice = if (isWindowsDesktop()) {
            persistedWindowsCloudFilesRecoveryNotice(preferences, accountId)
        } else {
            null
        }
        synchronized(virtualFileProviderLock) {
            if (isWindowsDesktop()) {
                val recordCloudFilesDiagnostic: (SupportDiagnosticEventDraft) -> Unit = { event ->
                    supportDiagnostics.recordForAccountIdentity(accountId, event)
                }
                if (
                    windowsCloudFilesProvider != null && windowsCloudFilesIdentity == accountId &&
                    windowsCloudFilesFailure == null && windowsCloudFilesProvider?.runtimeRecoveryFailure() == null
                ) {
                    return@withContext VirtualFileStorageActionResult.Completed(
                        "Windows Cloud Files are already connected at ${desktopWindowsCloudFilesRoot(accountId).absolutePath}.",
                    )
                }
                val replacedProvider = windowsCloudFilesProvider
                closeVirtualFileProviderForReplacement(
                    provider = replacedProvider,
                    detach = {
                        windowsCloudFilesProvider = null
                        windowsCloudFilesIdentity = null
                    },
                )?.let { failure ->
                    windowsCloudFilesFailure = failure.message ?: "Unknown Cloud Files cleanup failure"
                    recordVirtualFileFailure(
                        operation = "cloud-files.failed-provider-cleanup",
                        accountId = accountId,
                        root = desktopWindowsCloudFilesRoot(accountId).toPath(),
                        failure = failure,
                    )
                    throw failure
                }
                val root = desktopWindowsCloudFilesRoot(accountId).toPath()
                val userHome = File(System.getProperty("user.home"))
                val backend = DesktopNextcloudWindowsCloudFilesBackend(
                    session = session,
                    userId = userId,
                    services = this@DesktopNextcloudServices,
                )
                val legacyRoot = validatedWindowsCloudFilesRoot(
                    desktopLegacyWindowsCloudFilesRoot(accountId, userHome),
                    userHome,
                )
                try {
                    if (Files.exists(legacyRoot)) {
                        val legacyProvider = WindowsCloudFilesProvider(
                            root = legacyRoot,
                            backend = backend,
                            api = JnaWindowsCloudFilesApi(recordDiagnostic = recordCloudFilesDiagnostic),
                            recordDiagnostic = recordCloudFilesDiagnostic,
                            recordPreservedCorruptRoot = { preserved ->
                                persistWindowsCloudFilesPreservedRoot(preferences, accountId, preserved)
                                windowsCloudFilesRecoveryNotice = windowsCloudFilesRecoveryNoticeMessage(preserved)
                            },
                        )
                        try {
                            legacyProvider.start()
                            legacyProvider.recoverBeforeRootMigration()
                            legacyProvider.removeSyncRoot()
                            clearWindowsCloudFilesRootPreferences(preferences, accountId, legacyRoot)
                        } catch (failure: Throwable) {
                            runCatching(legacyProvider::close)
                            throw failure
                        }
                    } else {
                        JnaWindowsCloudFilesApi(recordDiagnostic = recordCloudFilesDiagnostic).use { cleanupApi ->
                            unregisterSupersededWindowsCloudFilesRoot(
                                preferences = preferences,
                                accountId = accountId,
                                userHome = userHome,
                                api = cleanupApi,
                            )
                        }
                    }
                } catch (failure: Throwable) {
                    windowsCloudFilesFailure = failure.message ?: "Unknown Cloud Files migration failure"
                    recordVirtualFileFailure(
                        operation = "cloud-files.legacy-cleanup",
                        accountId = accountId,
                        root = legacyRoot,
                        failure = failure,
                    )
                    throw failure
                }
                val api = JnaWindowsCloudFilesApi(recordDiagnostic = recordCloudFilesDiagnostic)
                lateinit var provider: WindowsCloudFilesProvider
                provider = WindowsCloudFilesProvider(
                    root = root,
                    backend = backend,
                    api = api,
                    recordDiagnostic = recordCloudFilesDiagnostic,
                    recordPreservedCorruptRoot = { preserved ->
                        persistWindowsCloudFilesPreservedRoot(preferences, accountId, preserved)
                        windowsCloudFilesRecoveryNotice = windowsCloudFilesRecoveryNoticeMessage(preserved)
                    },
                    onRuntimeFailure = { failure ->
                        if (windowsCloudFilesProvider === provider) {
                            windowsCloudFilesFailure = failure.message ?: "Unknown Cloud Files recovery failure"
                        }
                        recordVirtualFileFailure(
                            operation = "cloud-files.runtime-recovery",
                            accountId = accountId,
                            root = root,
                            failure = failure,
                        )
                    },
                )
                windowsCloudFilesProvider = provider
                windowsCloudFilesIdentity = accountId
                // Clear an earlier activation error before this provider can report a runtime
                // failure. Never clear it after startup, where that would race the callback.
                windowsCloudFilesFailure = null
                try {
                    provider.start()
                    provider.recoverAfterStartup()
                    provider.runtimeRecoveryFailure()?.let { throw it }
                    preferences.put(
                        windowsCloudFilesRootPreferenceKey(accountId),
                        root.toAbsolutePath().toString(),
                    )
                    preferences.remove(KEY_WINDOWS_CLOUD_FILES_ROOT)
                    preferences.putBoolean(virtualFileProviderPreferenceKey(accountId), true)
                } catch (failure: Throwable) {
                    runCatching(provider::close)
                    if (windowsCloudFilesProvider === provider) {
                        windowsCloudFilesProvider = null
                        windowsCloudFilesIdentity = null
                    }
                    windowsCloudFilesFailure = failure.message ?: "Unknown Cloud Files activation failure"
                    recordVirtualFileFailure(
                        operation = "cloud-files.activation",
                        accountId = accountId,
                        root = root,
                        failure = failure,
                    )
                    throw failure
                }
                return@withContext VirtualFileStorageActionResult.Completed(
                    windowsCloudFilesRecoveryNotice
                        ?: "Windows Cloud Files connected at ${desktopWindowsCloudFilesRoot(accountId).absolutePath}.",
                )
            }
            if (linuxVirtualFileSystem != null && linuxVirtualFileMountIdentity == accountId) {
                return@withContext VirtualFileStorageActionResult.Completed(
                    "Virtual files are already mounted at ${desktopLinuxVirtualFileMountPoint(preferences, accountId).absolutePath}.",
                )
            }
            if (linuxVirtualFileSystem != null) {
                linuxVirtualFileSystem?.unmount()
                linuxVirtualFileSystem = null
                linuxVirtualMetadataBackend = null
                linuxVirtualFileMountIdentity = null
            }
            val location = desktopVirtualFileProviderLocation(preferences, accountId)
            val mountPath = validateDesktopVirtualFileProviderLocation(location)
            if (Files.notExists(mountPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(mountPath)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // A second app process may have created the same validated mount point.
                }
            }
            val mountPoint = mountPath.toFile()
            check(!Files.isSymbolicLink(mountPoint.toPath())) { "The virtual-files mount folder cannot be a symlink." }
            check(mountPoint.list().orEmpty().isEmpty()) {
                "The virtual-files mount folder must be empty before it can be activated."
            }
            val writebackStore = defaultDesktopLinuxWritebackStore(session)
            val recoveredWritebackPaths = linkedSetOf<String>()
            writebackStore.recoverPending(
                tree = DesktopFileSyncRemoteTree(session, userId, ""),
                onCommitted = { path ->
                    runCatching { virtualRangeCache(accountId).invalidate(accountId, path) }
                    refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
                    recoveredWritebackPaths += path
                },
            )
            var metadataBackendReference: CachingLinuxVirtualFileBackend? = null
            val virtualBackend = DesktopNextcloudVirtualFileBackend(
                session = session,
                userId = userId,
                services = this@DesktopNextcloudServices,
                rangeCache = virtualRangeCache(accountId),
                writebacks = writebackStore,
                onMutationCommitted = { path ->
                    metadataBackendReference?.invalidateAfterExternalMutation(path)
                },
                onAmbiguousMutationResult = { path ->
                    metadataBackendReference?.invalidateAfterExternalMutation(path)
                },
            )
            val metadataBackend = CachingLinuxVirtualFileBackend(
                delegate = virtualBackend,
                store = RetainedLinuxVirtualMetadataStore(
                    rangeCache = virtualRangeCache(accountId),
                    accountId = accountId,
                    fallback = DesktopLinuxVirtualMetadataStore(fileReadCache, accountId),
                    afterRetainedListingChanged = { changedPaths ->
                        refreshRetainedFoldersAfterRemoteListing(session, userId, accountId, changedPaths)
                    },
                ),
                afterMutationInvalidated = { path ->
                    refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
                },
            )
            metadataBackendReference = metadataBackend
            recoveredWritebackPaths.forEach(metadataBackend::invalidateAfterExternalMutation)
            val fileSystem = LinuxNextcloudVirtualFileSystem(metadataBackend)
            try {
                fileSystem.mountAt(mountPoint.toPath())
                linuxVirtualFileSystem = fileSystem
                linuxVirtualMetadataBackend = metadataBackend
                linuxVirtualFileMountIdentity = accountId
                linuxVirtualFileFailure = null
                preferences.putBoolean(virtualFileProviderPreferenceKey(accountId), true)
            } catch (failure: Throwable) {
                runCatching(fileSystem::unmount).onFailure {
                    runCatching(metadataBackend::close)
                }
                linuxVirtualFileFailure = failure.message ?: "Unknown FUSE mount failure"
                recordVirtualFileFailure(
                    operation = "fuse.activation",
                    accountId = accountId,
                    root = mountPoint.toPath(),
                    failure = failure,
                )
                throw failure
            }
        }
        VirtualFileStorageActionResult.Completed(
            "Virtual files mounted at ${desktopLinuxVirtualFileMountPoint(preferences, accountId).absolutePath}.",
        )
    }

    override suspend fun deactivateVirtualFileProvider(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        synchronized(virtualFileProviderLock) {
            linuxVirtualFileSystem?.unmount()
            linuxVirtualFileSystem = null
            linuxVirtualMetadataBackend = null
            linuxVirtualFileMountIdentity = null
            linuxVirtualFileFailure = null
            windowsCloudFilesProvider?.close()
            windowsCloudFilesProvider = null
            windowsCloudFilesIdentity = null
            windowsCloudFilesFailure = null
            preferences.putBoolean(
                virtualFileProviderPreferenceKey(desktopFileCacheAccountId(session)),
                false,
            )
        }
        VirtualFileStorageActionResult.Completed(
            if (isWindowsDesktop()) {
                "Windows Cloud Files disconnected. Placeholders, cached content, and remote files were kept."
            } else {
                "Virtual files unmounted. Cached content and remote files were kept."
            },
        )
    }

    override suspend fun acknowledgeVirtualFileProviderRecovery(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        if (!isWindowsDesktop()) {
            return@withContext VirtualFileStorageActionResult.Unsupported(
                "A Windows Cloud Files recovery notice is not available on this platform.",
            )
        }
        val accountId = desktopFileCacheAccountId(session)
        acknowledgeWindowsCloudFilesPreservedRoot(preferences, accountId)
        VirtualFileStorageActionResult.Completed(
            "Recovery notice dismissed. The preserved local folder and its files were not deleted.",
        )
    }

    override suspend fun chooseVirtualFileProviderParent(initialParentPath: String?): String? =
        withContext(Dispatchers.IO) {
            val selectedFile = invokeOnSwingEventThread {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Choose where Nextcloud Native appears"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                    initialParentPath?.let(::File)?.takeIf(File::isDirectory)?.let {
                        currentDirectory = it
                    }
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile
                } else {
                    null
                }
            }
            val selected = selectedFile?.toPath()?.toAbsolutePath()?.normalize()
                ?: return@withContext null
            require(Files.isDirectory(selected, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(selected)) {
                "Choose a regular local drive or folder, not a symbolic link."
            }
            VirtualFileProviderLocation(selected.toString(), "Nextcloud Native").parentPath
        }

    override suspend fun chooseVirtualFileCacheLocation(initialPath: String?): String? =
        withContext(Dispatchers.IO) {
            val selectedFile = invokeOnSwingEventThread {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Choose a virtual-file cache folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                    initialPath?.let(::File)?.takeIf(File::isDirectory)?.let { currentDirectory = it }
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
            val selected = selectedFile?.toPath()?.toAbsolutePath()?.normalize() ?: return@withContext null
            validateDesktopVirtualFileCacheTierPath(selected.toString()).toString()
        }

    override suspend fun saveVirtualFileCacheTiers(
        session: NextcloudSession,
        userId: String,
        configuration: VirtualFileCacheTierConfiguration,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        if (!isLinuxDesktop()) {
            return@withContext VirtualFileStorageActionResult.Unsupported(
                "Tiered virtual-file cache locations are currently available on Linux.",
            )
        }
        val accountId = desktopFileCacheAccountId(session)
        synchronized(virtualFileProviderLock) {
            if (linuxVirtualFileSystem != null && linuxVirtualFileMountIdentity == accountId) {
                return@withContext VirtualFileStorageActionResult.Rejected(
                    "Disconnect the file-manager integration before changing cache drives.",
                )
            }
            val primary = validateDesktopVirtualFileCacheTierPath(configuration.primaryPath)
            val overflow = configuration.overflowPath?.let(::validateDesktopVirtualFileCacheTierPath)
            if (
                runCatching {
                    encodeDesktopVirtualFilePrimaryPreference(
                        primary.toString(),
                        "00000000-0000-0000-0000-000000000000",
                    )
                }.isFailure
            ) {
                return@withContext VirtualFileStorageActionResult.Rejected(
                    "The selected primary cache path is too long.",
                )
            }
            if (
                overflow != null &&
                runCatching {
                    encodeDesktopVirtualFileOverflowPreference(
                        overflow.toString(),
                        "00000000-0000-0000-0000-000000000000",
                    )
                }.isFailure
            ) {
                return@withContext VirtualFileStorageActionResult.Rejected(
                    "The selected overflow cache path is too long.",
                )
            }
            if (overflow != null && desktopVirtualFileCacheTierPathsOverlap(primary, overflow)) {
                return@withContext VirtualFileStorageActionResult.Rejected(
                    "Choose separate, non-nested folders for the primary and overflow caches.",
                )
            }
            val mountPoint = desktopLinuxVirtualFileMountPoint(preferences, accountId).toPath()
                .toAbsolutePath().normalize()
            if (
                desktopVirtualFileCacheTierPathsOverlap(primary, mountPoint) ||
                overflow != null && desktopVirtualFileCacheTierPathsOverlap(overflow, mountPoint)
            ) {
                return@withContext VirtualFileStorageActionResult.Rejected(
                    "Cache folders must stay outside the visible virtual-files folder.",
                )
            }
            val currentTiers = desktopVirtualFileCacheTiers(preferences, accountId)
            val currentConfiguration = currentTiers.configuration
            val currentCache = virtualRangeCache(accountId)
            val normalizedOverflow = overflow?.toString()
            val primaryChanges = primary.toString() != currentConfiguration.primaryPath
            val overflowChanges = normalizedOverflow != currentConfiguration.overflowPath
            if (!primaryChanges && !overflowChanges) {
                return@withContext VirtualFileStorageActionResult.Completed(
                    "The virtual-file cache already uses these locations.",
                )
            }
            if (!virtualFileCacheTierMutations.add(accountId)) {
                return@withContext VirtualFileStorageActionResult.Rejected(
                    "A virtual-file cache location change is already in progress.",
                )
            }
            try {
                val hydrationActive = synchronized(virtualFolderHydrationJobs) {
                    virtualFolderHydrationJobs.any { (key, job) ->
                        key.startsWith("$accountId\u0000") && job.occupiesVirtualFolderHydrationSlot()
                    }
                }
                if (hydrationActive) {
                    return@withContext VirtualFileStorageActionResult.Rejected(
                        "Wait for kept-folder downloads to finish before changing cache drives.",
                    )
                }
                if (overflowChanges) {
                    runCatching { currentCache.consolidateOverflow(accountId) }.getOrElse { failure ->
                        return@withContext VirtualFileStorageActionResult.Rejected(
                            failure.message ?: "Could not preserve the current overflow cache.",
                        )
                    }
                }
                val targetCache = runCatching {
                    val primaryIdentity = if (!primaryChanges) currentTiers.primaryIdentity else null
                    val overflowIdentity = overflow?.let { selected ->
                        if (!overflowChanges) {
                            currentTiers.overflowIdentity
                                ?: DesktopVirtualRangeCache.initializeOverflowRootIdentity(selected.toFile())
                        } else {
                            DesktopVirtualRangeCache.initializeOverflowRootIdentity(selected.toFile())
                        }
                    }
                    DesktopVirtualRangeCache(
                        root = primary.toFile(),
                        overflowRoot = overflow?.toFile(),
                        initializePrimaryMarker = primaryChanges || primaryIdentity == null,
                        expectedPrimaryIdentity = primaryIdentity,
                        requirePrimaryIdentity = true,
                        expectedOverflowIdentity = overflowIdentity,
                        policy = fileReadCache::loadPolicy,
                        createParentDirectories = false,
                    )
                }.getOrElse { failure ->
                    return@withContext VirtualFileStorageActionResult.Rejected(
                        failure.message ?: "Could not prepare the selected cache folders.",
                    )
                }
                if (primaryChanges) {
                    runCatching { currentCache.copyPrimaryAccountTo(accountId, targetCache) }.getOrElse { failure ->
                        return@withContext VirtualFileStorageActionResult.Rejected(
                            failure.message ?: "Could not move the primary cache safely.",
                        )
                    }
                }
                preferences.put(
                    virtualFileCachePreferenceKey(KEY_VIRTUAL_FILE_PRIMARY_CACHE_PREFIX, accountId),
                    encodeDesktopVirtualFilePrimaryPreference(
                        primary.toString(),
                        requireNotNull(targetCache.primaryIdentity()),
                    ),
                )
                val overflowKey = virtualFileCachePreferenceKey(KEY_VIRTUAL_FILE_OVERFLOW_CACHE_PREFIX, accountId)
                if (overflow == null) {
                    preferences.remove(overflowKey)
                } else {
                    preferences.put(
                        overflowKey,
                        encodeDesktopVirtualFileOverflowPreference(
                            overflow.toString(),
                            requireNotNull(targetCache.overflowIdentity()),
                        ),
                    )
                }
                preferences.flush()
                synchronized(virtualRangeCaches) { virtualRangeCaches.remove(accountId) }
                if (primaryChanges) runCatching { currentCache.removeCopiedPrimaryAccount(accountId) }
                VirtualFileStorageActionResult.Completed(
                    if (overflow == null) {
                        "Primary virtual-file cache saved. Overflow storage is off."
                    } else {
                        "Primary and overflow virtual-file cache locations saved."
                    },
                )
            } finally {
                virtualFileCacheTierMutations.remove(accountId)
            }
        }
    }

    override suspend fun saveVirtualFileProviderLocation(
        session: NextcloudSession,
        userId: String,
        location: VirtualFileProviderLocation,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        if (!isLinuxDesktop()) {
            return@withContext VirtualFileStorageActionResult.Unsupported(
                "Changing the system virtual-file location is not available on this desktop platform yet.",
            )
        }
        val accountId = desktopFileCacheAccountId(session)
        synchronized(virtualFileProviderLock) {
            if (linuxVirtualFileSystem != null && linuxVirtualFileMountIdentity == accountId) {
                return@withContext VirtualFileStorageActionResult.Rejected(
                    "Disconnect the file-manager integration before changing its location.",
                )
            }
            val target = validateDesktopVirtualFileProviderLocation(location)
            if (target == desktopLinuxVirtualFileMountPoint(preferences, accountId).toPath()) {
                return@withContext VirtualFileStorageActionResult.Completed(
                    virtualFileLocationActionMessage("Virtual files already use ", target.toString()),
                )
            }
            val currentLocation = desktopVirtualFileProviderLocation(preferences, accountId)
            val primaryCacheIsExplicit = preferences.get(
                virtualFileCachePreferenceKey(KEY_VIRTUAL_FILE_PRIMARY_CACHE_PREFIX, accountId),
                null,
            ) != null
            if (!primaryCacheIsExplicit && desktopVirtualFileCacheRootChanges(currentLocation, target)) {
                val currentCacheResult = runCatching { virtualRangeCache(accountId) }
                val currentCache = currentCacheResult.getOrNull()
                if (currentCache == null) {
                    val currentParent = File(currentLocation.parentPath).toPath().toAbsolutePath().normalize()
                    if (!hasInvalidDesktopVirtualFileCacheRoot(currentParent)) currentCacheResult.getOrThrow()
                } else {
                    currentCache.requireAvailable()
                    if (
                        currentCache.summary(accountId).cachedBytes > 0L ||
                        currentCache.loadFolderRetention(accountId).rules.isNotEmpty()
                    ) {
                        return@withContext VirtualFileStorageActionResult.Rejected(
                            "Make kept folders online-only and free disposable content before moving the storage drive.",
                        )
                    }
                }
            }
            preferences.put(virtualFileProviderRootPreferenceKey(accountId), target.toString())
            synchronized(virtualRangeCaches) { virtualRangeCaches.remove(accountId) }
            VirtualFileStorageActionResult.Completed(
                virtualFileLocationActionMessage("Virtual files will appear at ", target.toString()),
            )
        }
    }

    override suspend fun setVirtualFolderRetention(
        session: NextcloudSession,
        userId: String,
        relativePath: String,
        retention: VirtualFolderRetention,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        if (!isLinuxDesktop()) {
            return@withContext VirtualFileStorageActionResult.Unsupported(
                "Selective virtual folders are not available on this desktop platform yet.",
            )
        }
        virtualFolderRetentionMutex.withLock {
            val normalized = FileOfflineKey("account", relativePath).relativePath
            if (retention == VirtualFolderRetention.KeepOnDevice) {
                val selected = DesktopFileSyncRemoteTree(session, userId, "").resolve(normalized)
                if (selected == null || !selected.isDirectory) {
                    return@withLock VirtualFileStorageActionResult.Rejected("Choose an existing Nextcloud folder.")
                }
            }
            val accountId = desktopFileCacheAccountId(session)
            val cache = virtualRangeCache(accountId)
            val currentRetention = cache.loadFolderRetention(accountId)
            if (
                retention == VirtualFolderRetention.KeepOnDevice &&
                currentRetention.retentionFor(normalized) == VirtualFolderRetention.KeepOnDevice &&
                currentRetention.rules.none { rule -> rule.relativePath == normalized }
            ) {
                return@withLock VirtualFileStorageActionResult.Completed(
                    "${normalized.substringAfterLast('/')} is already covered by a kept parent folder.",
                )
            }
            cancelAllVirtualFolderHydration(accountId).forEach { job -> job.join() }
            synchronized(virtualFolderMutationLock) {
                cache.setFolderRetention(accountId, normalized, retention)
            }
            cancelAllVirtualFolderHydration(accountId).forEach { job -> job.join() }
            val nextRetention = cache.loadFolderRetention(accountId)
            val retainedJobKeys = nextRetention.rules.asSequence()
                .filter { rule -> rule.retention == VirtualFolderRetention.KeepOnDevice }
                .mapTo(hashSetOf()) { rule -> "$accountId\u0000${rule.relativePath}" }
            val accountJobPrefix = "$accountId\u0000"
            synchronized(virtualFolderMutationLock) {
                virtualFolderMutationGenerationsByJob.keys.removeIf { key ->
                    key.startsWith(accountJobPrefix) && key !in retainedJobKeys
                }
                virtualFolderCompletedGenerations.keys.removeIf { key ->
                    key.startsWith(accountJobPrefix) && key !in retainedJobKeys
                }
                virtualFolderRetryAtEpochMillis.keys.removeIf { key ->
                    key.startsWith(accountJobPrefix) && key !in retainedJobKeys
                }
            }
            val result = if (retention == VirtualFolderRetention.KeepOnDevice) {
                val retainedRoot = checkNotNull(nextRetention.keepOnDeviceRootFor(normalized)) {
                    "The selected folder did not resolve to a retained root."
                }
                synchronized(virtualFolderMutationLock) {
                    virtualFolderRetryAtEpochMillis.remove("$accountId\u0000$retainedRoot")
                }
                cache.setFolderHydrationStatus(
                    accountId,
                    VirtualFolderHydrationStatus(retainedRoot, VirtualFolderHydrationPhase.Queued),
                )
                VirtualFileStorageActionResult.Completed(
                    "${normalized.substringAfterLast('/')} was selected for offline use. " +
                        "Downloading continues in the background.",
                )
            } else {
                synchronized(virtualFolderMutationLock) {
                    val key = "$accountId\u0000$normalized"
                    virtualFolderRetryAtEpochMillis.remove(key)
                    virtualFolderMutationGenerationsByJob.remove(key)
                    virtualFolderCompletedGenerations.remove(key)
                }
                val protected = defaultDesktopLinuxWritebackStore(session).pendingWritebacks()
                    .mapTo(hashSetOf()) { writeback -> writeback.path }
                val freed = cache.dehydrateFolder(accountId, normalized, protected)
                VirtualFileStorageActionResult.Completed(
                    "${normalized.substringAfterLast('/')} is online-only. Safe local content was released.",
                    freedBytes = freed,
                )
            }
            nextRetention.rules.asSequence()
                .filter { rule -> rule.retention == VirtualFolderRetention.KeepOnDevice }
                .forEach { rule ->
                    scheduleVirtualFolderHydration(session, userId, rule.relativePath, accountId, cache)
                }
            result
        }
    }

    override suspend fun retryVirtualFolderHydration(
        session: NextcloudSession,
        userId: String,
        relativePath: String,
    ): VirtualFileStorageActionResult = withContext(Dispatchers.IO) {
        if (!isLinuxDesktop()) {
            return@withContext VirtualFileStorageActionResult.Unsupported(
                "Selective virtual folders are not available on this desktop platform yet.",
            )
        }
        val normalized = FileOfflineKey("account", relativePath).relativePath
        val accountId = desktopFileCacheAccountId(session)
        val cache = virtualRangeCache(accountId)
        runCatching { cache.retryFolderHydration(accountId, normalized) }
            .getOrElse { failure ->
                return@withContext VirtualFileStorageActionResult.Rejected(
                    failure.message ?: "This folder is no longer selected for offline use.",
                )
            }
        synchronized(virtualFolderMutationLock) {
            virtualFolderRetryAtEpochMillis.remove("$accountId\u0000$normalized")
        }
        scheduleVirtualFolderHydration(session, userId, normalized, accountId, cache)
        VirtualFileStorageActionResult.Completed(
            "${normalized.substringAfterLast('/')} will retry downloading in the background.",
        )
    }

    override fun close() {
        val rangeSessions = synchronized(fileRangeSessionLock) {
            sessionClearing = true
            activeFileRangeSessions.toList()
        }
        serviceScope.cancel()
        rangeSessions.forEach { source -> runCatching(source::close) }
        synchronized(virtualRangeCaches) {
            virtualRangeCaches.values.forEach { cache -> runCatching(cache::flushAccessTimes) }
        }
        val providersToClose = synchronized(virtualFileProviderLock) {
            (linuxVirtualFileSystem to windowsCloudFilesProvider).also {
                linuxVirtualFileSystem = null
                linuxVirtualMetadataBackend = null
                linuxVirtualFileMountIdentity = null
                windowsCloudFilesProvider = null
                windowsCloudFilesIdentity = null
            }
        }
        // A retained-metadata persistence callback can briefly enter virtualFileProviderLock.
        // Closing its backend while holding the same lock reverses that order and deadlocks.
        runCatching { providersToClose.first?.unmount() }
        runCatching { providersToClose.second?.close() }
        supportIntake.close()
        supportDiagnostics.close()
        if (ownsTemporarySupportIntakeRoot) resolvedSupportIntakeRoot.deleteRecursively()
        if (ownsTemporarySupportDiagnosticsRoot) requireNotNull(resolvedSupportDiagnosticsRoot).deleteRecursively()
    }

    private fun invalidateDesktopFileMetadata(accountId: String, path: String) {
        synchronized(virtualFileProviderLock) {
            val mountedBackend = linuxVirtualMetadataBackend
                ?.takeIf { linuxVirtualFileMountIdentity == accountId }
            if (mountedBackend != null) {
                mountedBackend.invalidateAfterExternalMutation(path)
            } else {
                fileReadCache.invalidate(accountId, path)
            }
        }
    }

    override suspend fun chooseFileSyncLocalRoot(initialRootHint: String?): FileSyncLocalRoot? =
        fileSyncEngine.chooseLocalRoot(initialRootHint)

    override suspend fun loadFileSyncCenter(
        session: NextcloudSession,
        userId: String,
    ): FileSyncCenterSnapshot = withContext(Dispatchers.IO) {
        val center = loadDesktopFileSyncCenter(session)
        publishFileSyncTraySnapshot(center, fileSyncEngine.loadTrayActivities(session))
        center
    }

    private suspend fun loadDesktopFileSyncCenter(session: NextcloudSession): FileSyncCenterSnapshot {
        val runtimeConditions = desktopFileSyncRuntimeConditions()
        return fileSyncEngine.loadCenter(
            session = session,
            runState = if (isFileSyncPaused()) {
                FileSyncPairRunState.Paused
            } else {
                FileSyncPairRunState.Active
            },
            networkState = runtimeConditions::networkState,
        )
    }

    override suspend fun addFileSyncPair(
        session: NextcloudSession,
        userId: String,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        val diagnosticFields = listOf(
            SupportDiagnosticFieldDraft("local_root", localRoot.localRootId, SupportDiagnosticValuePrivacy.LocalPath),
            SupportDiagnosticFieldDraft("remote_root", remoteRootPath, SupportDiagnosticValuePrivacy.RemotePath),
        )
        diagnoseDesktopSupportFailure(accountId, "sync.pair-add", diagnosticFields) {
            fileSyncEngine.addPair(session, localRoot, remoteRootPath, configuration)
        }.also { result ->
            recordDesktopFileSyncResult(accountId, "sync.pair-add", diagnosticFields, result)
            runCatching {
                publishFileSyncTraySnapshot(
                    loadDesktopFileSyncCenter(session),
                    fileSyncEngine.loadTrayActivities(session),
                )
            }
        }
    }

    override suspend fun runFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        val diagnosticFields = listOf(
            SupportDiagnosticFieldDraft("pair", pairId, SupportDiagnosticValuePrivacy.Identifier),
        )
        diagnoseDesktopSupportFailure(accountId, "sync.pair-run", diagnosticFields) {
            fileSyncRunLock.withLock {
                if (isFileSyncPaused()) {
                    return@withLock FileSyncCenterActionResult.Rejected(
                        "Desktop syncing is paused. Resume it from the system tray first.",
                    )
                }
                mutableFileSyncTraySnapshot.value = mutableFileSyncTraySnapshot.value.copy(
                    phase = DesktopFileSyncTrayPhase.Syncing,
                    message = "Checking folder changes",
                )
                try {
                    fileSyncEngine.runPair(
                        session,
                        userId,
                        pairId,
                        onProgress = { event -> publishFileSyncProgress(accountId, event) },
                        shouldContinue = { !isFileSyncPaused() },
                        resetExhaustedFailures = true,
                    )
                } finally {
                    runCatching {
                        publishFileSyncTraySnapshot(
                            loadDesktopFileSyncCenter(session),
                            fileSyncEngine.loadTrayActivities(session),
                        )
                    }
                }
            }
        }.also { result -> recordDesktopFileSyncResult(accountId, "sync.pair-run", diagnosticFields, result) }
    }

    override suspend fun resolveFileSyncConflict(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        workId: Long,
        choice: FileSyncDecisionChoice,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        val diagnosticFields = listOf(
            SupportDiagnosticFieldDraft("pair", pairId, SupportDiagnosticValuePrivacy.Identifier),
            SupportDiagnosticFieldDraft(
                "work",
                workId.toString(),
                SupportDiagnosticValuePrivacy.Identifier,
            ),
            SupportDiagnosticFieldDraft("choice", choice.name.lowercase()),
        )
        diagnoseDesktopSupportFailure(accountId, "sync.conflict-resolve", diagnosticFields) {
            fileSyncRunLock.withLock {
                if (isFileSyncPaused()) {
                    return@withLock FileSyncCenterActionResult.Rejected(
                        "Desktop syncing is paused. Resume it from the system tray first.",
                    )
                }
                mutableFileSyncTraySnapshot.value = mutableFileSyncTraySnapshot.value.copy(
                    phase = DesktopFileSyncTrayPhase.Syncing,
                    message = "Resolving sync conflict",
                )
                try {
                    fileSyncEngine.resolveConflictAndRun(
                        session,
                        userId,
                        pairId,
                        workId,
                        choice,
                        onProgress = { event -> publishFileSyncProgress(accountId, event) },
                        shouldContinue = { !isFileSyncPaused() },
                    )
                } finally {
                    runCatching {
                        publishFileSyncTraySnapshot(
                            loadDesktopFileSyncCenter(session),
                            fileSyncEngine.loadTrayActivities(session),
                        )
                    }
                }
            }
        }.also { result ->
            recordDesktopFileSyncResult(accountId, "sync.conflict-resolve", diagnosticFields, result)
        }
    }

    override suspend fun removeFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        val diagnosticFields = listOf(
            SupportDiagnosticFieldDraft("pair", pairId, SupportDiagnosticValuePrivacy.Identifier),
        )
        diagnoseDesktopSupportFailure(accountId, "sync.pair-remove", diagnosticFields) {
            fileSyncEngine.removePair(session, pairId)
        }.also { result ->
            recordDesktopFileSyncResult(accountId, "sync.pair-remove", diagnosticFields, result)
            runCatching {
                publishFileSyncTraySnapshot(
                    loadDesktopFileSyncCenter(session),
                    fileSyncEngine.loadTrayActivities(session),
                )
            }
        }
    }

    fun isFileSyncPaused(): Boolean = preferences.getBoolean(KEY_FILE_SYNC_PAUSED, false)

    override val supportsStartOnLogin: Boolean = true

    override fun loadStartOnLoginPreference(): Boolean = preferences.getBoolean(KEY_START_ON_LOGIN, false)

    override fun saveStartOnLoginPreference(enabled: Boolean): String? {
        return runCatching {
            val result = startOnLoginController.configure(enabled)
            if (result.configured) preferences.putBoolean(KEY_START_ON_LOGIN, enabled)
            result.message.takeUnless { result.configured }
        }.getOrElse { failure ->
            failure.message ?: "Start on login could not be updated."
        }
    }

    override val supportsKeepRunningInBackground: Boolean = true

    override fun loadKeepRunningInBackgroundPreference(): Boolean =
        preferences.getBoolean(KEY_KEEP_RUNNING_IN_BACKGROUND, true)

    override fun saveKeepRunningInBackgroundPreference(enabled: Boolean) {
        preferences.putBoolean(KEY_KEEP_RUNNING_IN_BACKGROUND, enabled)
        onKeepRunningInBackgroundChanged(enabled)
    }

    private fun restoreConfirmedStartOnLoginRegistration() {
        val confirmedPreference = preferences.get(KEY_START_ON_LOGIN, null)
        runCatching {
            startOnLoginController.configure(confirmedPreference?.toBooleanStrictOrNull() == true)
        }
    }

    fun setFileSyncPaused(paused: Boolean) {
        preferences.putBoolean(KEY_FILE_SYNC_PAUSED, paused)
        val current = mutableFileSyncTraySnapshot.value
        mutableFileSyncTraySnapshot.value = current.copy(
            phase = if (paused) DesktopFileSyncTrayPhase.Paused else {
                if (current.conflictCount + current.failedCount > 0) {
                    DesktopFileSyncTrayPhase.NeedsAttention
                } else {
                    DesktopFileSyncTrayPhase.Idle
                }
            },
            message = if (paused) "Sync is paused" else null,
        )
        if (!paused) {
            serviceScope.launch {
                runCatching { syncAllFileSyncPairs(DesktopFileSyncRunSource.Resume) }
            }
        }
    }

    suspend fun refreshFileSyncTraySnapshot() = withContext(Dispatchers.IO) {
        val session = loadSession() ?: return@withContext
        publishFileSyncTraySnapshot(
            loadDesktopFileSyncCenter(session),
            fileSyncEngine.loadTrayActivities(session),
        )
    }

    suspend fun syncAllFileSyncPairsFromTray(): FileSyncCenterActionResult =
        syncAllFileSyncPairs(DesktopFileSyncRunSource.Tray)

    private suspend fun syncAllFileSyncPairs(
        source: DesktopFileSyncRunSource,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        var diagnosticAccountId: String? = null
        try {
            fileSyncRunLock.withLock {
                if (isFileSyncPaused()) {
                    return@withLock FileSyncCenterActionResult.Rejected("Desktop syncing is paused.")
                }
                val session = loadSession()
                    ?: return@withLock FileSyncCenterActionResult.Rejected("Sign in before syncing folders.")
                val accountId = desktopFileCacheAccountId(session)
                diagnosticAccountId = accountId
                val userId = runCatching { loadServerInfo(session).userId }.getOrElse { failure ->
                    return@withLock FileSyncCenterActionResult.Rejected(
                        failure.message ?: "Could not load the signed-in account.",
                    )
                }
                val initial = loadDesktopFileSyncCenter(session)
                if (initial.pairs.isEmpty()) {
                    publishFileSyncTraySnapshot(initial, emptyList())
                    return@withLock FileSyncCenterActionResult.Completed("No desktop sync folders are configured.")
                }
                mutableFileSyncTraySnapshot.value = mutableFileSyncTraySnapshot.value.copy(
                    phase = DesktopFileSyncTrayPhase.Syncing,
                    message = if (source == DesktopFileSyncRunSource.Background) {
                        "Checking for changes"
                    } else {
                        "Syncing all folders"
                    },
                    accountLabel = session.loginName,
                )
                try {
                    var failures = 0
                    var waitingForConditions = 0
                    initial.pairs.forEach { pair ->
                        if (isFileSyncPaused()) return@forEach
                        fun runtimeAllowsPair(): Boolean =
                            source == DesktopFileSyncRunSource.Tray ||
                                desktopFileSyncRuntimeConditions().allows(pair.configuration)
                        if (!runtimeAllowsPair()) {
                            waitingForConditions += 1
                            return@forEach
                        }
                        val result = try {
                            fileSyncEngine.runPair(
                                session,
                                userId,
                                pair.id,
                                onProgress = { event -> publishFileSyncProgress(accountId, event) },
                                shouldContinue = { !isFileSyncPaused() && runtimeAllowsPair() },
                                resetExhaustedFailures = source == DesktopFileSyncRunSource.Tray,
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            failures += 1
                            return@forEach
                        }
                        if (result is FileSyncCenterActionResult.Rejected) failures += 1
                        if (source != DesktopFileSyncRunSource.Tray && !runtimeAllowsPair()) {
                            waitingForConditions += 1
                        }
                    }
                    if (failures == 0) {
                        FileSyncCenterActionResult.Completed(
                            if (waitingForConditions == 0) {
                                "All desktop sync folders were checked."
                            } else {
                                "$waitingForConditions desktop sync folder(s) are waiting for their network or power rules."
                            },
                        )
                    } else {
                        FileSyncCenterActionResult.Rejected("$failures desktop sync folders need attention.")
                    }
                } finally {
                    runCatching {
                        publishFileSyncTraySnapshot(
                            loadDesktopFileSyncCenter(session),
                            fileSyncEngine.loadTrayActivities(session),
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            publishFileSyncRunFailure(diagnosticAccountId, source, failure)
            FileSyncCenterActionResult.Rejected(
                failure.message ?: "The desktop sync check failed.",
            )
        }
    }

    private fun enforceCombinedVirtualFileCachePolicy(
        accountId: String,
        policy: VirtualFileCachePolicy,
    ) {
        fileReadCache.freeUpVirtualFiles(accountId, requestedBytesToFree = 0L)
        synchronized(virtualFileProviderLock) {
            windowsCloudFilesProvider?.takeIf { windowsCloudFilesIdentity == accountId }?.enforcePolicy(policy)
        }
        if (!policy.automaticCleanup) return
        virtualRangeCache(accountId).freeUp(accountId, 0L)
        val maximumBytes = policy.maximumCacheBytes ?: return
        fun currentExcess(): Long {
            val windows = windowsVirtualFileSummary(accountId)
            val ranges = virtualRangeCache(accountId).summary(accountId)
            return combinedAutomaticCacheExcess(
                maximumBytes,
                fileReadCache.virtualFileSummary(accountId).cachedBytes,
                ranges.primaryCachedBytes - ranges.primaryPinnedBytes,
                windows?.cachedBytes ?: 0L,
                windows?.pinnedBytes ?: 0L,
            )
        }
        var excess = currentExcess()
        if (excess == 0L) return
        synchronized(virtualFileProviderLock) {
            windowsCloudFilesProvider?.takeIf { windowsCloudFilesIdentity == accountId }?.freeUpSpace(excess)
        }
        excess = currentExcess()
        if (excess > 0L) virtualRangeCache(accountId).relievePrimaryPressure(accountId, excess)
        excess = currentExcess()
        if (excess > 0L) fileReadCache.freeUpVirtualFiles(accountId, excess)
    }

    private fun windowsVirtualFileSummary(accountId: String): WindowsCloudFilesSummary? =
        synchronized(virtualFileProviderLock) {
            windowsCloudFilesProvider?.takeIf { windowsCloudFilesIdentity == accountId }?.summary()
        }

    private fun publishFileSyncTraySnapshot(
        center: FileSyncCenterSnapshot,
        durableActivities: List<DesktopFileSyncTrayActivity> = emptyList(),
    ) {
        val conflicts = center.pairs.sumOf(FileSyncPairSummary::conflictCount)
        val failed = center.pairs.sumOf(FileSyncPairSummary::failedCount)
        val paused = isFileSyncPaused()
        val recentCompleted = mutableFileSyncTraySnapshot.value.activities.filter {
            it.phase == DesktopFileSyncTrayActivityPhase.Completed
        }
        val activities = (durableActivities + recentCompleted)
            .distinctBy(DesktopFileSyncTrayActivity::stableId)
            .take(MAX_TRAY_ACTIVITY_ITEMS)
        mutableFileSyncTraySnapshot.value = DesktopFileSyncTraySnapshot(
            phase = when {
                paused -> DesktopFileSyncTrayPhase.Paused
                conflicts + failed > 0 -> DesktopFileSyncTrayPhase.NeedsAttention
                else -> DesktopFileSyncTrayPhase.Idle
            },
            pairCount = center.pairs.size,
            pendingCount = center.pairs.sumOf { it.readyCount + it.runningCount },
            conflictCount = conflicts,
            failedCount = failed,
            message = when {
                paused -> "Sync is paused"
                conflicts + failed > 0 -> "Open Nextcloud Native to review sync problems"
                else -> null
            },
            accountLabel = loadSession()?.loginName,
            overallProgress = null,
            activities = activities,
            lastCheckedEpochMillis = center.pairs.mapNotNull(FileSyncPairSummary::lastScanEpochMillis).maxOrNull(),
        )
    }

    private fun publishFileSyncProgress(accountId: String, event: DesktopFileSyncProgressEvent) {
        if (event.stage == DesktopFileSyncProgressStage.Failed) {
            supportDiagnostics.recordForAccountIdentity(
                accountId,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Error,
                    component = SupportDiagnosticComponent.Sync,
                    operation = "sync.item",
                    outcome = "failed",
                    message = event.failureMessage,
                    fields = listOf(
                        SupportDiagnosticFieldDraft("pair", event.pairId, SupportDiagnosticValuePrivacy.Identifier),
                        SupportDiagnosticFieldDraft(
                            "work",
                            event.workId.toString(),
                            SupportDiagnosticValuePrivacy.Identifier,
                        ),
                        SupportDiagnosticFieldDraft(
                            "relative_path",
                            event.relativePath,
                            SupportDiagnosticValuePrivacy.RemotePath,
                        ),
                        SupportDiagnosticFieldDraft("operation_type", event.operation::class.simpleName.orEmpty()),
                        SupportDiagnosticFieldDraft("completed_operations", event.completedOperations.toString()),
                        SupportDiagnosticFieldDraft("total_operations", event.totalOperations.toString()),
                    ),
                ),
            )
        }
        val current = mutableFileSyncTraySnapshot.value
        val phase = when (event.stage) {
            DesktopFileSyncProgressStage.Started -> event.operation.toTrayActivityPhase()
            DesktopFileSyncProgressStage.Completed -> DesktopFileSyncTrayActivityPhase.Completed
            DesktopFileSyncProgressStage.Failed -> DesktopFileSyncTrayActivityPhase.Failed
        }
        val activity = DesktopFileSyncTrayActivity(
            stableId = event.stableId,
            relativePath = event.relativePath,
            pairLabel = event.pairLabel,
            phase = phase,
            sizeBytes = event.sizeBytes,
            detail = when (event.stage) {
                DesktopFileSyncProgressStage.Started ->
                    "${event.completedOperations + 1} of ${event.totalOperations}"
                DesktopFileSyncProgressStage.Completed -> "Synced safely"
                DesktopFileSyncProgressStage.Failed -> event.failureMessage
            },
        )
        val paused = isFileSyncPaused()
        mutableFileSyncTraySnapshot.value = current.copy(
            phase = if (paused) DesktopFileSyncTrayPhase.Paused else DesktopFileSyncTrayPhase.Syncing,
            pendingCount = (event.totalOperations - event.completedOperations).coerceAtLeast(0),
            message = if (paused) {
                "Pausing after the current file"
            } else when (phase) {
                DesktopFileSyncTrayActivityPhase.Uploading -> "Uploading ${event.relativePath.substringAfterLast('/')}"
                DesktopFileSyncTrayActivityPhase.Downloading ->
                    "Downloading ${event.relativePath.substringAfterLast('/')}"
                DesktopFileSyncTrayActivityPhase.Failed -> "A sync item needs attention"
                else -> "Applying ${event.relativePath.substringAfterLast('/')}"
            },
            overallProgress = event.progressFraction,
            activities = (listOf(activity) + current.activities.filterNot { it.stableId == activity.stableId })
                .take(MAX_TRAY_ACTIVITY_ITEMS),
        )
    }

    private fun publishFileSyncRunFailure(
        accountId: String?,
        source: DesktopFileSyncRunSource,
        failure: Throwable,
    ) {
        val event = SupportDiagnosticEventDraft(
            severity = SupportDiagnosticSeverity.Error,
            component = SupportDiagnosticComponent.Sync,
            operation = "sync.${source.name.lowercase()}-run",
            outcome = "failed",
            exception = failure.toSupportDiagnosticExceptionDraft(),
        )
        if (accountId == null) {
            supportDiagnostics.record(event)
        } else {
            supportDiagnostics.recordForAccountIdentity(accountId, event)
        }
        val current = mutableFileSyncTraySnapshot.value
        val message = failure.message
            ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?.take(1_024)
            ?: "The automatic sync check failed."
        mutableFileSyncTraySnapshot.value = current.copy(
            phase = DesktopFileSyncTrayPhase.NeedsAttention,
            failedCount = current.failedCount + 1,
            message = message,
            overallProgress = null,
        )
    }

    private fun recordDesktopFileSyncResult(
        accountId: String,
        operation: String,
        fields: List<SupportDiagnosticFieldDraft>,
        result: FileSyncCenterActionResult,
    ) {
        supportDiagnostics.recordForAccountIdentity(
            accountId,
            SupportDiagnosticEventDraft(
                severity = if (result is FileSyncCenterActionResult.Completed) {
                    SupportDiagnosticSeverity.Info
                } else {
                    SupportDiagnosticSeverity.Warning
                },
                component = SupportDiagnosticComponent.Sync,
                operation = operation,
                outcome = when (result) {
                    is FileSyncCenterActionResult.Completed -> "completed"
                    is FileSyncCenterActionResult.Rejected -> "rejected"
                    is FileSyncCenterActionResult.Unsupported -> "unsupported"
                },
                message = when (result) {
                    is FileSyncCenterActionResult.Completed -> null
                    is FileSyncCenterActionResult.Rejected -> result.reason
                    is FileSyncCenterActionResult.Unsupported -> result.reason
                },
                fields = fields,
            ),
        )
    }

    private suspend fun <T> diagnoseDesktopSupportFailure(
        accountId: String,
        operation: String,
        fields: List<SupportDiagnosticFieldDraft>,
        block: suspend () -> T,
    ): T {
        val started = System.nanoTime()
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            supportDiagnostics.recordForAccountIdentity(
                accountId,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Error,
                    component = SupportDiagnosticComponent.Sync,
                    operation = operation,
                    outcome = "failed",
                    durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                    fields = fields,
                    exception = failure.toSupportDiagnosticExceptionDraft(),
                ),
            )
            throw failure
        }
    }

    private fun recordVirtualFileFailure(
        operation: String,
        accountId: String,
        root: Path,
        failure: Throwable,
    ) {
        supportDiagnostics.recordForAccountIdentity(
            accountId,
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.VirtualFiles,
                operation = operation,
                outcome = "failed",
                code = windowsCloudFilesDiagnosticCode(failure),
                fields = listOf(
                    SupportDiagnosticFieldDraft("account", accountId, SupportDiagnosticValuePrivacy.Identifier),
                    SupportDiagnosticFieldDraft(
                        "provider_root",
                        root.toAbsolutePath().toString(),
                        SupportDiagnosticValuePrivacy.LocalPath,
                    ),
                ),
                exception = failure.toSupportDiagnosticExceptionDraft(),
            ),
        )
    }

    override fun loadThemePreference(): ThemePreference = runCatching {
        ThemePreference.valueOf(preferences.get(KEY_THEME, ThemePreference.System.name))
    }.getOrDefault(ThemePreference.System)

    override fun saveThemePreference(preference: ThemePreference) {
        preferences.put(KEY_THEME, preference.name)
        onThemePreferenceChanged(preference)
    }

    override suspend fun loadProjectNews(forceRefresh: Boolean): ProjectNewsResult =
        withContext(Dispatchers.IO) {
            val cached = runCatching {
                projectNewsCache
                    .takeIf { it.isFile && it.length() <= MAX_PROJECT_NEWS_FEED_BYTES }
                    ?.readBytes()
                    ?.let(::parseProjectNewsFeed)
            }.getOrNull()
            val cacheAge = System.currentTimeMillis() - projectNewsCache.lastModified()
            if (!forceRefresh && cached != null && cacheAge in 0..6 * 60 * 60 * 1_000L) {
                return@withContext ProjectNewsResult(cached, cached = true)
            }
            runCatching {
                projectContentHttpClient.newCall(
                    Request.Builder().url(PROJECT_NEWS_FEED_URL).get().build(),
                ).execute().use { response ->
                    check(response.isSuccessful) {
                        "Project news request failed (HTTP ${response.code})."
                    }
                    val body = requireNotNull(response.body)
                    check(body.contentLength() in -1..MAX_PROJECT_NEWS_FEED_BYTES.toLong())
                    val bytes = body.byteStream().readBounded(MAX_PROJECT_NEWS_FEED_BYTES.toLong())
                    val feed = parseProjectNewsFeed(bytes)
                    projectNewsCache.parentFile.mkdirs()
                    val temporary = File(projectNewsCache.parentFile, "${projectNewsCache.name}.part")
                    temporary.writeBytes(bytes)
                    publishDesktopProjectContentCache(temporary, projectNewsCache)
                    ProjectNewsResult(feed, cached = false)
                }
            }.getOrElse { failure ->
                cached?.let { ProjectNewsResult(it, cached = true) }
                    ?: throw IllegalStateException(
                        failure.message ?: "Could not load project news.",
                        failure,
                    )
            }
        }

    override suspend fun loadProjectNewsImage(image: ProjectNewsImage): ByteArray =
        withContext(Dispatchers.IO) {
            require(isCanonicalProjectNewsImageUrl(image.url))
            val cached = File(projectNewsImageDirectory, "${image.sha256}.png")
            if (cached.isFile && cached.length() <= MAX_PROJECT_NEWS_IMAGE_BYTES) {
                cached.readBytes().takeIf { publicContentSha256(it) == image.sha256 }
                    ?.let { return@withContext it }
            }
            projectContentHttpClient.newCall(Request.Builder().url(image.url).get().build())
                .execute().use { response ->
                    check(response.isSuccessful) {
                        "Project news image request failed (HTTP ${response.code})."
                    }
                    val body = requireNotNull(response.body)
                    check(body.contentLength() in -1..MAX_PROJECT_NEWS_IMAGE_BYTES.toLong())
                    val bytes = body.byteStream().readBounded(MAX_PROJECT_NEWS_IMAGE_BYTES.toLong())
                    check(publicContentSha256(bytes) == image.sha256) {
                        "Project news image verification failed."
                    }
                    projectNewsImageDirectory.mkdirs()
                    val temporary = File(projectNewsImageDirectory, "${image.sha256}.part")
                    temporary.writeBytes(bytes)
                    publishDesktopProjectContentCache(temporary, cached)
                    bytes
                }
        }

    override fun appUpdateSupport(): AppUpdateSupport = appUpdater.support()

    override fun loadAppUpdateChannel(): AndroidUpdateChannel = appUpdater.updateChannel()

    override fun saveAppUpdateChannel(channel: AndroidUpdateChannel): Boolean =
        appUpdater.saveUpdateChannel(channel)

    override fun loadAppUpdatePreferences(): AppUpdatePreferences =
        appUpdater.updatePreferences()

    override fun saveAppUpdatePreferences(preferences: AppUpdatePreferences): Boolean {
        appUpdater.saveUpdatePreferences(preferences)
        return true
    }

    override fun appUpdateAutomaticCheckIntervalMillis(): Long =
        DESKTOP_APP_UPDATE_CHECK_INTERVAL_MILLIS

    override fun observeAppUpdateCheckResult(): Flow<AppUpdateCheckResult?> =
        appUpdater.observeCheckResult()

    override suspend fun checkForAppUpdate(
        channel: AndroidUpdateChannel,
        automatic: Boolean,
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val result = if (automatic && !appUpdater.updatePreferences().automaticChecks) {
                AppUpdateCheckResult.Unavailable(appUpdater.support())
            } else {
                appUpdater.checkForUpdate(channel)
            }
            when (result) {
                is AppUpdateCheckResult.Available -> supportDiagnostics.record(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Info,
                        component = SupportDiagnosticComponent.Updates,
                        operation = "updates.check",
                        outcome = "available",
                        durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                        fields = listOf(
                            SupportDiagnosticFieldDraft("channel", channel.name.lowercase()),
                            SupportDiagnosticFieldDraft("automatic", automatic.toString()),
                            SupportDiagnosticFieldDraft("release", result.release.versionName),
                        ),
                    ),
                )
                is AppUpdateCheckResult.Failed -> supportDiagnostics.record(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Warning,
                        component = SupportDiagnosticComponent.Updates,
                        operation = "updates.check",
                        outcome = "failed",
                        durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                        message = result.message,
                        fields = listOf(
                            SupportDiagnosticFieldDraft("channel", channel.name.lowercase()),
                            SupportDiagnosticFieldDraft("automatic", automatic.toString()),
                            SupportDiagnosticFieldDraft("retryable", result.retryable.toString()),
                        ),
                    ),
                )
                is AppUpdateCheckResult.Current,
                is AppUpdateCheckResult.Unavailable,
                -> Unit
            }
            result
        } catch (failure: Throwable) {
            supportDiagnostics.record(
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Error,
                    component = SupportDiagnosticComponent.Updates,
                    operation = "updates.check",
                    outcome = "failed",
                    durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                    fields = listOf(
                        SupportDiagnosticFieldDraft("channel", channel.name.lowercase()),
                        SupportDiagnosticFieldDraft("automatic", automatic.toString()),
                    ),
                    exception = failure.toSupportDiagnosticExceptionDraft(),
                ),
            )
            throw failure
        }
    }

    override fun observeAppUpdateInstallState(): Flow<AppUpdateInstallState> =
        appUpdater.observeInstallState()

    override suspend fun beginAppUpdate(release: AppUpdateRelease): AppUpdateInstallResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                val result = appUpdater.beginUpdate(release)
                supportDiagnostics.record(
                    SupportDiagnosticEventDraft(
                        severity = when (result) {
                            AppUpdateInstallResult.ConfirmationOpened,
                            AppUpdateInstallResult.Installed,
                            -> SupportDiagnosticSeverity.Info
                            is AppUpdateInstallResult.Cancelled,
                            is AppUpdateInstallResult.PermissionRequired,
                            is AppUpdateInstallResult.Rejected,
                            -> SupportDiagnosticSeverity.Warning
                        },
                        component = SupportDiagnosticComponent.Updates,
                        operation = "updates.install",
                        outcome = when (result) {
                            AppUpdateInstallResult.ConfirmationOpened -> "confirmation-opened"
                            AppUpdateInstallResult.Installed -> "installed"
                            is AppUpdateInstallResult.Cancelled -> "cancelled"
                            is AppUpdateInstallResult.PermissionRequired -> "permission-required"
                            is AppUpdateInstallResult.Rejected -> "rejected"
                        },
                        durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                        message = when (result) {
                            is AppUpdateInstallResult.PermissionRequired -> result.message
                            is AppUpdateInstallResult.Rejected -> result.message
                            else -> null
                        },
                        fields = buildList {
                            add(SupportDiagnosticFieldDraft("release", release.versionName))
                            if (result is AppUpdateInstallResult.Rejected) {
                                add(SupportDiagnosticFieldDraft("reason", result.diagnosticCode))
                            }
                        },
                    ),
                )
                result
            } catch (failure: Throwable) {
                supportDiagnostics.record(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Error,
                        component = SupportDiagnosticComponent.Updates,
                        operation = "updates.install",
                        outcome = "failed",
                        durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                        fields = listOf(SupportDiagnosticFieldDraft("release", release.versionName)),
                        exception = failure.toSupportDiagnosticExceptionDraft(),
                    ),
                )
                throw failure
            }
        }

    override fun cancelAppUpdate(): Boolean = appUpdater.cancelUpdate()

    override suspend fun loadSupportDiagnosticsSummary(): SupportDiagnosticsSummary =
        supportDiagnostics.loadSummary()

    override fun supportDiagnosticsRevisions() = supportDiagnostics.revisions()

    override suspend fun exportSupportDiagnostics(
        reproductionSteps: String,
    ): SupportDiagnosticsExportResult = supportBundleExporter.export(
        reproductionSteps = reproductionSteps,
        featureState = supportDiagnosticFeatureState(),
    )

    override fun supportDiagnosticsSubmissionStates() = supportIntake.states()

    override suspend fun submitSupportDiagnostics(reproductionSteps: String) = supportIntake.submit(
        reproductionSteps = reproductionSteps,
        channel = appUpdateSupport().channel.name.lowercase(),
        featureState = supportDiagnosticFeatureState(),
    )

    override suspend fun retrySupportDiagnosticsSubmission() = supportIntake.retry()

    override suspend fun cancelSupportDiagnosticsSubmission(): Boolean = supportIntake.cancel()

    override suspend fun deleteSubmittedSupportDiagnosticsReport(
        recordId: String,
    ): SupportDiagnosticsDeletionResult = supportIntake.deleteCompletedReport(recordId)

    override suspend fun refreshSubmittedSupportDiagnosticsReports(): SupportDiagnosticsConversationResult =
        supportIntake.refreshCompletedReports()

    override suspend fun sendSubmittedSupportDiagnosticsMessage(
        recordId: String,
        message: String,
    ): SupportDiagnosticsConversationResult = supportIntake.sendCompletedReportMessage(recordId, message)

    override suspend fun markSubmittedSupportDiagnosticsReportRead(recordId: String): Boolean =
        supportIntake.markCompletedReportRead(recordId)

    private fun supportDiagnosticFeatureState(): List<SupportDiagnosticFieldDraft> =
        listOf(
            SupportDiagnosticFieldDraft("distribution", appUpdateSupport().channel.name.lowercase()),
            SupportDiagnosticFieldDraft("direct_updates", appUpdateSupport().canCheckDirectUpdates.toString()),
            SupportDiagnosticFieldDraft("start_on_login_supported", supportsStartOnLogin.toString()),
            SupportDiagnosticFieldDraft("virtual_files_supported", supportsVirtualFileStorage.toString()),
            SupportDiagnosticFieldDraft(
                "virtual_files_active",
                (windowsCloudFilesProvider != null || linuxVirtualFileSystem != null).toString(),
            ),
            SupportDiagnosticFieldDraft("bidirectional_sync", supportsBidirectionalFileSync.toString()),
        )

    override suspend fun clearSupportDiagnostics(): Boolean = withContext(Dispatchers.IO) {
        supportDiagnostics.clear()
    }

    override fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) {
        supportDiagnostics.record(event)
    }

    override fun registerSupportDiagnosticPrivateValue(value: String?) {
        supportDiagnostics.registerPrivateValue(value)
    }

    override fun loadLastOpenedAppId(): String = preferences.get(KEY_LAST_OPENED_APP, "files")

    override fun saveLastOpenedAppId(appId: String) {
        preferences.put(KEY_LAST_OPENED_APP, appId)
    }

    override suspend fun loadDurableMutationRecovery(
        accountScope: String,
        kind: DurableMutationRecoveryKind,
    ): String? = withContext(Dispatchers.IO) { durableMutationRecovery.load(accountScope, kind) }

    override suspend fun saveDurableMutationRecovery(
        accountScope: String,
        kind: DurableMutationRecoveryKind,
        encoded: String,
    ): Boolean = withContext(Dispatchers.IO) { durableMutationRecovery.save(accountScope, kind, encoded) }

    override suspend fun clearDurableMutationRecovery(
        accountScope: String,
        kind: DurableMutationRecoveryKind,
        expectedEncoded: String,
    ): Boolean = withContext(Dispatchers.IO) {
        durableMutationRecovery.clear(accountScope, kind, expectedEncoded)
    }

    override suspend fun loadCachedDynamicAppDiscovery(
        session: NextcloudSession,
        appId: String,
    ): DynamicDescriptorDiscovery? = withContext(Dispatchers.IO) {
        val target = dynamicDiscoveryCacheFile(session, appId) ?: return@withContext null
        if (!target.isFile || target.length() !in 1..MAX_PERSISTED_DYNAMIC_DISCOVERY_BYTES.toLong()) {
            return@withContext null
        }
        runCatching { target.readText() }
            .getOrNull()
            ?.let { encoded -> decodePersistedDynamicDiscovery(encoded, appId, session.serverUrl) }
    }

    override suspend fun saveCachedDynamicAppDiscovery(
        session: NextcloudSession,
        discovery: DynamicDescriptorDiscovery,
    ) = withContext(Dispatchers.IO) {
        val encoded = encodePersistedDynamicDiscovery(discovery) ?: return@withContext
        val target = dynamicDiscoveryCacheFile(session, discovery.descriptor.app.id) ?: return@withContext
        check(dynamicDiscoveryCacheDirectory.mkdirs() || dynamicDiscoveryCacheDirectory.isDirectory) {
            "Could not create the dynamic contract cache."
        }
        val temporary = File(dynamicDiscoveryCacheDirectory, "${target.name}.part")
        temporary.outputStream().buffered().use { output ->
            output.write(encoded.encodeToByteArray())
            output.flush()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Unit
    }

    private fun dynamicDiscoveryCacheFile(session: NextcloudSession, appId: String): File? {
        if (!appId.isSafeDynamicDiscoveryCacheAppId()) return null
        return File(dynamicDiscoveryCacheDirectory, "${desktopFileCacheAccountId(session)}-$appId.json")
    }

    override suspend fun loadPendingDynamicMutation(
        session: NextcloudSession,
        appId: String,
        actionId: String,
        targetRecordId: String,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val target = pendingDynamicMutationFile(session, appId, actionId, targetRecordId)
            ?: return@withContext null
        if (!target.exists()) return@withContext null
        if (pendingDynamicMutationDirectory.isDirectory) {
            ensurePrivatePendingMutationDirectory(pendingDynamicMutationDirectory)
        }
        check(
            Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                target.length() in 1..MAX_PERSISTED_DYNAMIC_MUTATION_BYTES.toLong(),
        ) {
            "The pending mutation marker is unreadable."
        }
        setPrivatePendingMutationFilePermissions(target)
        val encoded = runCatching { target.readText() }.getOrElse { failure ->
            throw IllegalStateException("The pending mutation marker could not be read.", failure)
        }
        requireNotNull(decodePersistedDynamicMutation(encoded, appId, actionId, targetRecordId)) {
            "The pending mutation marker is invalid."
        }
    }

    override suspend fun savePendingDynamicMutation(
        session: NextcloudSession,
        appId: String,
        actionId: String,
        targetRecordId: String,
        values: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val encoded = requireNotNull(
            encodePersistedDynamicMutation(appId, actionId, targetRecordId, values),
        ) { "The pending dynamic mutation is invalid." }
        val target = requireNotNull(pendingDynamicMutationFile(session, appId, actionId, targetRecordId)) {
            "The pending dynamic mutation identity is invalid."
        }
        writePrivatePendingMutationFile(
            directory = pendingDynamicMutationDirectory,
            target = target,
            bytes = encoded.encodeToByteArray(),
        )
        Unit
    }

    override suspend fun clearPendingDynamicMutation(
        session: NextcloudSession,
        appId: String,
        actionId: String,
        targetRecordId: String,
    ) = withContext(Dispatchers.IO) {
        pendingDynamicMutationFile(session, appId, actionId, targetRecordId)?.let { target ->
            check(!target.exists() || target.delete()) { "Could not clear the pending mutation." }
        }
        Unit
    }

    private fun pendingDynamicMutationFile(
        session: NextcloudSession,
        appId: String,
        actionId: String,
        targetRecordId: String,
    ): File? {
        if (!appId.isSafePendingMutationId() || !actionId.isSafePendingMutationId()) return null
        if (!targetRecordId.isSafePendingMutationId()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$actionId\n$targetRecordId".encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(
            pendingDynamicMutationDirectory,
            "${desktopFileCacheAccountId(session)}-$appId-$digest.json",
        )
    }

    override fun loadSession(): NextcloudSession? {
        return sessionPublicationGuard.serialize {
            val server = preferences.get(KEY_SERVER, null)
            val login = preferences.get(KEY_LOGIN, null)
            if (server == null || login == null) {
                supportDiagnostics.setActiveAccountIdentity(null)
                supportIntake.setActiveAccountIdentity(null)
                return@serialize null
            }
            val password = secretStore.load(desktopSessionSecretReference(server, login))
                ?.decodeToString()
                ?.takeIf(String::isNotBlank)
            if (password == null) {
                supportDiagnostics.setActiveAccountIdentity(null)
                supportIntake.setActiveAccountIdentity(null)
                return@serialize null
            }
            listOf(server, login, password).forEach(supportDiagnostics::registerPrivateValue)
            NextcloudSession(server, login, password).also { session ->
                val accountIdentity = desktopFileCacheAccountId(session)
                supportDiagnostics.setActiveAccountIdentity(accountIdentity)
                supportIntake.setActiveAccountIdentity(accountIdentity)
            }
        }
    }

    override suspend fun saveSession(session: NextcloudSession) = withContext(Dispatchers.IO) {
        sessionPublicationGuard.serialize {
            listOf(session.serverUrl, session.loginName, session.appPassword)
                .forEach(supportDiagnostics::registerPrivateValue)
            try {
                secretStore.save(
                    reference = desktopSessionSecretReference(session.serverUrl, session.loginName),
                    username = session.loginName,
                    secret = session.appPassword.encodeToByteArray(),
                )
            } catch (failure: Throwable) {
                recordSupportDiagnostic(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Error,
                        component = SupportDiagnosticComponent.Authentication,
                        operation = "credentials.save",
                        outcome = "failed",
                        code = if (failure is DesktopSecretStoreUnavailableException) {
                            "DESKTOP_SECRET_STORE_UNAVAILABLE"
                        } else {
                            "DESKTOP_SECRET_STORE_FAILED"
                        },
                        exception = failure.toSupportDiagnosticExceptionDraft(),
                    ),
                )
                throw failure
            }
            preferences.put(KEY_SERVER, session.serverUrl)
            preferences.put(KEY_LOGIN, session.loginName)
            val accountIdentity = desktopFileCacheAccountId(session)
            supportDiagnostics.setActiveAccountIdentity(accountIdentity)
            supportIntake.setActiveAccountIdentity(accountIdentity)
        }
        synchronized(fileRangeSessionLock) { sessionClearing = false }
        startDesktopSyncLifecycle()
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        val userHome = File(System.getProperty("user.home"))
        val rangeSessions = synchronized(fileRangeSessionLock) {
            sessionClearing = true
            activeFileRangeSessions.toList()
        }
        var cleared = false
        try {
            val accountId = loadSession()?.let(::desktopFileCacheAccountId)
            val syncJob = synchronized(this) {
                val active = backgroundFileSyncJob
                backgroundFileSyncJob = null
                active
            }
            syncJob?.cancel()
            val hydrationJobs = accountId?.let(::cancelAllVirtualFolderHydration).orEmpty()
            rangeSessions.forEach { source -> runCatching(source::close) }
            hydrationJobs.forEach { job -> job.join() }
            accountId?.let { clearedAccountId ->
                val prefix = "$clearedAccountId\u0000"
                synchronized(virtualFolderMutationLock) {
                    virtualFolderMutationGenerationsByJob.keys.removeIf { key -> key.startsWith(prefix) }
                    virtualFolderCompletedGenerations.keys.removeIf { key -> key.startsWith(prefix) }
                    virtualFolderRetryAtEpochMillis.keys.removeIf { key -> key.startsWith(prefix) }
                }
            }
            syncJob?.join()
            synchronized(virtualFileProviderLock) {
                linuxVirtualFileSystem?.unmount()
                linuxVirtualFileSystem = null
                linuxVirtualMetadataBackend = null
                linuxVirtualFileMountIdentity = null
                linuxVirtualFileFailure = null
                val windowsCloudFilesFailureMessage = "Could not remove the Windows Cloud Files root."
                val provider = windowsCloudFilesProvider
                try {
                    if (provider != null) {
                        provider.removeSyncRoot()
                    } else if (isWindowsDesktop()) {
                        unregisterWindowsCloudFilesRootForUninstall(preferences)
                    }
                    windowsCloudFilesFailure = null
                } catch (failure: Throwable) {
                    windowsCloudFilesFailure = failure.message ?: windowsCloudFilesFailureMessage
                    supportDiagnostics.record(
                        SupportDiagnosticEventDraft(
                            severity = SupportDiagnosticSeverity.Error,
                            component = SupportDiagnosticComponent.VirtualFiles,
                            operation = "cloud-files.signout-cleanup",
                            outcome = "failed",
                            fields = accountId?.let {
                                listOf(
                                    SupportDiagnosticFieldDraft(
                                        "account",
                                        it,
                                        SupportDiagnosticValuePrivacy.Identifier,
                                    ),
                                )
                            }.orEmpty(),
                            exception = failure.toSupportDiagnosticExceptionDraft(),
                        ),
                    )
                } finally {
                    runCatching { provider?.close() }
                    windowsCloudFilesProvider = null
                    windowsCloudFilesIdentity = null
                    preferences.remove(KEY_WINDOWS_CLOUD_FILES_ROOT)
                    accountId?.let {
                        clearWindowsCloudFilesRootPreferences(
                            preferences,
                            it,
                            desktopWindowsCloudFilesRoot(it, userHome).toPath(),
                        )
                        clearWindowsCloudFilesRootPreferences(
                            preferences,
                            it,
                            desktopLegacyWindowsCloudFilesRoot(it, userHome).toPath(),
                        )
                    }
                    if (isWindowsDesktop()) {
                        val uninstallFailure = runCatching {
                            unregisterWindowsCloudFilesRootForUninstall(preferences, userHome = userHome)
                        }.exceptionOrNull()
                        if (uninstallFailure != null) {
                            windowsCloudFilesFailure = windowsCloudFilesFailure ?: (
                                uninstallFailure.message ?: windowsCloudFilesFailureMessage
                            )
                            supportDiagnostics.record(
                                SupportDiagnosticEventDraft(
                                    severity = SupportDiagnosticSeverity.Error,
                                    component = SupportDiagnosticComponent.VirtualFiles,
                                    operation = "cloud-files.signout-cleanup-retry",
                                    outcome = "failed",
                                    fields = accountId?.let {
                                        listOf(
                                            SupportDiagnosticFieldDraft(
                                                "account",
                                                it,
                                                SupportDiagnosticValuePrivacy.Identifier,
                                            ),
                                        )
                                    }.orEmpty(),
                                    exception = uninstallFailure.toSupportDiagnosticExceptionDraft(),
                                ),
                            )
                        }
                    }
                }
            }
            mutableFileSyncTraySnapshot.value = DesktopFileSyncTraySnapshot(
                phase = DesktopFileSyncTrayPhase.Idle,
            )
            val server = preferences.get(KEY_SERVER, null)
            val login = preferences.get(KEY_LOGIN, null)
            runCatching {
                if (server != null && login != null) {
                    secretStore.clear(desktopSessionSecretReference(server, login))
                }
            }.onFailure { failure ->
                supportDiagnostics.record(
                    SupportDiagnosticEventDraft(
                        severity = SupportDiagnosticSeverity.Error,
                        component = SupportDiagnosticComponent.Authentication,
                        operation = "credentials.clear",
                        outcome = "failed",
                        exception = failure.toSupportDiagnosticExceptionDraft(),
                    ),
                )
            }
            sessionPublicationGuard.serialize {
                preferences.remove(KEY_SERVER)
                preferences.remove(KEY_LOGIN)
                supportDiagnostics.setActiveAccountIdentity(null)
                supportIntake.setActiveAccountIdentity(null)
            }
            cleared = true
        } finally {
            if (!cleared) {
                synchronized(fileRangeSessionLock) { sessionClearing = false }
                if (loadSession() != null) startDesktopSyncLifecycle()
            }
        }
    }

    override suspend fun loadDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ): PersistedDeckCardDraft? = withContext(Dispatchers.IO) {
        deckCardDrafts.load(session, key)
    }

    override suspend fun saveDeckCardDraft(
        session: NextcloudSession,
        draft: PersistedDeckCardDraft,
    ) = withContext(Dispatchers.IO) {
        deckCardDrafts.save(session, draft)
    }

    override suspend fun clearDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ) = withContext(Dispatchers.IO) {
        deckCardDrafts.clear(session, key)
    }

    override fun openExternalUrl(url: String) {
        serviceScope.launch {
            runCatching { openExternalUrlNow(url) }
        }
    }

    override suspend fun openLoginUrl(url: String) = withContext(Dispatchers.IO) {
        openExternalUrlNow(url)
    }

    private fun openExternalUrlNow(url: String) {
        try {
            externalUrlLauncher.open(url)
        } catch (failure: DesktopExternalUrlLaunchException) {
            runCatching {
                recordSupportDiagnostic(desktopExternalUrlFailureDiagnostic(failure))
            }
            throw failure
        }
    }

    override suspend fun handoffFileToExternalApp(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult {
        val capability = (externalFileHandoffSupport as ExternalFileHandoffSupport.Available).capability
        return externalFileHandoff.launch(file, action, capability) { maximumBytes ->
            downloadFile(session, userId, file.path, maximumBytes)
        }
    }

    override suspend fun handoffDeckAttachmentToExternalApp(
        session: NextcloudSession,
        target: DeckAttachmentOpenTarget,
        attachment: DeckAttachment,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult {
        require(target.method == NextcloudApiMethod.GET) {
            "Deck attachments can only be opened with a read request."
        }
        val requestSpec = NextcloudApiRequest(
            method = target.method,
            relativePath = target.relativePath,
            ocsApiRequest = true,
        ).requireSafe()
        val capability = (externalFileHandoffSupport as ExternalFileHandoffSupport.Available).capability
        return externalFileHandoff.launchDetached(attachment, action, capability) { output, maximumBytes ->
            withContext(Dispatchers.IO) {
                val authorization = Base64.getEncoder().encodeToString(
                    "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
                )
                val started = System.nanoTime()
                val networkAttempt = JvmNetworkRequestAttempt()
                val request = Request.Builder()
                    .url(buildNextcloudApiUrl(session.serverUrl, requestSpec))
                    .get()
                    .tag(JvmNetworkRequestAttempt::class.java, networkAttempt)
                    .header("Accept", "*/*")
                    .header("OCS-APIRequest", "true")
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Basic $authorization")
                    .build()
                val response = try {
                    noRedirectHttpClient.newCall(request).execute()
                } catch (failure: Throwable) {
                    recordDesktopStreamingFailure(
                        session = session,
                        streamKind = "deck_attachment",
                        startedNanos = started,
                        attempt = networkAttempt,
                        failure = failure,
                    )
                    throw failure
                }
                response.use {
                    check(response.isSuccessful) {
                        "Opening the Deck attachment failed (HTTP ${response.code})."
                    }
                    val responseBody = response.body
                    val contentLength = responseBody.contentLength()
                    check(contentLength <= maximumBytes || contentLength == -1L) {
                        "The Deck attachment is larger than the external handoff limit."
                    }
                    DesktopDetachedDownload(
                        responseBody.byteStream().copyBoundedNetworkResponseTo(
                            output = output,
                            maxBytes = maximumBytes,
                            onLimitExceeded = {
                                error("The Deck attachment is larger than the external handoff limit.")
                            },
                            onNetworkReadFailure = { failure ->
                                recordDesktopStreamingFailure(
                                    session = session,
                                    streamKind = "deck_attachment",
                                    startedNanos = started,
                                    attempt = networkAttempt,
                                    failure = failure,
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    override fun copyTextToClipboard(label: String, text: String): Boolean = runCatching {
        require(text.isNotBlank() && text.length <= 8_192 && text.none(Char::isISOControl)) {
            "Clipboard text is invalid."
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)

    override suspend fun beginLogin(
        serverUrl: String,
        transportSecurity: LoginTransportSecurity,
    ): LoginChallenge = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl, transportSecurity)
        val effectiveTransport = loginTransportSecurity(baseUrl)
        val response = request(
            "POST",
            "$baseUrl/index.php/login/v2",
            maxResponseBytes = LOGIN_FLOW_RESPONSE_MAX_BYTES,
        )
        val interpretation = interpretLoginChallengeHttpResponse(
            status = response.status,
            body = response.text,
            enteredServerUrl = baseUrl,
            transportSecurity = effectiveTransport,
        )
        recordSupportDiagnostic(interpretation.toStartedDiagnostic())
        interpretation.challenge
    }

    override suspend fun pollLogin(challenge: LoginChallenge): LoginPollResult = withContext(Dispatchers.IO) {
        var networkFailure: JvmNetworkFailureDiagnostic? = null
        fun poll(endpoint: String): HttpResponse {
            networkFailure = null
            return request(
                "POST",
                endpoint,
                body = "token=" + encodeForm(challenge.token),
                contentType = "application/x-www-form-urlencoded",
                client = loginPollHttpClient,
                maxResponseBytes = LOGIN_FLOW_RESPONSE_MAX_BYTES,
                diagnosticIgnoredHttpStatuses = setOf(404),
                onNetworkFailure = { networkFailure = it },
            )
        }
        var usedFallback = challenge.token in loginPollFallbackTokens
        val initialEndpoint = if (usedFallback) {
            requireNotNull(challenge.pollFallbackEndpoint)
        } else {
            challenge.pollEndpoint
        }
        val response = try {
            poll(initialEndpoint)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val initialResult = classifyLoginPollNetworkFailure(networkFailure)
            val fallback = challenge.pollFallbackEndpoint
            if (
                initialResult is LoginPollResult.RetryablePreExchangeFailure &&
                !usedFallback &&
                fallback != null
            ) {
                runCatching {
                    recordSupportDiagnostic(loginPollEndpointFallbackDiagnostic())
                }
                try {
                    poll(fallback).also {
                        usedFallback = true
                        loginPollFallbackTokens += challenge.token
                    }
                } catch (fallbackFailure: Throwable) {
                    if (fallbackFailure is CancellationException) throw fallbackFailure
                    val result = classifyLoginPollNetworkFailure(networkFailure)
                    result.toLoginPollFailureDiagnostic()?.let(::recordSupportDiagnostic)
                    return@withContext result
                }
            } else {
                initialResult.toLoginPollFailureDiagnostic()?.let(::recordSupportDiagnostic)
                return@withContext initialResult
            }
        }
        val interpretation = interpretLoginPollHttpResponse(response.status, response.text, challenge)
        val result = interpretation.result
        when (result) {
            LoginPollResult.Pending -> {
                if (loginPollPendingTokens.add(challenge.token)) {
                    recordSupportDiagnostic(loginPollPendingDiagnostic(usedFallback))
                }
            }
            is LoginPollResult.Approved -> {
                registerSupportDiagnosticPrivateValue(requireNotNull(interpretation.approvedLoginName))
                registerSupportDiagnosticPrivateValue(requireNotNull(interpretation.approvedAppPassword))
                runCatching { recordSupportDiagnostic(interpretation.toApprovedDiagnostic(usedFallback)) }
            }
            else -> result.toLoginPollFailureDiagnostic()?.let(::recordSupportDiagnostic)
        }
        result
    }

    override fun finishLoginPolling(challenge: LoginChallenge) {
        loginPollFallbackTokens -= challenge.token
        loginPollPendingTokens -= challenge.token
    }

    override suspend fun loadServerInfo(session: NextcloudSession): NextcloudServerInfo =
        withContext(Dispatchers.IO) {
            val user = ocsGet(session, "/ocs/v2.php/cloud/user").getJSONObject("ocs").getJSONObject("data")
            val data = ocsGet(session, "/ocs/v1.php/cloud/capabilities")
                .getJSONObject("ocs").getJSONObject("data")
            val capabilities = data.getJSONObject("capabilities")
            val theming = capabilities.optJSONObject("theming")
            val navigation = runCatching {
                ocsGet(session, "/ocs/v2.php/core/navigation/apps")
                    .getJSONObject("ocs").getJSONArray("data")
            }.getOrNull()
            NextcloudServerInfo(
                session.serverUrl,
                user.optString("display-name").ifBlank { session.loginName },
                user.optString("id").ifBlank { session.loginName },
                data.optJSONObject("version")?.optString("string")?.takeIf(String::isNotBlank),
                theming?.optString("name")?.takeIf(String::isNotBlank),
                theming?.optString("color")?.takeIf(String::isNotBlank),
                navigation?.toAppEntries() ?: capabilities.toCapabilityEntries(),
                navigation != null,
                discoverRecognizeBridge(capabilities.toString()),
                parseNextcloudFileSharingCapabilities(capabilities.toString()),
            )
        }

    override suspend fun listFiles(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): List<NextcloudFile> = listFilesWithSource(session, userId, path).files

    override suspend fun listFilesWithSource(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): NextcloudFileListing = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        val requestStartedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
        try {
            val response = request(
                "PROPFIND", buildNextcloudFileUrl(session.serverUrl, userId, path), session, DAV_PROPERTIES,
                "application/xml; charset=utf-8", headers = mapOf("Depth" to "1", "Accept" to "application/xml"),
            )
            if (response.status == 207) {
                val files = parseDavFiles(response.body, userId).drop(1)
                    .sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                runCatching {
                    fileReadCache.storeListingUnlessNewer(
                        accountId = accountId,
                        path = path,
                        files = files,
                        fetchedAtEpochMillis = requestStartedAtEpochMillis,
                    )
                }
                NextcloudFileListing(files, NextcloudFileListingSource.Network)
            } else {
                if (response.status >= 500) {
                    fileReadCache.cachedListing(accountId, path)?.let {
                        return@withContext NextcloudFileListing(it, NextcloudFileListingSource.Cache)
                    }
                }
                throw NextcloudFileListingHttpException(response.status)
            }
        } catch (failure: IOException) {
            fileReadCache.cachedListing(accountId, path)
                ?.let { NextcloudFileListing(it, NextcloudFileListingSource.Cache) }
                ?: throw failure
        }
    }

    override suspend fun listFilesCachedWithSource(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): NextcloudFileListing? = withContext(Dispatchers.IO) {
        fileReadCache.cachedListing(desktopFileCacheAccountId(session), path)?.let {
            NextcloudFileListing(it, NextcloudFileListingSource.Cache)
        }
    }

    override suspend fun searchFiles(
        session: NextcloudSession,
        userId: String,
        query: String,
        scopePath: String,
        maximumResults: Int,
    ): List<NextcloudFile> = withContext(Dispatchers.IO) {
        val body = buildFileSearchDavRequest(userId, scopePath, query, maximumResults)
        val response = request(
            method = "SEARCH",
            url = session.serverUrl.trimEnd('/') + "/remote.php/dav/",
            session = session,
            body = body,
            contentType = "application/xml; charset=utf-8",
            headers = mapOf("Accept" to "application/xml"),
        )
        if (response.status != 207) throw NextcloudFileSearchHttpException(response.status)
        parseDavFiles(response.body, userId)
            .distinctBy(NextcloudFile::path)
            .take(maximumResults)
    }

    override suspend fun listFavoriteFiles(
        session: NextcloudSession,
        userId: String,
        scopePath: String,
    ): List<NextcloudFile> = withContext(Dispatchers.IO) {
        val response = request(
            method = "REPORT",
            url = buildNextcloudFileUrl(session.serverUrl, userId, scopePath),
            session = session,
            body = buildFavoriteFilesDavReport(),
            contentType = "application/xml; charset=utf-8",
            headers = mapOf("Accept" to "application/xml"),
        )
        if (response.status != 207) throw NextcloudFileListingHttpException(response.status)
        parseDavFiles(response.body, userId).distinctBy(NextcloudFile::path)
    }

    override suspend fun setFileFavorite(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        favorite: Boolean,
    ) = withContext(Dispatchers.IO) {
        val safePath = requireSafeFilePath(file.path, allowRoot = false)
        val expectedEtag = file.etag?.trim().orEmpty()
        require(expectedEtag.isNotEmpty()) { "Refresh the folder before changing favorites." }
        val headers = buildMap {
            put("Accept", "application/xml")
            putAll(
                FileWebDavMutationSpec(
                    method = "PROPPATCH",
                    sourcePath = safePath,
                    destinationPath = null,
                    expectedEtag = expectedEtag,
                    sourceIsDirectory = file.isDirectory,
                    overwrite = false,
                ).conflictConditionHeaders(),
            )
        }
        val accountId = desktopFileCacheAccountId(session)
        fun refreshMetadata() {
            runCatching { refreshRetainedFoldersAfterMutation(session, userId, accountId, safePath) }
        }
        val response = request(
            method = "PROPPATCH",
            url = buildNextcloudFileUrl(session.serverUrl, userId, safePath),
            session = session,
            body = buildFileFavoritePropPatch(favorite),
            contentType = "application/xml; charset=utf-8",
            headers = headers,
            maxResponseBytes = 64 * 1024,
            mutationExecutor = fileMutationHttpExecutor,
            onAmbiguousMutationResult = ::refreshMetadata,
        )
        if (response.status !in 200..299) throw fileOperationException(response.status)
        check(response.status == 200 || response.status == 207 && fileFavoriteUpdateSucceeded(response.body)) {
            "The server did not confirm the favorite change."
        }
        refreshMetadata()
    }

    override suspend fun listMedia(session: NextcloudSession, userId: String): List<NextcloudFile> =
        withContext(Dispatchers.IO) {
            val pages = collectMediaSearchDavPages(
                requests = mediaSearchDavRequests(userId),
                execute = { body ->
                    val response = request(
                        "SEARCH", session.serverUrl + "/remote.php/dav/", session, body,
                        "application/xml; charset=utf-8", headers = mapOf("Accept" to "application/xml"),
                    )
                    MediaSearchDavTransportResponse(response.status, response.body)
                },
                parse = { body -> parseDavFiles(body, userId) },
                shouldSearchRaw = { files -> files.any(NextcloudFile::isRawPhoto) },
                rawCompatibilityPolicy = RawMediaSearchCompatibilityPolicy.KeepAvailableResults,
            )
            mergeMediaSearchResultPages(pages)
        }

    override suspend fun listMediaTimelinePage(
        session: NextcloudSession,
        userId: String,
        cursor: PhotoTimelineCursor?,
        rawPreviouslyObserved: Boolean,
        queryOwner: PhotoMediaQueryOwner,
    ): PhotoTimelinePage = withContext(Dispatchers.IO) {
        suspend fun loadDavPage(davCursor: PhotoTimelineCursor?): PhotoTimelinePage {
            val page = collectMediaTimelineDavPage(
                userId = userId,
                cursor = davCursor,
                execute = { body ->
                    val response = request(
                        "SEARCH", session.serverUrl + "/remote.php/dav/", session, body,
                        "application/xml; charset=utf-8", headers = mapOf("Accept" to "application/xml"),
                    )
                    MediaSearchDavTransportResponse(response.status, response.body)
                },
                parse = { body -> parseDavFiles(body, userId) },
                shouldSearchRaw = { files ->
                    rawPreviouslyObserved || files.any(NextcloudFile::isRawPhoto)
                },
                carryoverStore = mediaTimelineCarryoverStore,
                carryoverAccountScope = photoMediaCarryoverScope(
                    accountScope = desktopFileCacheAccountId(session),
                    owner = queryOwner,
                ),
            )
            return PhotoTimelinePage(
                entries = page.files.mapNotNull(NextcloudFile::toPhotoTimelineEntryOrNull),
                nextCursor = page.nextCursor,
                optionalRawRemovalAuthoritative = page.optionalRawRemovalAuthoritative,
                rawObserved = page.rawObserved,
                optionalRawSearchRetryPending = page.optionalRawSearchRetryPending,
            )
        }

        if (queryOwner == PhotoMediaQueryOwner.Timeline) {
            memoriesTimeline.loadPage(
                session = session,
                accountScope = desktopFileCacheAccountId(session),
                cursor = cursor,
                fallback = ::loadDavPage,
            )
        } else {
            loadDavPage(cursor)
        }
    }

    override suspend fun loadMediaTimelineNavigationSnapshot(
        session: NextcloudSession,
        monthResolver: PhotoTimelineMonthResolver,
    ): MemoriesTimelineNavigationSnapshot? = withContext(Dispatchers.IO) {
        memoriesTimeline.navigationSnapshot(
            accountScope = desktopFileCacheAccountId(session),
            monthResolver = monthResolver,
        )
    }

    override suspend fun loadMediaTimelineNavigationTarget(
        session: NextcloudSession,
        sourceGeneration: Long,
        targetDayId: Long,
    ): MemoriesTimelineNavigationLoadResult = withContext(Dispatchers.IO) {
        memoriesTimeline.loadNavigationTarget(
            session = session,
            accountScope = desktopFileCacheAccountId(session),
            sourceGeneration = sourceGeneration,
            targetDayId = targetDayId,
        )
    }

    override suspend fun listSystemTags(session: NextcloudSession): List<NextcloudSystemTag> =
        withContext(Dispatchers.IO) {
            val discovery = systemTagsDavDiscoveryRequest()
            val response = request(
                discovery.method,
                session.serverUrl + discovery.relativePath,
                session,
                discovery.body.decodeToString(),
                discovery.contentType,
                headers = mapOf("Depth" to discovery.depth.toString(), "Accept" to "application/xml"),
            )
            check(response.status == 207) { "System tag discovery failed (HTTP ${response.status})." }
            parseDesktopSystemTagsDavResponse(response.body)
        }

    override suspend fun resolveFilesById(
        session: NextcloudSession,
        userId: String,
        fileIds: Collection<Long>,
    ): Map<Long, NextcloudFile> = withContext(Dispatchers.IO) {
        fileIds.distinct().chunked(MAX_FILE_IDENTITY_SEARCH_BATCH)
            .flatMap { batch ->
                val search = filesByIdDavSearchRequest(userId, batch)
                val response = request(
                    search.method,
                    session.serverUrl + search.relativePath,
                    session,
                    search.body.decodeToString(),
                    search.contentType,
                    headers = mapOf("Accept" to "application/xml"),
                )
                check(response.status == 207) {
                    "WebDAV file identity lookup failed (HTTP ${response.status})."
                }
                parseDavFiles(response.body, userId)
            }
            .mapNotNull { file -> file.fileId?.let { it to file } }
            .toMap()
    }

    override suspend fun loadPreview(
        session: NextcloudSession,
        fileId: Long,
        width: Int,
        height: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val safeWidth = boundedPreviewDimension(width)
            val safeHeight = boundedPreviewDimension(height)
            val response = request(
                "GET",
                session.serverUrl +
                    "/index.php/core/preview?fileId=$fileId&x=$safeWidth&y=$safeHeight&a=1&mode=cover" +
                    "&forceIcon=0&mimeFallback=0",
                session,
                headers = mapOf("Accept" to "image/*"),
            )
            check(response.status in 200..299) { "Preview failed (HTTP ${response.status})." }
            response.body
        }

    override suspend fun downloadFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        maxBytes: Long,
    ): NextcloudFileContent = withContext(Dispatchers.IO) {
        require(maxBytes > 0) { "The download size limit must be greater than zero." }
        val accountId = desktopFileCacheAccountId(session)
        val cached = fileReadCache.cachedContent(accountId, path, maxBytes)
        try {
            var response = request(
                "GET",
                buildNextcloudFileUrl(session.serverUrl, userId, path),
                session,
                headers = buildMap {
                    put("Accept", "*/*")
                    cached?.etag?.let { put("If-None-Match", it) }
                },
                maxResponseBytes = maxBytes,
            )
            if (response.status == 304 && cached == null) {
                response = request(
                    "GET",
                    buildNextcloudFileUrl(session.serverUrl, userId, path),
                    session,
                    headers = mapOf("Accept" to "*/*"),
                    maxResponseBytes = maxBytes,
                )
            }
            when {
                response.status == 304 && cached != null ->
                    NextcloudFileContent(cached.bytes, cached.mimeType, cached.etag)
                response.status == 404 -> {
                    runCatching { refreshRetainedFoldersAfterMutation(session, userId, accountId, path) }
                    error("The file no longer exists on the server.")
                }
                response.status >= 500 && cached != null ->
                    NextcloudFileContent(cached.bytes, cached.mimeType, cached.etag)
                response.status !in 200..299 ->
                    error("Downloading the file failed (HTTP ${response.status}).")
                else -> NextcloudFileContent(response.body, response.contentType, response.etag).also { content ->
                    runCatching { fileReadCache.storeContent(accountId, path, content) }
                }
            }
        } catch (failure: IOException) {
            cached?.let { NextcloudFileContent(it.bytes, it.mimeType, it.etag) } ?: throw failure
        }
    }

    override suspend fun downloadFileRange(
        session: NextcloudSession,
        userId: String,
        path: String,
        offset: Long,
        length: Int,
        expectedEtag: String,
    ): ByteArray = openFileRangeSession(
        session,
        userId,
        path,
        Math.addExact(offset, length.toLong()),
        expectedEtag,
    ).use { source ->
        source.read(offset, length)
    }

    override fun openFileRangeSession(
        session: NextcloudSession,
        userId: String,
        path: String,
        size: Long,
        expectedEtag: String,
    ): NextcloudFileRangeSession {
        require(size > 0L)
        synchronized(fileRangeSessionLock) {
            check(!sessionClearing) { "The account session is closing." }
        }
        val safeEtag = requireSafeFileRangeEtag(expectedEtag)
        val closed = AtomicBoolean(false)
        val activeCall = AtomicReference<Call?>(null)
        lateinit var rangeSession: NextcloudFileRangeSession
        rangeSession = NextcloudFileRangeSession(
            size = size,
            readBlock = { offset, length ->
                withContext(Dispatchers.IO) {
                    check(!closed.get()) { "The file range session is closed." }
                    require(offset >= 0L) { "The file range offset must not be negative." }
                    require(length > 0) { "The file range length must be greater than zero." }
                    require(offset <= size && length.toLong() <= size - offset) {
                        "The requested file range exceeds the file size."
                    }
                    val endInclusive = Math.addExact(offset, length.toLong() - 1L)
                    val builder = Request.Builder()
                        .url(buildNextcloudFileUrl(session.serverUrl, userId, path))
                        .get()
                        .header("Accept", "application/octet-stream")
                        .header("Range", "bytes=$offset-$endInclusive")
                        .header("If-Match", safeEtag)
                        .header("User-Agent", USER_AGENT)
                    val started = System.nanoTime()
                    val networkAttempt = JvmNetworkRequestAttempt()
                    builder.tag(JvmNetworkRequestAttempt::class.java, networkAttempt)
                    val encoded = Base64.getEncoder()
                        .encodeToString("${session.loginName}:${session.appPassword}".toByteArray())
                    builder.header("Authorization", "Basic $encoded")
                    val call = noRedirectHttpClient.newCall(builder.build())
                    check(activeCall.compareAndSet(null, call)) { "Only one file range read can run at a time." }
                    if (closed.get()) call.cancel()
                    try {
                        call.execute().use { response ->
                            check(response.code == 206) {
                                "The server did not honor the bounded file range request (HTTP ${response.code})."
                            }
                            check(isExactHttpByteContentRange(response.header("Content-Range"), offset, endInclusive)) {
                                "The server returned a different file range than requested."
                            }
                            val responseBody = response.body
                            val contentLength = responseBody.contentLength()
                            if (contentLength in 0 until length.toLong()) {
                                throw JvmNetworkResponseTruncatedIOException()
                            }
                            check(contentLength == -1L || contentLength == length.toLong()) {
                                "The server returned an incomplete file range."
                            }
                            responseBody.byteStream().readBounded(length.toLong())
                                .requireExactJvmNetworkResponseBytes(length)
                        }
                    } catch (failure: Throwable) {
                        recordDesktopStreamingFailure(
                            session = session,
                            streamKind = "file_range",
                            startedNanos = started,
                            attempt = networkAttempt,
                            failure = failure,
                        )
                        throw failure
                    } finally {
                        activeCall.compareAndSet(call, null)
                    }
                }
            },
            closeBlock = {
                if (closed.compareAndSet(false, true)) {
                    activeCall.get()?.cancel()
                    synchronized(fileRangeSessionLock) {
                        activeFileRangeSessions.remove(rangeSession)
                    }
                }
            },
        )
        val registered = synchronized(fileRangeSessionLock) {
            if (sessionClearing) false else activeFileRangeSessions.add(rangeSession)
        }
        if (!registered) {
            rangeSession.close()
            error("The account session is closing.")
        }
        return rangeSession
    }

    override suspend fun downloadMemoriesFileRange(
        session: NextcloudSession,
        fileId: Long,
        offset: Long,
        length: Int,
        expectedEtag: String,
        expectedSourceSize: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(fileId > 0L) { "The Memories file ID must be positive." }
        require(offset >= 0L) { "The file range offset must not be negative." }
        require(length > 0) { "The file range length must be greater than zero." }
        require(expectedSourceSize > 0L) { "The source size must be positive." }
        val safeEtag = requireSafeFileRangeEtag(expectedEtag)
        val endInclusive = Math.addExact(offset, length.toLong() - 1L)
        val response = request(
            "GET",
            session.serverUrl.trimEnd('/') + "/index.php/apps/memories/api/stream/$fileId",
            session,
            headers = mapOf(
                "Accept" to "application/octet-stream",
                "Range" to "bytes=$offset-$endInclusive",
                "If-Match" to safeEtag,
            ),
            maxResponseBytes = length.toLong(),
            expectedSuccessResponseBytes = length,
            expectedSuccessResponseStatus = 206,
            client = noRedirectHttpClient,
        )
        check(response.status == 206) {
            "The Memories stream did not honor the bounded range request (HTTP ${response.status})."
        }
        check(
            isExactHttpByteContentRange(
                response.contentRange,
                offset,
                endInclusive,
                expectedSourceSize,
            ),
        ) {
            "The Memories stream returned a different file range than requested."
        }
        response.etag?.let { returnedEtag ->
            check(requireSafeFileRangeEtag(returnedEtag) == safeEtag) {
                "The Memories stream returned a different file generation."
            }
        }
        response.body
    }

    override suspend fun listFileVersions(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
    ): FileVersionHistory = withContext(Dispatchers.IO) {
        require(!file.isDirectory) { "Folders do not have file version history." }
        val fileId = requireNotNull(file.fileId) { "The file has no stable server identity." }
        require(fileId > 0L) { "The file has no stable server identity." }
        val specification = fileVersionHistoryRequest(userId, fileId)
        val response = request(
            method = specification.method,
            url = session.serverUrl + specification.relativePath,
            session = session,
            body = specification.body?.decodeToString(),
            contentType = specification.contentType,
            headers = mapOf(
                "Depth" to requireNotNull(specification.depth).toString(),
                "Accept" to "application/xml",
            ),
            maxResponseBytes = specification.maximumResponseBytes,
            client = noRedirectHttpClient,
        )
        check(response.status != 404) { "Version history is not available for this file." }
        check(response.status == 207) { "Loading file version history failed (HTTP ${response.status})." }
        normalizeFileVersionHistory(userId, fileId, parseDesktopFileVersionDavRecords(response.body))
    }

    override suspend fun downloadFileVersion(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
        maximumBytes: Long,
    ): NextcloudFileContent = withContext(Dispatchers.IO) {
        val fileId = requireMatchingFileVersion(file, version)
        val specification = boundedFileVersionContentRequest(
            userId,
            fileId,
            version.id,
            maximumBytes,
            version.sizeBytes,
        )
        val response = request(
            method = specification.method,
            url = session.serverUrl + specification.relativePath,
            session = session,
            headers = specification.headers + ("Accept" to "*/*"),
            maxResponseBytes = specification.maximumResponseBytes,
            client = noRedirectHttpClient,
        )
        check(response.status != 404) { "This historical version no longer exists." }
        check(response.status == 200 || response.status == 206) {
            "Downloading the historical version failed (HTTP ${response.status})."
        }
        NextcloudFileContent(response.body, response.contentType, response.etag ?: version.etag)
    }

    override suspend fun restoreFileVersion(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
    ): Unit = withContext(Dispatchers.IO) {
        val specification = fileVersionRestoreRequest(userId, file, version)
        val accountId = desktopFileCacheAccountId(session)
        fun queueAffectedMetadataRefresh() =
            refreshRetainedFoldersAfterMutation(session, userId, accountId, file.path)
        val response = request(
            method = specification.method,
            url = session.serverUrl + specification.relativePath,
            session = session,
            headers = specification.headers + mapOf(
                "Accept" to "*/*",
                "Destination" to session.serverUrl + specification.destinationRelativePath,
            ),
            maxResponseBytes = specification.maximumResponseBytes,
            client = noRedirectHttpClient,
            mutationExecutor = noRedirectFileMutationHttpExecutor,
            onAmbiguousMutationResult = ::queueAffectedMetadataRefresh,
        )
        when (val result = classifyFileVersionRestoreHttpResponse(response.status)) {
            FileVersionRestoreHttpResult.Restored -> {
                runCatching {
                    refreshRetainedFoldersAfterMutation(
                        session,
                        userId,
                        accountId,
                        file.path,
                    )
                }
            }
            is FileVersionRestoreHttpResult.Rejected -> error(result.message)
        }
    }

    override suspend fun saveTextFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        text: String,
        expectedEtag: String,
    ): SavedTextFile = withContext(Dispatchers.IO) {
        val specification = textFileDavSaveRequest(text, expectedEtag)
        val accountId = desktopFileCacheAccountId(session)
        fun queueAffectedMetadataRefresh() =
            refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
        val response = request(
            "PUT",
            buildNextcloudFileUrl(session.serverUrl, userId, path),
            session,
            rawBody = specification.body,
            contentType = specification.contentType,
            headers = specification.headers,
            mutationExecutor = fileMutationHttpExecutor,
            onAmbiguousMutationResult = ::queueAffectedMetadataRefresh,
        )
        val confirmation = confirmTextFileDavSave(response.status)
        val etag = response.etag ?:
            runCatchingPreservingCancellation { loadFileEtag(session, userId, path) }.getOrNull()
        runCatching {
            refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
            etag?.let {
                fileReadCache.storeContent(
                    accountId,
                    path,
                    NextcloudFileContent(specification.body, specification.contentType, it),
                )
            }
        }
        SavedTextFile(etag, confirmation.created)
    }

    override suspend fun createTextFileIfAbsent(
        session: NextcloudSession,
        userId: String,
        path: String,
        text: String,
    ): SavedTextFile = withContext(Dispatchers.IO) {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size.toLong() <= MAX_EDITABLE_TEXT_BYTES) {
            "Text files larger than ${MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)} MiB cannot be created in the app."
        }
        val accountId = desktopFileCacheAccountId(session)
        fun queueAffectedMetadataRefresh() =
            refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
        val response = request(
            "PUT",
            buildNextcloudFileUrl(session.serverUrl, userId, path),
            session,
            rawBody = utf8,
            contentType = "text/plain; charset=utf-8",
            headers = mapOf("Accept" to "*/*", "If-None-Match" to "*"),
            mutationExecutor = fileMutationHttpExecutor,
            onAmbiguousMutationResult = ::queueAffectedMetadataRefresh,
        )
        if (response.status == 412) return@withContext SavedTextFile(etag = null, wasCreated = false)
        check(response.status in 200..299) { "Creating the text file failed (HTTP ${response.status})." }
        check(response.status == 201) { "The server did not confirm that a new text file was created." }
        runCatching {
            refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
            response.etag?.let {
                fileReadCache.storeContent(
                    accountId,
                    path,
                    NextcloudFileContent(utf8, "text/plain; charset=utf-8", it),
                )
            }
        }
        SavedTextFile(response.etag, wasCreated = true)
    }

    override suspend fun createDirectoryIfAbsent(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        fun queueAffectedMetadataRefresh() =
            refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
        val response = request(
            method = "MKCOL",
            url = buildNextcloudFileUrl(session.serverUrl, userId, path),
            session = session,
            headers = mapOf("Accept" to "*/*", "If-None-Match" to "*"),
            maxResponseBytes = 64 * 1024,
            mutationExecutor = fileMutationHttpExecutor,
            onAmbiguousMutationResult = ::queueAffectedMetadataRefresh,
        )
        if (response.status in setOf(405, 412)) return@withContext false
        if (response.status !in 200..299) throw fileOperationException(response.status)
        check(response.status == 201) { "The server did not confirm that a new folder was created." }
        runCatching {
            refreshRetainedFoldersAfterMutation(session, userId, accountId, path)
        }
        true
    }

    override suspend fun executeFileMutation(
        session: NextcloudSession,
        userId: String,
        mutation: NextcloudFileMutation,
    ): NextcloudFileMutationResult = withContext(Dispatchers.IO) {
        val spec = mutation.toWebDavMutationSpec()
        val headers = buildMap {
            put("Accept", "*/*")
            putAll(spec.conflictConditionHeaders())
            spec.destinationPath?.let { destinationPath ->
                put("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
                put("Overwrite", if (spec.overwrite) "T" else "F")
            }
        }
        val accountId = desktopFileCacheAccountId(session)
        fun invalidateAffectedMetadata() {
            runCatching {
                refreshRetainedFoldersAfterMutation(session, userId, accountId, spec.sourcePath)
                spec.destinationPath?.let { destination ->
                    refreshRetainedFoldersAfterMutation(session, userId, accountId, destination)
                }
            }
        }
        val response = request(
            method = spec.method,
            url = buildNextcloudFileUrl(session.serverUrl, userId, spec.sourcePath),
            session = session,
            headers = headers,
            maxResponseBytes = 64 * 1024,
            mutationExecutor = fileMutationHttpExecutor,
            onAmbiguousMutationResult = ::invalidateAffectedMetadata,
        )
        if (response.status !in 200..299) throw fileOperationException(response.status)
        invalidateAffectedMetadata()
        NextcloudFileMutationResult(spec.destinationPath, response.etag)
    }

    override suspend fun executeNextcloudApi(
        session: NextcloudSession,
        request: NextcloudApiRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val safeRequest = request.requireSafe()
        safeRequest.multipartBody?.let { multipart ->
            return@withContext executeNextcloudMultipartUpload(
                session,
                multipart.toUploadRequest(safeRequest),
            )
        }
        val accountId = desktopFileCacheAccountId(session)
        val cacheIdentity = safeRequest.dynamicReadCacheIdentity()
        if (safeRequest.method != NextcloudApiMethod.GET) {
            dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
            }
        }
        suspend fun executeNetworkRequest(): NextcloudApiResponse {
            var responseBodyMayHaveStarted = false
            val response = try {
                request(
                    method = safeRequest.method.name,
                    url = buildNextcloudApiUrl(session.serverUrl, safeRequest),
                    session = session,
                    contentType = safeRequest.contentType,
                    rawBody = safeRequest.body,
                    ocsRequest = safeRequest.ocsApiRequest,
                    maxResponseBytes = safeRequest.maximumResponseBytes,
                    client = noRedirectHttpClient,
                    onFailurePhase = { phase ->
                        responseBodyMayHaveStarted = phase == JvmNetworkFailurePhase.ResponseBody
                    },
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (safeRequest.method != NextcloudApiMethod.GET) throw failure
                throw NextcloudApiReadFailure(responseBodyMayHaveStarted, failure)
            }
            return NextcloudApiResponse(
                response.status,
                response.body,
                response.contentType,
                response.etag,
                response.location,
            )
        }
        if (safeRequest.method != NextcloudApiMethod.GET) {
            return@withContext try {
                executeNetworkRequest()
            } finally {
                dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                    runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
                }
            }
        }
        executeDesktopDynamicApiGet(
            accountId = accountId,
            requestIdentity = cacheIdentity,
            cachePolicy = safeRequest.cachePolicy,
            coalescer = dynamicApiRequestCoalescer,
            loadCached = {
                dynamicApiReadCache.load(accountId, cacheIdentity, safeRequest.maximumResponseBytes)
                    ?.let { cached ->
                        NextcloudApiResponse(cached.status, cached.body, cached.contentType, cached.etag)
                    }
            },
            invalidateCached = {
                runCatching { dynamicApiReadCache.invalidate(accountId, cacheIdentity) }
            },
            executeNetwork = ::executeNetworkRequest,
            commit = { result ->
                if (
                    result.status in 200..299 &&
                    result.contentType?.contains("json", ignoreCase = true) == true
                ) {
                    runCatching {
                        dynamicApiReadCache.store(
                            accountId,
                            cacheIdentity,
                            CachedDynamicApiResponse(
                                result.status,
                                result.body,
                                result.contentType,
                                result.etag,
                            ),
                        )
                    }
                }
            },
        )
    }

    override suspend fun chooseLocalUploadFile(
        acceptedMimeTypes: List<String>,
        maximumBytes: Long,
    ): LocalUploadSelectionResult =
        localUploadPicker.choose(acceptedMimeTypes, maximumBytes)

    override fun releaseLocalUploadFile(file: LocalUploadFile) {
        localUploadPicker.release(file)
    }

    override suspend fun executeNextcloudMultipartUpload(
        session: NextcloudSession,
        request: NextcloudMultipartUploadRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val safeRequest = request.requireSafe()
        val envelope = prepareMultipartUpload(
            safeRequest,
            "nc-native-${UUID.randomUUID()}",
        )
        val requestBody = DesktopStreamingMultipartRequestBody(envelope) {
            localUploadPicker.open(safeRequest.file)
        }
        val apiRequest = NextcloudApiRequest(
            method = safeRequest.method,
            relativePath = safeRequest.relativePath,
            queryParameters = safeRequest.queryParameters,
            ocsApiRequest = safeRequest.ocsApiRequest,
            maximumResponseBytes = safeRequest.maximumResponseBytes,
        )
        val accountId = desktopFileCacheAccountId(session)
        dynamicApiRequestCoalescer.invalidateAccount(accountId) {
            runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
        }
        try {
            val response = request(
                method = safeRequest.method.name,
                url = buildNextcloudApiUrl(session.serverUrl, apiRequest),
                session = session,
                ocsRequest = safeRequest.ocsApiRequest,
                streamingBody = requestBody,
                maxResponseBytes = safeRequest.maximumResponseBytes,
                client = noRedirectHttpClient,
            )
            NextcloudApiResponse(
                response.status,
                response.body,
                response.contentType,
                response.etag,
                response.location,
            )
        } finally {
            dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
            }
        }
    }

    override suspend fun executeGroupwareDav(
        session: NextcloudSession,
        request: GroupwareDavRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val headers = buildMap {
            request.depth?.let { put("Depth", it.toString()) }
            putAll(request.headers)
        }
        val response = request(
            method = request.method,
            url = session.serverUrl.trimEnd('/') + request.relativePath,
            session = session,
            contentType = request.contentType,
            rawBody = request.body,
            headers = headers,
            maxResponseBytes = request.maximumResponseBytes,
            client = noRedirectHttpClient,
        )
        NextcloudApiResponse(response.status, response.body, response.contentType, response.etag, response.location)
    }

    override suspend fun executeMediaCollectionMutation(
        session: NextcloudSession,
        request: NativeMediaCollectionTransportRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val origin = session.serverUrl.trimEnd('/')
        val headers = buildMap {
            put("Accept", "*/*")
            request.ifMatch?.let { put("If-Match", it) }
            if (request.ifNoneMatch) put("If-None-Match", "*")
            request.destinationRelativePath?.let { put("Destination", origin + it) }
            request.overwrite?.let { put("Overwrite", if (it) "T" else "F") }
        }
        val response = request(
            method = request.method.name,
            url = origin + request.relativePath,
            session = session,
            headers = headers,
            maxResponseBytes = 64 * 1024,
            client = noRedirectHttpClient,
        )
        NextcloudApiResponse(response.status, response.body, response.contentType, response.etag, response.location)
    }

    override suspend fun executePeopleMutation(
        session: NextcloudSession,
        request: PeopleTransportRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val formBody = request.encodedFormBody()
        val headers = buildMap {
            request.destinationRelativePath?.let { destination ->
                put("Destination", buildPeopleMutationUrl(session, destination))
            }
            request.overwrite?.let { put("Overwrite", if (it) "T" else "F") }
            (request.authorization as? PeopleTransportAuthorization.RecognizeBridgeToken)?.let { authorization ->
                put(authorization.headerName, authorization.bridgeToken.value)
            }
        }
        val response = request(
            method = request.method.name,
            url = buildPeopleMutationUrl(session, request.relativePath),
            session = session,
            contentType = formBody?.let { "application/x-www-form-urlencoded; charset=utf-8" },
            rawBody = formBody,
            ocsRequest = request.surface == PeopleMutationSurface.MemoriesApi,
            headers = headers,
            maxResponseBytes = 64 * 1024,
            client = noRedirectHttpClient,
        )
        NextcloudApiResponse(response.status, response.body, response.contentType, response.etag, response.location)
    }

    override suspend fun acquireSignedOpenApiContract(
        appId: String,
        serverVersion: String,
        installedAppVersion: String?,
    ): AcquiredOpenApiContract? = withContext(Dispatchers.IO) {
        contractAcquirer.acquire(ContractAcquisitionRequest(appId, serverVersion, installedAppVersion))
            ?.let { contract ->
                AcquiredOpenApiContract(
                    appId = contract.appId,
                    appVersion = contract.appVersion,
                    contractVersion = contract.contractVersion,
                    specFile = contract.specFile,
                    document = contract.document,
                    packageUrl = contract.packageUrl,
                    sourceUrl = contract.sourceUrl,
                    sourceKind = when (contract.sourceKind) {
                        OpenApiContractSourceKind.SignedAppPackage ->
                            AcquiredOpenApiContractSourceKind.SignedAppPackage
                        OpenApiContractSourceKind.SignedCompatibleAppPackage ->
                            AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage
                        OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
                            AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag
                        OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
                            AcquiredOpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag
                    },
                    contractKind = when (contract.contractKind) {
                        VerifiedContractKind.OpenApi -> AcquiredContractKind.OpenApi
                        VerifiedContractKind.VerifiedReadRoutes -> AcquiredContractKind.VerifiedReadRoutes
                        VerifiedContractKind.OpenApiWithVerifiedReadRoutes ->
                            AcquiredContractKind.OpenApiWithVerifiedReadRoutes
                    },
                )
            }
    }

    override suspend fun listActivities(session: NextcloudSession, limit: Int): List<NextcloudActivity> =
        withContext(Dispatchers.IO) {
            val data = ocsGet(
                session,
                "/ocs/v2.php/apps/activity/api/v2/activity?limit=${boundedActivityLimit(limit)}&sort=desc",
            ).getJSONObject("ocs").getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optLong("activity_id", -1L).takeIf { it >= 0L } ?: continue
                    add(
                        NextcloudActivity(
                            id = id,
                            app = item.optString("app").ifBlank { "nextcloud" },
                            type = item.optString("type"),
                            subject = item.optString("subject").ifBlank { "Nextcloud activity" },
                            message = item.optString("message").takeIf(String::isNotBlank),
                            objectType = item.optString("object_type").takeIf(String::isNotBlank),
                            objectId = item.optString("object_id").takeIf(String::isNotBlank),
                            objectName = item.optString("object_name").takeIf(String::isNotBlank),
                            link = item.optString("link").takeIf(String::isNotBlank),
                            icon = item.optString("icon").takeIf(String::isNotBlank),
                            dateTime = item.optString("datetime").takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        }

    override suspend fun loadDocumentEditingCapabilities(
        session: NextcloudSession,
        expectedEtag: String?,
    ): NextcloudConditionalRead<NextcloudDocumentEditingCapabilities> = withContext(Dispatchers.IO) {
        val response = request(
            method = "GET",
            url = session.serverUrl + DIRECT_EDITING_INFO_RELATIVE_PATH,
            session = session,
            ocsRequest = true,
            headers = documentEditingConditionalHeaders(expectedEtag),
            maxResponseBytes = MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES,
            client = noRedirectHttpClient,
        )
        if (response.status == 304) return@withContext NextcloudConditionalRead.NotModified
        check(response.status in 200..299 && response.location == null) {
            "Loading document editing capabilities failed (HTTP ${response.status})."
        }
        val capabilitiesResponse = request(
            method = "GET",
            url = session.serverUrl + NEXTCLOUD_CAPABILITIES_RELATIVE_PATH,
            session = session,
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES,
            client = noRedirectHttpClient,
        )
        check(capabilitiesResponse.status in 200..299 && capabilitiesResponse.location == null) {
            "Loading direct-editing support failed (HTTP ${capabilitiesResponse.status})."
        }
        NextcloudConditionalRead.Modified(
            value = parseDesktopDocumentEditingCapabilities(
                response.text,
                supportsFileId = parseDesktopDirectEditingSupportsFileId(capabilitiesResponse.text),
            ),
            responseEtag = response.etag,
        )
    }

    override suspend fun beginDocumentEditSession(
        session: NextcloudSession,
        request: NextcloudDocumentEditSessionRequest,
    ): NextcloudDocumentEditSession = withContext(Dispatchers.IO) {
        val response = request(
            method = "POST",
            url = session.serverUrl + DIRECT_EDITING_OPEN_RELATIVE_PATH,
            session = session,
            body = directEditingOpenForm(request),
            contentType = "application/x-www-form-urlencoded",
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_EDIT_SESSION_RESPONSE_BYTES,
            client = noRedirectHttpClient,
        )
        check(response.status in 200..299 && response.location == null) {
            "Starting the Office edit session failed (HTTP ${response.status})."
        }
        val candidate = JSONObject(response.text)
            .getJSONObject("ocs")
            .getJSONObject("data")
            .getString("url")
        NextcloudDocumentEditSession(
            validatedDirectEditingHandoffUrl(session.serverUrl, candidate),
        )
    }

    override suspend fun listDocumentTemplates(
        session: NextcloudSession,
        editorId: String,
        creatorId: String,
    ): List<NextcloudDocumentTemplate> = withContext(Dispatchers.IO) {
        val primary = request(
            method = "GET",
            url = session.serverUrl + documentTemplatesRelativePath(editorId, creatorId),
            session = session,
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_TEMPLATES_RESPONSE_BYTES,
            client = noRedirectHttpClient,
        )
        if (primary.status in 200..299 && primary.location == null) {
            return@withContext parseDesktopDocumentTemplates(primary.text, creatorId)
        }
        check(primary.status != 401 && primary.status != 403 && primary.location == null) {
            "Loading document templates failed (HTTP ${primary.status})."
        }
        check(editorId == OFFICE_DIRECT_EDITOR_ID) {
            "Loading document templates failed (HTTP ${primary.status})."
        }
        val fallback = request(
            method = "GET",
            url = session.serverUrl + legacyRichdocumentsTemplatesRelativePath(creatorId),
            session = session,
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_TEMPLATES_RESPONSE_BYTES,
            client = noRedirectHttpClient,
        )
        check(fallback.status in 200..299 && fallback.location == null) {
            "Loading document templates failed (HTTP ${fallback.status})."
        }
        parseDesktopDocumentTemplates(fallback.text, creatorId)
    }

    override suspend fun listNotes(session: NextcloudSession): List<NextcloudNote> =
        when (val result = listNotesConditionally(session, expectedEtag = null)) {
            is NextcloudConditionalRead.Modified -> result.value
            NextcloudConditionalRead.NotModified -> error("An unconditional Notes list read returned not modified.")
        }

    override suspend fun listNotesConditionally(
        session: NextcloudSession,
        expectedEtag: String?,
    ): NextcloudConditionalRead<List<NextcloudNote>> =
        withContext(Dispatchers.IO) {
            val response = request(
                "GET",
                session.serverUrl + NOTES_LIST_RELATIVE_PATH,
                session,
                headers = notesConditionalHeaders(expectedEtag),
            )
            if (response.status == 304) return@withContext NextcloudConditionalRead.NotModified
            check(response.status in 200..299) { "Loading Notes failed (HTTP ${response.status})." }
            val data = JSONArray(response.text)
            val notes = buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.toNextcloudNote()?.let(::add)
                }
            }.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
            NextcloudConditionalRead.Modified(notes, response.etag)
        }

    override suspend fun loadNote(session: NextcloudSession, noteId: Long): NextcloudNote =
        when (val presence = inspectNotePresence(session, noteId)) {
            NextcloudNotePresence.Absent -> error("The note no longer exists.")
            is NextcloudNotePresence.Present -> presence.note
        }

    override suspend fun inspectNotePresence(
        session: NextcloudSession,
        noteId: Long,
    ): NextcloudNotePresence = withContext(Dispatchers.IO) {
        require(noteId >= 0L) { "The note ID is invalid." }
        val response = request(
            "GET",
            session.serverUrl + notesDetailRelativePath(noteId),
            session,
        )
        if (response.status == 404 || response.status == 410) {
            return@withContext NextcloudNotePresence.Absent
        }
        check(response.status in 200..299) { "Loading the note failed (HTTP ${response.status})." }
        val note = requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) {
            "The note response is invalid."
        }
        NextcloudNotePresence.Present(note)
    }

    override suspend fun loadNoteConditionally(
        session: NextcloudSession,
        noteId: Long,
        expectedEtag: String?,
    ): NextcloudConditionalRead<NextcloudNote> =
        withContext(Dispatchers.IO) {
            val response = request(
                "GET",
                session.serverUrl + notesDetailRelativePath(noteId),
                session,
                headers = notesConditionalHeaders(expectedEtag),
            )
            if (response.status == 304) return@withContext NextcloudConditionalRead.NotModified
            check(response.status != 404) { "The note no longer exists." }
            check(response.status in 200..299) { "Loading the note failed (HTTP ${response.status})." }
            val note = requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) {
                "The note response is invalid."
            }
            NextcloudConditionalRead.Modified(note, response.etag)
        }

    override suspend fun updateNote(
        session: NextcloudSession,
        noteId: Long,
        content: String,
        category: String,
        favorite: Boolean,
        expectedEtag: String?,
        title: String?,
    ): NextcloudNote = withContext(Dispatchers.IO) {
        require(content.encodeToByteArray().size.toLong() <= MAX_NOTE_BYTES) {
            "Notes larger than ${MAX_NOTE_BYTES / (1024 * 1024)} MiB cannot be edited in the app."
        }
        val body = JSONObject()
            .put("content", content)
            .put("category", category)
            .put("favorite", favorite)
            .apply { title?.let { put("title", it) } }
            .toString()
        val response = request(
            "PUT",
            session.serverUrl + notesDetailRelativePath(noteId),
            session,
            body,
            "application/json; charset=utf-8",
            headers = expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-Match" to it) }.orEmpty(),
        )
        check(response.status != 412) { "This note changed on the server. Reload it before saving your changes." }
        check(response.status != 423) { "This note is temporarily locked on the server." }
        check(response.status in 200..299) { "Saving the note failed (HTTP ${response.status})." }
        requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) { "The saved note response is invalid." }
    }

    override suspend fun createNote(
        session: NextcloudSession,
        title: String,
        content: String,
        category: String,
    ): NextcloudNote = withContext(Dispatchers.IO) {
        val plan = createNoteRequest(title, content, category)
        val response = request(
            plan.method.name,
            session.serverUrl + plan.relativePath,
            session,
            requireNotNull(plan.body).decodeToString(),
            plan.contentType,
        )
        check(response.status in 200..299) { "Creating the note failed (HTTP ${response.status})." }
        requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) {
            "The created note response is invalid."
        }
    }

    override suspend fun deleteNote(
        session: NextcloudSession,
        noteId: Long,
        expectedEtag: String?,
    ) = withContext(Dispatchers.IO) {
        val plan = deleteNoteRequest(noteId)
        val response = request(
            plan.method.name,
            session.serverUrl + plan.relativePath,
            session,
            headers = expectedEtag?.takeIf(String::isNotBlank)
                ?.let { etag -> mapOf("If-Match" to etag) }
                .orEmpty(),
        )
        check(response.status != 404) { "The note no longer exists." }
        check(response.status != 412) { "This note changed on the server. Reload it before deleting it." }
        check(response.status != 423) { "This note is temporarily locked on the server." }
        check(response.status in 200..299) { "Deleting the note failed (HTTP ${response.status})." }
    }

    override suspend fun listPeople(session: NextcloudSession, backend: String): List<NextcloudPerson> =
        withContext(Dispatchers.IO) {
            require(backend in setOf("recognize", "facerecognition")) { "Unsupported people backend." }
            val response = request(
                "GET",
                session.serverUrl + "/index.php/apps/memories/api/clusters/${encodePath(backend)}",
                session,
                ocsRequest = true,
            )
            check(response.status in 200..299) { "Loading people from Memories failed (HTTP ${response.status})." }
            val data = JSONArray(response.text)
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optLong("cluster_id", -1L).takeIf { it >= 0L } ?: continue
                    val rawName = item.optString("name")
                    add(
                        NextcloudPerson(
                            id = id,
                            name = rawName.ifBlank { "Unnamed person" },
                            userId = item.optString("user_id").ifBlank { session.loginName },
                            queryName = rawName.ifBlank { id.toString() },
                            count = item.optInt("count", 0),
                            coverFileId = item.optLong("cover", -1L).takeIf { it >= 0L },
                            coverEtag = item.optString("cover_etag").takeIf(String::isNotBlank),
                            backend = item.optString("cluster_type").ifBlank { backend },
                        ),
                    )
                }
            }.sortedWith(compareByDescending<NextcloudPerson> { it.count }.thenBy { it.name.lowercase() })
        }

    override suspend fun loadPersonCover(session: NextcloudSession, person: NextcloudPerson): ByteArray =
        withContext(Dispatchers.IO) {
            require(person.coverFileId != null) { "This person does not have a selected cover." }
            val response = request(
                "GET",
                session.serverUrl + "/index.php/apps/memories/api/clusters/${encodePath(person.backend)}/preview" +
                    "?name=${person.id}&cover=${person.coverFileId}&cover_etag=${encodeForm(person.coverEtag.orEmpty())}",
                session,
                ocsRequest = true,
                headers = mapOf("Accept" to "image/*"),
            )
            check(response.status in 200..299) { "Loading the person cover failed (HTTP ${response.status})." }
            response.body
        }

    override suspend fun listPersonMedia(session: NextcloudSession, person: NextcloudPerson): List<NextcloudFile> =
        withContext(Dispatchers.IO) {
            val filter = encodeForm("${person.userId}/${person.queryName}")
            val daysResponse = request(
                "GET",
                session.serverUrl + "/index.php/apps/memories/api/days?${encodePath(person.backend)}=$filter" +
                    "&nopreload=1&facerect=1",
                session,
                ocsRequest = true,
            )
            check(daysResponse.status in 200..299) {
                "Loading this person from Memories failed (HTTP ${daysResponse.status})."
            }
            val days = JSONArray(daysResponse.text)
            val files = linkedMapOf<Long, NextcloudFile>()
            val dayIds = buildList {
                for (index in 0 until days.length()) {
                    val day = days.optJSONObject(index) ?: continue
                    val dayId = day.optLong("dayid", -1L).takeIf { it >= 0L } ?: continue
                    add(dayId)
                    if (size >= PERSON_MEDIA_INITIAL_DAY_LIMIT) break
                }
            }
            if (dayIds.isNotEmpty()) {
                val response = request(
                    "GET",
                    session.serverUrl + "/index.php/apps/memories/api/days/${dayIds.joinToString(",")}" +
                        "?${encodePath(person.backend)}=$filter&facerect=1",
                    session,
                    ocsRequest = true,
                )
                if (response.status in 200..299) JSONArray(response.text).appendMemoryFiles(person, files)
            }
            files.values.toList()
        }

    override suspend fun listTalkRooms(session: NextcloudSession): List<TalkRoom> =
        withContext(Dispatchers.IO) {
            val data = ocsGet(session, "/ocs/v2.php/apps/spreed/api/v4/room?noStatusUpdate=1")
                .getJSONObject("ocs").getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val room = data.optJSONObject(index) ?: continue
                    val token = room.optString("token").takeIf(String::isNotBlank) ?: continue
                    add(
                        TalkRoom(
                            token,
                            room.optString("displayName").ifBlank { "Conversation" },
                            room.optJSONObject("lastMessage")
                                ?.let { parseTalkMessageJson(it.toString()) }
                                ?.content
                                ?.summary
                                ?.takeIf(String::isNotBlank),
                            room.optInt("unreadMessages", 0),
                        ),
                    )
                }
            }
        }

    override suspend fun listTalkMessages(session: NextcloudSession, token: String): List<TalkMessage> =
        listTalkMessagePage(session, token).messages

    override suspend fun listTalkMessagePage(
        session: NextcloudSession,
        token: String,
        olderCursor: Long?,
        limit: Int,
    ): TalkMessagePage =
        withContext(Dispatchers.IO) {
            val response = request(
                "GET",
                session.serverUrl + talkMessageHistoryPath(token, olderCursor, limit),
                session,
                ocsRequest = true,
            )
            val data = if (response.status == 304) {
                JSONArray()
            } else {
                check(response.status in 200..299) {
                    "Loading Talk history failed (HTTP ${response.status})."
                }
                JSONObject(response.text).getJSONObject("ocs").getJSONArray("data")
            }
            val messages = buildList {
                for (index in 0 until data.length()) {
                    val message = data.optJSONObject(index) ?: continue
                    parseTalkMessageJson(message.toString())?.let(::add)
                }
            }
            val nextCursor = response.chatLastGiven?.toLongOrNull()
            TalkMessagePage(
                messages = messages,
                olderCursor = nextCursor,
                hasMoreHistory = response.status != 304 && nextCursor != null,
            )
        }

    override suspend fun sendTalkMessage(session: NextcloudSession, token: String, message: String) =
        withContext(Dispatchers.IO) {
            val response = request(
                "POST",
                session.serverUrl + "/ocs/v2.php/apps/spreed/api/v1/chat/${encodePath(token)}?format=json",
                session,
                "message=" + encodeForm(message),
                "application/x-www-form-urlencoded",
                true,
            )
            check(response.status in 200..299) { "Sending the Talk message failed (HTTP ${response.status})." }
            Unit
        }

    override suspend fun revokeSession(session: NextcloudSession) = withContext(Dispatchers.IO) {
        request("DELETE", session.serverUrl + "/ocs/v2.php/core/apppassword", session, ocsRequest = true)
        Unit
    }

    private fun ocsGet(session: NextcloudSession, path: String): JSONObject {
        val separator = if ('?' in path) '&' else '?'
        val response = request("GET", session.serverUrl + path + separator + "format=json", session, ocsRequest = true)
        check(response.status in 200..299) { "Nextcloud API request failed (HTTP ${response.status})." }
        return JSONObject(response.text)
    }

    private fun loadFileEtag(session: NextcloudSession, userId: String, path: String): String? {
        val response = request(
            "PROPFIND",
            buildNextcloudFileUrl(session.serverUrl, userId, path),
            session,
            DAV_ETAG_PROPERTY,
            "application/xml; charset=utf-8",
            headers = mapOf("Depth" to "0", "Accept" to "application/xml"),
        )
        if (response.status != 207) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(response.body))
            .documentElement.firstText(DAV, "getetag")
    }

    private fun request(
        method: String,
        url: String,
        session: NextcloudSession? = null,
        body: String? = null,
        contentType: String? = null,
        ocsRequest: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        rawBody: ByteArray? = null,
        maxResponseBytes: Long = MAX_API_RESPONSE_BYTES,
        expectedSuccessResponseBytes: Int? = null,
        expectedSuccessResponseStatus: Int? = null,
        client: OkHttpClient = httpClient,
        streamingBody: RequestBody? = null,
        mutationExecutor: DesktopHttpMutationExecutor? = null,
        onAmbiguousMutationResult: () -> Unit = {},
        onNetworkFailure: (JvmNetworkFailureDiagnostic) -> Unit = {},
        onFailurePhase: (JvmNetworkFailurePhase) -> Unit = {},
        diagnosticIgnoredHttpStatuses: Set<Int> = emptySet(),
    ): HttpResponse {
        val started = System.nanoTime()
        require((expectedSuccessResponseBytes == null) == (expectedSuccessResponseStatus == null))
        val requestBody = when {
            streamingBody != null -> streamingBody
            rawBody != null -> rawBody.toRequestBody(contentType?.toMediaType())
            body != null -> body.toRequestBody(contentType?.toMediaType())
            method == "POST" || method == "PUT" || method == "PATCH" -> byteArrayOf().toRequestBody(null)
            else -> null
        }
        val networkAttempt = JvmNetworkRequestAttempt()
        val builder = Request.Builder().url(url).method(method, requestBody)
            .tag(JvmNetworkRequestAttempt::class.java, networkAttempt)
            .header("Accept", "application/json").header("User-Agent", USER_AGENT)
        if (ocsRequest) builder.header("OCS-APIRequest", "true")
        headers.forEach(builder::header)
        session?.let {
            val encoded = Base64.getEncoder().encodeToString("${it.loginName}:${it.appPassword}".toByteArray())
            builder.header("Authorization", "Basic $encoded")
        }
        val request = builder.build()
        fun consumeResponse(response: okhttp3.Response): HttpResponse {
            val responseBody = response.body
            val contentLength = responseBody.contentLength()
            val readLimit = if (response.isSuccessful) maxResponseBytes else MAX_ERROR_RESPONSE_BYTES
            check(contentLength <= readLimit || contentLength == -1L) {
                "The server response is larger than the allowed ${formatByteLimit(readLimit)} limit."
            }
            val bodyBytes = if (mutationExecutor != null && !response.isSuccessful) {
                runCatching { responseBody.byteStream().readBounded(readLimit) }.getOrDefault(byteArrayOf())
            } else {
                responseBody.byteStream().readBounded(readLimit)
            }
            if (response.code == expectedSuccessResponseStatus && expectedSuccessResponseBytes != null) {
                bodyBytes.requireExactJvmNetworkResponseBytes(expectedSuccessResponseBytes)
            }
            return HttpResponse(
                response.code,
                bodyBytes,
                responseBody.contentType()?.toString(),
                response.header("ETag") ?: response.header("OC-Etag"),
                if (session == null) {
                    response.header("Location")
                } else {
                    resolveDesktopNextcloudRedirectLocation(
                        requestUrl = response.request.url,
                        serverUrl = session.serverUrl,
                        location = response.header("Location"),
                    )
                },
                response.header("X-Chat-Last-Given"),
                response.header("Content-Range"),
            )
        }
        return try {
            val result = if (mutationExecutor == null) {
                client.newCall(request).execute().use(::consumeResponse)
            } else {
                mutationExecutor.execute(
                    request = request,
                    onAmbiguousNetworkResult = onAmbiguousMutationResult,
                    onAcceptedResponse = onAmbiguousMutationResult,
                    consume = ::consumeResponse,
                )
            }
            if (shouldRecordHttpStatusDiagnostic(result.status, diagnosticIgnoredHttpStatuses)) {
                recordDesktopRequestDiagnostic(
                    session,
                    SupportDiagnosticEventDraft(
                        severity = if (result.status >= 500) {
                            SupportDiagnosticSeverity.Error
                        } else {
                            SupportDiagnosticSeverity.Warning
                        },
                        component = SupportDiagnosticComponent.Network,
                        operation = "http.request",
                        outcome = "rejected",
                        code = "HTTP:${result.status}",
                        durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                        fields = listOf(
                            SupportDiagnosticFieldDraft("method", method.lowercase()),
                            SupportDiagnosticFieldDraft(
                                "url",
                                url,
                                SupportDiagnosticValuePrivacy.Url,
                            ),
                            SupportDiagnosticFieldDraft("response_bytes", result.body.size.toString()),
                            SupportDiagnosticFieldDraft("mutation", (mutationExecutor != null).toString()),
                        ),
                    ),
                )
            }
            result
        } catch (failure: Throwable) {
            onFailurePhase(networkAttempt.phase)
            if (failure.isJvmLocalUploadSourceFailure()) {
                recordDesktopRequestDiagnostic(
                    session,
                    failure.toJvmLocalUploadSourceDiagnosticEvent(
                        method = method,
                        durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                    ),
                )
                throw failure
            }
            val networkFailure = if (failure is IOException || failure is CancellationException) {
                failure.toJvmNetworkFailureDiagnostic(
                    attempt = networkAttempt,
                    readOnlyRequest = method.isReadOnlyJvmNetworkMethod(),
                    replayableRequest = requestBody?.isOneShot() != true,
                )
            } else {
                null
            }
            networkFailure?.let(onNetworkFailure)
            recordDesktopRequestDiagnostic(
                session,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Error,
                    component = SupportDiagnosticComponent.Network,
                    operation = "http.request",
                    outcome = "failed",
                    code = networkFailure?.code,
                    attempt = networkFailure?.attempt,
                    durationMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L,
                    fields = listOf(
                        SupportDiagnosticFieldDraft("method", method.lowercase()),
                        SupportDiagnosticFieldDraft("url", url, SupportDiagnosticValuePrivacy.Url),
                        SupportDiagnosticFieldDraft("mutation", (mutationExecutor != null).toString()),
                    ) + networkFailure?.fields().orEmpty(),
                    exception = failure.toSupportDiagnosticExceptionDraft(),
                ),
            )
            throw failure
        }
    }

    private fun recordDesktopRequestDiagnostic(
        session: NextcloudSession?,
        event: SupportDiagnosticEventDraft,
    ) {
        if (session == null) {
            supportDiagnostics.record(event)
        } else {
            supportDiagnostics.recordForAccountIdentity(desktopFileCacheAccountId(session), event)
        }
    }

    private fun recordDesktopStreamingFailure(
        session: NextcloudSession,
        streamKind: String,
        startedNanos: Long,
        attempt: JvmNetworkRequestAttempt,
        failure: Throwable,
    ) {
        if (failure !is IOException && failure !is CancellationException) return
        val networkFailure = failure.toJvmNetworkFailureDiagnostic(
            attempt = attempt,
            readOnlyRequest = true,
            replayableRequest = true,
        )
        if (networkFailure.isCancellation) return
        recordDesktopRequestDiagnostic(
            session,
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.Network,
                operation = "http.stream",
                outcome = "failed",
                code = networkFailure.code,
                attempt = networkFailure.attempt,
                durationMillis = (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L,
                fields = listOf(
                    SupportDiagnosticFieldDraft("method", "get"),
                    SupportDiagnosticFieldDraft("stream_kind", streamKind),
                    SupportDiagnosticFieldDraft("mutation", "false"),
                ) + networkFailure.fields(),
                exception = failure.toSupportDiagnosticExceptionDraft(),
            ),
        )
    }

    private fun java.io.InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_CAPACITY.toLong()).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_CAPACITY)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            check(total <= maxBytes) {
                "The server response is larger than the allowed ${formatByteLimit(maxBytes)} limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun formatByteLimit(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
        bytes >= 1024 -> "${bytes / 1024} KiB"
        else -> "$bytes bytes"
    }

    private fun parseDavFiles(xml: ByteArray, userId: String): List<NextcloudFile> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val responses = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            .getElementsByTagNameNS(DAV, "response")
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index)
                val name = response.firstText(DAV, "displayname") ?: continue
                val href = URLDecoder.decode(response.firstText(DAV, "href").orEmpty(), StandardCharsets.UTF_8)
                val path = href.substringAfter("/files/$userId/", name).trimEnd('/').ifBlank { name }
                add(
                    NextcloudFile(
                        path = path,
                        name = name,
                        isDirectory = response.childCount(DAV, "collection") > 0,
                        mimeType = response.firstText(DAV, "getcontenttype"),
                        size = response.firstText(OC, "size")?.toLongOrNull()
                            ?: response.firstText(DAV, "getcontentlength")?.toLongOrNull(),
                        lastModified = response.firstText(DAV, "getlastmodified"),
                        fileId = response.firstText(OC, "fileid")?.toLongOrNull(),
                        hasPreview = response.firstText(NC, "has-preview") == "true",
                        etag = response.firstText(DAV, "getetag"),
                        favorite = response.firstText(OC, "favorite") == "1",
                        ownerId = response.firstText(OC, "owner-id"),
                        ownerDisplayName = response.firstText(OC, "owner-display-name"),
                        unreadComments = response.firstText(OC, "comments-unread")?.toIntOrNull() ?: 0,
                        permissions = response.firstText(OC, "permissions"),
                    ),
                )
            }
        }
    }

    private fun fileFavoriteUpdateSucceeded(xml: ByteArray): Boolean {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val propstats = document.getElementsByTagNameNS(DAV, "propstat")
        for (index in 0 until propstats.length) {
            val propstat = propstats.item(index)
            val status = propstat.firstText(DAV, "status").orEmpty()
            val includesFavorite = propstat.childCount(OC, "favorite") > 0
            if (includesFavorite && parseDavStatusCode(status) in 200..299) return true
        }
        return false
    }

    private fun org.w3c.dom.Node.firstText(namespace: String, name: String): String? =
        (this as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, name)?.item(0)
            ?.textContent?.takeIf(String::isNotBlank)

    private fun org.w3c.dom.Node.childCount(namespace: String, name: String): Int =
        (this as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, name)?.length ?: 0

    private fun loginTransportSecurity(serverUrl: String): LoginTransportSecurity =
        if (serverUrl.startsWith("http://", ignoreCase = true)) {
            LoginTransportSecurity.PlainHttp
        } else {
            LoginTransportSecurity.Tls
        }

    private fun JSONArray.toAppEntries(): List<NextcloudAppEntry> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
            add(NextcloudAppEntry(id, item.optString("name").ifBlank { readableName(id) }, item.optString("href").takeIf(String::isNotBlank)))
        }
    }

    private fun JSONObject.toCapabilityEntries(): List<NextcloudAppEntry> = keys().asSequence()
        .filterNot { it in setOf("core", "theming") }
        .map { NextcloudAppEntry(it, readableName(it), null) }.sortedBy(NextcloudAppEntry::name).toList()

    private fun readableName(id: String): String = id.replace('_', ' ').split(' ')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun JSONObject.toNextcloudNote(responseEtag: String? = null): NextcloudNote? {
        val id = optLong("id", -1L).takeIf { it >= 0L } ?: return null
        return NextcloudNote(
            id = id,
            title = optString("title").ifBlank { "Untitled note" },
            modified = optLong("modified", 0L),
            category = optString("category"),
            favorite = optBoolean("favorite", false),
            readOnly = optBoolean("readonly", false),
            content = if (has("content")) optString("content") else null,
            etag = resolvedNoteEtag(responseEtag, optString("etag")),
            internalPath = optString("internalPath").takeIf(String::isNotBlank),
            isShared = optBoolean("isShared", false),
        )
    }

    private fun JSONArray.appendMemoryFiles(person: NextcloudPerson, target: MutableMap<Long, NextcloudFile>) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val fileId = item.optLong("fileid", -1L).takeIf { it >= 0L } ?: continue
            target.putIfAbsent(
                fileId,
                syntheticMemoriesPersonFile(
                    personId = person.id.toString(),
                    fileId = fileId,
                    name = item.optString("basename").ifBlank { "Photo $fileId" },
                    mimeType = item.optString("mimetype").takeIf(String::isNotBlank),
                    lastModified = item.optLong("epoch", 0L).takeIf { it > 0L }?.toString(),
                    etag = item.optString("etag").takeIf(String::isNotBlank),
                ),
            )
        }
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    private fun encodeForm(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val contentType: String? = null,
        val etag: String? = null,
        val location: String? = null,
        val chatLastGiven: String? = null,
        val contentRange: String? = null,
    ) {
        val text: String get() = body.toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val APP_ID = "dev.obiente.nextcloudnative"
        const val KEY_THEME = "theme"
        const val KEY_LAST_OPENED_APP = "last_opened_app"
        const val KEY_SERVER = "server"
        const val KEY_LOGIN = "login"
        const val KEY_FILE_SYNC_PAUSED = "file_sync_paused"
        const val KEY_START_ON_LOGIN = "start_on_login"
        const val KEY_KEEP_RUNNING_IN_BACKGROUND = "keep_running_in_background"
        const val DESKTOP_FILE_SYNC_INTERVAL_MILLIS = 2L * 60L * 1_000L
        const val USER_AGENT = "Nextcloud-Native/0.1.0 (Desktop)"
        const val DAV = "DAV:"
        const val OC = "http://owncloud.org/ns"
        const val NC = "http://nextcloud.org/ns"
        const val DEFAULT_BUFFER_CAPACITY = 8 * 1024
        const val MAX_API_RESPONSE_BYTES = 16L * 1024L * 1024L
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024L
        const val PERSON_MEDIA_INITIAL_DAY_LIMIT = 12
        const val MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES = 512L * 1024L
        const val MAX_DOCUMENT_EDIT_SESSION_RESPONSE_BYTES = 64L * 1024L
        const val MAX_DOCUMENT_TEMPLATES_RESPONSE_BYTES = 2L * 1024L * 1024L
        val DAV_PROPERTIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop>
              <d:displayname/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/><d:resourcetype/>
              <oc:fileid/><oc:size/><oc:permissions/><oc:favorite/><oc:owner-id/><oc:owner-display-name/>
              <oc:comments-unread/><nc:has-preview/>
            </d:prop></d:propfind>
        """.trimIndent()
        val DAV_ETAG_PROPERTY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>
        """.trimIndent()
    }
}

internal fun retainedFolderAncestorListings(relativePath: String): List<String> {
    val segments = relativePath.trim('/').split('/').filter(String::isNotBlank)
    require(segments.isNotEmpty()) { "A retained folder path is required." }
    return buildList {
        add("")
        var current = ""
        segments.dropLast(1).forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            add(current)
        }
    }
}

internal fun retainedFolderNavigationChild(parentPath: String, retainedRoot: String): String? {
    val parent = parentPath.trim('/')
    val root = retainedRoot.trim('/')
    if (root.isEmpty() || parent.isNotEmpty() && root != parent && !root.startsWith("$parent/")) return null
    if (root == parent) return null
    val remainder = if (parent.isEmpty()) root else root.removePrefix("$parent/")
    val child = remainder.substringBefore('/')
    return if (parent.isEmpty()) child else "$parent/$child"
}

internal fun retainedFolderAvailableNavigationTargets(
    currentTarget: String,
    documents: Collection<DesktopRemoteSyncDocument>,
): Set<String> = documents.mapTo(linkedSetOf()) { document -> document.entry.relativePath }.also { available ->
    check(currentTarget in available) {
        "The selected retained folder is no longer available at its saved path."
    }
}

internal fun retainedRootsMissingNavigationTarget(
    parentPath: String,
    retainedRoots: Collection<String>,
    availableTargets: Set<String>,
): Set<String> = retainedRoots.filterTo(linkedSetOf()) { retainedRoot ->
    retainedFolderNavigationChild(parentPath, retainedRoot)?.let { target -> target !in availableTargets } == true
}

private class VirtualFolderRefreshSupersededException : IllegalStateException(
    "The retained folder changed while it was being published.",
)

internal fun shouldScheduleVirtualFolderHydration(
    status: VirtualFolderHydrationStatus?,
    nowEpochMillis: Long,
    refreshIntervalMillis: Long = VIRTUAL_FOLDER_REFRESH_INTERVAL_MILLIS,
): Boolean {
    require(nowEpochMillis >= 0L)
    require(refreshIntervalMillis > 0L)
    if (status == null) return true
    if (status.phase == VirtualFolderHydrationPhase.Failed) return false
    if (status.phase != VirtualFolderHydrationPhase.AvailableOffline) return true
    if (status.refreshing) return true
    val verifiedAt = status.verifiedAtEpochMillis ?: return true
    status.refreshRetryAtEpochMillis?.let { retryAt ->
        if (nowEpochMillis >= verifiedAt) return nowEpochMillis >= retryAt
    }
    val age = nowEpochMillis - verifiedAt
    return age < 0L || age >= refreshIntervalMillis
}

internal fun virtualFolderHydrationStatusForStorageAvailability(
    status: VirtualFolderHydrationStatus,
    retainedOverflowUnavailable: Boolean,
): VirtualFolderHydrationStatus = if (retainedOverflowUnavailable) {
    status.copy(
        phase = VirtualFolderHydrationPhase.Failed,
        detail = "Reconnect the overflow cache drive to use this offline folder.",
        refreshFailure = null,
        refreshing = false,
        refreshRetryAtEpochMillis = null,
    )
} else {
    status
}

internal fun virtualFolderRefreshRetryAt(
    nowEpochMillis: Long,
    retryDelayMillis: Long = VIRTUAL_FOLDER_REFRESH_RETRY_MILLIS,
): Long {
    require(nowEpochMillis >= 0L && retryDelayMillis > 0L)
    return if (Long.MAX_VALUE - nowEpochMillis < retryDelayMillis) Long.MAX_VALUE else nowEpochMillis + retryDelayMillis
}

internal fun nextVirtualFolderRetainedMetadataCount(
    currentEntries: Int,
    additionalEntries: Int,
    maximumEntries: Int = MAX_VIRTUAL_FOLDER_DISCOVERED_ENTRIES,
): Int {
    require(currentEntries in 0..maximumEntries)
    require(additionalEntries >= 0)
    check(additionalEntries <= maximumEntries - currentEntries) {
        "The selected virtual folder contains too much metadata for one reconciliation pass."
    }
    return currentEntries + additionalEntries
}

internal fun requireVirtualFolderListingCapacity(
    currentListings: Int,
    maximumListings: Int = MAX_VIRTUAL_FOLDER_RETAINED_LISTINGS,
) {
    require(currentListings >= 0 && maximumListings > 0)
    check(currentListings < maximumListings) {
        "The selected virtual folder contains too many directories to keep on this device safely."
    }
}

internal fun isCompleteRetainedTreeListing(listingPath: String, retainedRoot: String): Boolean =
    listingPath == retainedRoot || listingPath.startsWith("$retainedRoot/")

internal fun Job?.occupiesVirtualFolderHydrationSlot(): Boolean = this != null && !isCompleted

internal fun removeVirtualFolderHydrationJobIfOwned(
    jobs: MutableMap<String, Job>,
    key: String,
    owner: Job,
): Boolean {
    if (jobs[key] !== owner) return false
    jobs.remove(key)
    return true
}

internal fun advanceAffectedVirtualFolderGenerations(
    generations: MutableMap<String, Long>,
    completedGenerations: MutableMap<String, Long>,
    accountId: String,
    retainedRoots: Iterable<String>,
) {
    retainedRoots.forEach { retainedRoot ->
        val key = "$accountId\u0000$retainedRoot"
        val current = generations.getOrDefault(key, 0L)
        if (current == Long.MAX_VALUE) {
            completedGenerations.remove(key)
            generations[key] = 1L
        } else {
            generations[key] = current + 1L
        }
    }
}

internal fun handleDesktopFileVersionRestoreStatus(status: Int, onRestored: () -> Unit) {
    when (status) {
        in 200..299 -> onRestored()
        403 -> error("You do not have permission to restore this file version.")
        404 -> error("This historical version no longer exists.")
        409 -> error("The server could not restore this version to the current file.")
        else -> error("Restoring the file version failed (HTTP $status).")
    }
}

private data class VirtualFolderListingGeneration(
    val path: String,
    val directory: Boolean,
    val remoteRevision: String,
    val size: Long?,
)

private fun List<DesktopRemoteSyncDocument>.hydrationGeneration(): List<VirtualFolderListingGeneration> =
    map { document ->
        VirtualFolderListingGeneration(
            path = document.entry.relativePath,
            directory = document.isDirectory,
            remoteRevision = document.entry.etag,
            size = document.entry.size,
        )
    }.sortedBy(VirtualFolderListingGeneration::path)

internal fun reconcileVirtualRangeChildren(
    cache: DesktopVirtualRangeCache,
    accountId: String,
    parent: String,
    documents: List<DesktopRemoteSyncDocument>,
    protectedPaths: Set<String>,
) {
    val liveChildren = documents.mapTo(hashSetOf()) { document -> document.entry.relativePath }
    cache.cachedDirectChildren(accountId, parent)
        .filterNot(liveChildren::contains)
        .filterNot { missing ->
            protectedPaths.any { protected -> protected == missing || protected.startsWith("$missing/") }
        }
        .forEach { missing -> cache.invalidateDisposableRanges(accountId, missing) }
}

internal fun publishDesktopLinuxFallbackMetadataBestEffort(
    store: LinuxVirtualMetadataStore,
    snapshots: Map<String, LinuxVirtualDirectorySnapshot>,
) {
    snapshots.filterValues(LinuxVirtualDirectorySnapshot::complete).forEach { (path, snapshot) ->
        runCatching { store.store(path, snapshot) }
    }
}

internal fun parseDesktopFileVersionDavRecords(xml: ByteArray): List<FileVersionDavRecord> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val responses = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        .getElementsByTagNameNS(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "response")
    return buildList {
        for (index in 0 until responses.length) {
            val response = responses.item(index)
            val properties = response.successfulFileVersionPropertyRoot() ?: continue
            add(
                FileVersionDavRecord(
                    href = response.fileVersionFirstText(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "href").orEmpty(),
                    contentLength = properties.fileVersionFirstText(
                        FILE_VERSION_DESKTOP_DAV_NAMESPACE,
                        "getcontentlength",
                    ),
                    lastModified = properties.fileVersionFirstText(
                        FILE_VERSION_DESKTOP_DAV_NAMESPACE,
                        "getlastmodified",
                    ),
                    etag = properties.fileVersionFirstText(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "getetag"),
                    author = properties.fileVersionFirstText(FILE_VERSION_DESKTOP_NC_NAMESPACE, "version-author"),
                    label = properties.fileVersionFirstText(FILE_VERSION_DESKTOP_NC_NAMESPACE, "version-label"),
                ),
            )
        }
    }
}

private fun org.w3c.dom.Node.successfulFileVersionPropertyRoot(): org.w3c.dom.Node? {
    val element = this as? org.w3c.dom.Element ?: return null
    val propstats = element.getElementsByTagNameNS(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "propstat")
    if (propstats.length > 0) {
        for (index in 0 until propstats.length) {
            val propstat = propstats.item(index)
            val status = propstat.fileVersionFirstText(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "status").orEmpty()
            if (status.isFileVersionDavSuccessStatus()) return propstat
        }
        return null
    }
    return if (
        element.getElementsByTagNameNS(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "status")
            .item(0)?.textContent.orEmpty().isFileVersionDavSuccessStatus()
    ) {
        element
    } else {
        null
    }
}

private fun String.isFileVersionDavSuccessStatus(): Boolean =
    trim().split(' ').any { token -> token.toIntOrNull()?.let { it in 200..299 } == true }

private fun org.w3c.dom.Node.fileVersionFirstText(namespace: String, localName: String): String? =
    (this as? org.w3c.dom.Element)
        ?.getElementsByTagNameNS(namespace, localName)
        ?.item(0)
        ?.textContent
        ?.takeIf(String::isNotBlank)

private const val FILE_VERSION_DESKTOP_DAV_NAMESPACE = "DAV:"
private const val FILE_VERSION_DESKTOP_NC_NAMESPACE = "http://nextcloud.org/ns"

internal fun parseDesktopSystemTagsDavResponse(xml: ByteArray): List<NextcloudSystemTag> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val responses = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        .getElementsByTagNameNS(SYSTEM_TAG_DESKTOP_DAV_NAMESPACE, "response")
    val records = buildList {
        for (index in 0 until responses.length) {
            val response = responses.item(index)
            add(
                SystemTagDavRecord(
                    href = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_DAV_NAMESPACE, "href").orEmpty(),
                    id = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "id"),
                    displayName = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "display-name"),
                    userVisible = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "user-visible"),
                    userAssignable = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "user-assignable"),
                    canAssign = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "can-assign"),
                    color = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_NC_NAMESPACE, "color"),
                    etag = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_DAV_NAMESPACE, "getetag"),
                ),
            )
        }
    }
    return normalizeSystemTagsDavResponse(records).tags
}

private fun org.w3c.dom.Node.systemTagFirstText(namespace: String, localName: String): String? =
    (this as? org.w3c.dom.Element)
        ?.getElementsByTagNameNS(namespace, localName)
        ?.item(0)
        ?.textContent
        ?.takeIf(String::isNotBlank)

private const val SYSTEM_TAG_DESKTOP_DAV_NAMESPACE = "DAV:"
private const val SYSTEM_TAG_DESKTOP_OC_NAMESPACE = "http://owncloud.org/ns"
private const val SYSTEM_TAG_DESKTOP_NC_NAMESPACE = "http://nextcloud.org/ns"
