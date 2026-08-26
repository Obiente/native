package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

@Serializable
enum class FileSyncDirection {
    Bidirectional,
    DownloadOnly,
    UploadOnly,
}

@Serializable
enum class FileSyncConflictPolicy {
    Ask,
    KeepBoth,
    PreferLocal,
    PreferRemote,
}

@Serializable
enum class FileSyncDeletionPolicy {
    Ask,
    Propagate,
    RestoreMissing,
}

@Serializable
enum class FileSyncNetworkPolicy {
    AnyConnection,
    Unmetered,
}

@Serializable
enum class FileSyncPowerPolicy {
    AnyPower,
    BatteryNotLow,
    Charging,
}

/**
 * One ordered transfer-priority group. The first matching rule wins.
 *
 * Patterns use portable path globs: `*` and `?` match inside one path segment and `**` matches
 * across directories. A pattern without `/` matches a name at any depth. Matching is deliberately
 * case-insensitive so camera extensions such as `.RAF` and `.raf` share one policy on every
 * platform; path identity itself remains case-preserving and platform-specific.
 */
@Serializable
data class FileSyncPriorityRule(val pattern: String) {
    init {
        requireValidFileSyncGlob(pattern)
    }
}

enum class SyncEntryKind { File, Directory }

data class LocalSyncEntry(
    val relativePath: String,
    val kind: SyncEntryKind,
    val revision: String,
    val size: Long? = null,
    val contentHash: String? = null,
    val modifiedEpochMillis: Long? = null,
    val contentIdentityUnverified: Boolean = false,
) {
    init {
        requireValidSyncPath(relativePath)
        require(revision.isNotBlank())
        require(size == null || size >= 0L)
        require(modifiedEpochMillis == null || modifiedEpochMillis >= 0L)
        require(contentHash == null || kind == SyncEntryKind.File) {
            "Only files can expose a sync content hash."
        }
        require(contentHash == null || normalizeSyncSha256(contentHash) == contentHash) {
            "The local sync content hash is invalid."
        }
        require(!contentIdentityUnverified || kind == SyncEntryKind.File)
    }
}

data class RemoteSyncEntry(
    val relativePath: String,
    val kind: SyncEntryKind,
    val etag: String,
    val size: Long? = null,
    val contentHash: String? = null,
    val modifiedEpochMillis: Long? = null,
) {
    init {
        requireValidSyncPath(relativePath)
        require(etag.isNotBlank())
        require(size == null || size >= 0L)
        require(modifiedEpochMillis == null || modifiedEpochMillis >= 0L)
        require(contentHash == null || kind == SyncEntryKind.File) {
            "Only files can expose a sync content hash."
        }
        require(contentHash == null || normalizeSyncSha256(contentHash) == contentHash) {
            "The remote sync content hash is invalid."
        }
    }
}

/**
 * Canonicalizes a server or platform SHA-256 content identity for safe equality checks.
 *
 * Content hashes are evidence only when both sides independently produced the same strong digest.
 * They are never accepted as a substitute for revision preconditions on a write or delete.
 */
fun normalizeSyncSha256(value: String): String? {
    val separator = value.indexOf(':')
    if (separator <= 0 || separator == value.lastIndex) return null
    val algorithm = value.substring(0, separator)
        .replace("-", "")
        .lowercase()
    if (algorithm != "sha256") return null
    val digest = value.substring(separator + 1).lowercase()
    if (digest.length != SHA_256_HEX_LENGTH || digest.any { it !in "0123456789abcdef" }) return null
    return "sha256:$digest"
}

/** Revisions recorded only after a complete, verified sync operation. */
data class FileSyncBaseline(
    val relativePath: String,
    val kind: SyncEntryKind,
    val localRevision: String?,
    val remoteEtag: String?,
    val contentHash: String? = null,
) {
    init {
        requireValidSyncPath(relativePath)
        require(localRevision != null || remoteEtag != null)
        require(contentHash == null || kind == SyncEntryKind.File)
        require(contentHash == null || normalizeSyncSha256(contentHash) == contentHash)
    }
}

@Serializable
data class FileSyncConfiguration(
    val direction: FileSyncDirection = FileSyncDirection.Bidirectional,
    val conflictPolicy: FileSyncConflictPolicy = FileSyncConflictPolicy.Ask,
    val deletionPolicy: FileSyncDeletionPolicy = FileSyncDeletionPolicy.Ask,
    val deviceLabel: String,
    val networkPolicy: FileSyncNetworkPolicy = FileSyncNetworkPolicy.AnyConnection,
    val powerPolicy: FileSyncPowerPolicy = FileSyncPowerPolicy.BatteryNotLow,
    val selectedPaths: List<String> = emptyList(),
    val ignoredPatterns: List<String> = emptyList(),
    val priorityRules: List<FileSyncPriorityRule> = emptyList(),
) {
    init {
        require(deviceLabel.isNotBlank())
        require(selectedPaths.size <= MAX_FILE_SYNC_SELECTION_PATHS)
        require(ignoredPatterns.size <= MAX_FILE_SYNC_FILTER_PATTERNS)
        require(priorityRules.size <= MAX_FILE_SYNC_PRIORITY_RULES)
        selectedPaths.forEach(::requireValidSyncPath)
        ignoredPatterns.forEach(::requireValidFileSyncGlob)
        require(selectedPaths.distinct() == selectedPaths) { "Selective sync paths must be unique." }
        require(ignoredPatterns.distinct() == ignoredPatterns) { "Ignore patterns must be unique." }
        require(priorityRules.distinct() == priorityRules) { "Priority rules must be unique." }
    }
}

/** True when [relativePath] belongs to the configured selective-sync view and is not ignored. */
fun FileSyncConfiguration.includesSyncPath(
    relativePath: String,
    kind: SyncEntryKind,
): Boolean {
    requireValidSyncPath(relativePath)
    val pathSegments = relativePath.split('/')
    val pathAndParents = pathSegments.indices.map { endIndex ->
        pathSegments.take(endIndex + 1).joinToString("/")
    }
    if (ignoredPatterns.any { pattern ->
            pathAndParents.any { candidate -> fileSyncGlobMatches(pattern, candidate) }
        }
    ) {
        return false
    }
    if (selectedPaths.isEmpty()) return true
    return selectedPaths.any { selected ->
        relativePath == selected ||
            relativePath.startsWith("$selected/") ||
            (kind == SyncEntryKind.Directory && selected.startsWith("$relativePath/"))
    }
}

/** Zero-based ordered priority group, with unmatched files after every configured group. */
fun FileSyncConfiguration.fileSyncPriority(relativePath: String): Int {
    requireValidSyncPath(relativePath)
    return priorityRules.indexOfFirst { fileSyncGlobMatches(it.pattern, relativePath) }
        .takeIf { it >= 0 }
        ?: priorityRules.size
}

fun fileSyncGlobMatches(pattern: String, relativePath: String): Boolean {
    requireValidFileSyncGlob(pattern)
    requireValidSyncPath(relativePath)
    val patternSegments = pattern.lowercase().split('/')
    val pathSegments = relativePath.lowercase().split('/')
    if (patternSegments.size == 1) {
        return pathSegments.any { segment -> matchFileSyncSegment(patternSegments.single(), segment) }
    }
    val memo = mutableMapOf<Pair<Int, Int>, Boolean>()
    fun match(patternIndex: Int, pathIndex: Int): Boolean = memo.getOrPut(patternIndex to pathIndex) {
        when {
            patternIndex == patternSegments.size -> pathIndex == pathSegments.size
            patternSegments[patternIndex] == "**" ->
                match(patternIndex + 1, pathIndex) ||
                    (pathIndex < pathSegments.size && match(patternIndex, pathIndex + 1))
            pathIndex == pathSegments.size -> false
            else -> matchFileSyncSegment(patternSegments[patternIndex], pathSegments[pathIndex]) &&
                match(patternIndex + 1, pathIndex + 1)
        }
    }
    return match(0, 0)
}

private fun matchFileSyncSegment(pattern: String, value: String): Boolean {
    var previous = BooleanArray(value.length + 1)
    previous[0] = true
    pattern.forEach { token ->
        val current = BooleanArray(value.length + 1)
        when (token) {
            '*' -> {
                current[0] = previous[0]
                for (index in 1..value.length) {
                    current[index] = previous[index] || current[index - 1]
                }
            }
            '?' -> {
                for (index in 1..value.length) current[index] = previous[index - 1]
            }
            else -> {
                for (index in 1..value.length) {
                    current[index] = previous[index - 1] && token == value[index - 1]
                }
            }
        }
        previous = current
    }
    return previous[value.length]
}

internal fun requireValidFileSyncGlob(pattern: String) {
    require(pattern.isNotBlank() && pattern.length <= MAX_FILE_SYNC_GLOB_LENGTH)
    require(!pattern.startsWith('/') && !pattern.endsWith('/'))
    require('\\' !in pattern && pattern.none(Char::isISOControl))
    require(pattern.split('/').all { it.isNotBlank() && it != "." && it != ".." })
}

sealed interface FileSyncOperation {
    val relativePath: String

    data class Upload(
        override val relativePath: String,
        val expectedRemoteEtag: String?,
    ) : FileSyncOperation

    data class Download(
        override val relativePath: String,
        val expectedLocalRevision: String?,
    ) : FileSyncOperation

    data class DeleteLocal(
        override val relativePath: String,
        val expectedLocalRevision: String,
    ) : FileSyncOperation

    data class DeleteRemote(
        override val relativePath: String,
        val expectedRemoteEtag: String,
    ) : FileSyncOperation

    data class KeepBoth(
        override val relativePath: String,
        val localConflictPath: String,
        val remoteConflictPath: String,
    ) : FileSyncOperation

    data class NeedsDecision(
        override val relativePath: String,
        val reason: FileSyncDecisionReason,
    ) : FileSyncOperation

    data class Skipped(
        override val relativePath: String,
        val reason: String,
    ) : FileSyncOperation
}

enum class FileSyncDecisionReason {
    FirstSyncCollision,
    SimultaneousEdit,
    LocalDeletion,
    RemoteDeletion,
    TypeChanged,
}

data class FileSyncPlan(val operations: List<FileSyncOperation>) {
    val conflicts: List<FileSyncOperation.NeedsDecision>
        get() = operations.filterIsInstance<FileSyncOperation.NeedsDecision>()
}

fun planFileSync(
    localEntries: List<LocalSyncEntry>,
    remoteEntries: List<RemoteSyncEntry>,
    baselines: List<FileSyncBaseline>,
    configuration: FileSyncConfiguration,
): FileSyncPlan {
    requireUniqueSyncPaths(localEntries.map(LocalSyncEntry::relativePath), "local")
    requireUniqueSyncPaths(remoteEntries.map(RemoteSyncEntry::relativePath), "remote")
    requireUniqueSyncPaths(baselines.map(FileSyncBaseline::relativePath), "baseline")
    val localByPath = localEntries.associateBy(LocalSyncEntry::relativePath)
    val remoteByPath = remoteEntries.associateBy(RemoteSyncEntry::relativePath)
    val baselineByPath = baselines.associateBy(FileSyncBaseline::relativePath)
    val paths = (localByPath.keys + remoteByPath.keys + baselineByPath.keys).sorted()
    return FileSyncPlan(
        paths.mapNotNull { path ->
            planSyncPath(
                path = path,
                local = localByPath[path],
                remote = remoteByPath[path],
                baseline = baselineByPath[path],
                configuration = configuration,
            )
        },
    )
}

private fun planSyncPath(
    path: String,
    local: LocalSyncEntry?,
    remote: RemoteSyncEntry?,
    baseline: FileSyncBaseline?,
    configuration: FileSyncConfiguration,
): FileSyncOperation? {
    if (local != null && remote != null && local.kind != remote.kind) {
        return FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.TypeChanged)
    }
    if (baseline != null && local != null && local.kind != baseline.kind) {
        return FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.TypeChanged)
    }
    if (baseline != null && remote != null && remote.kind != baseline.kind) {
        return FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.TypeChanged)
    }
    if (
        local?.kind == SyncEntryKind.File &&
        remote?.kind == SyncEntryKind.File &&
        local.contentIdentityUnverified
    ) {
        return FileSyncOperation.Skipped(path, "Exact content verification is continuing in the background.")
    }
    if (
        local?.kind == SyncEntryKind.File &&
        remote?.kind == SyncEntryKind.File &&
        local.contentHash != null &&
        local.contentHash == remote.contentHash
    ) {
        return null
    }
    if (local?.kind == SyncEntryKind.Directory || remote?.kind == SyncEntryKind.Directory) {
        return planDirectory(path, local, remote, baseline, configuration)
    }
    if (baseline == null) {
        return planFirstSync(path, local, remote, configuration)
    }

    val localContentChanged = local?.contentHash != null && local.contentHash != baseline.contentHash
    val localChanged = local?.revision != baseline.localRevision || localContentChanged
    val remoteChanged = remote?.etag != baseline.remoteEtag
    return when {
        !localChanged && !remoteChanged -> null
        local == null && remote == null -> null
        local == null -> planLocalDeletion(path, remote, baseline, remoteChanged, configuration)
        remote == null -> planRemoteDeletion(path, local, baseline, localChanged, configuration)
        localChanged && remoteChanged -> resolveEditConflict(path, local, remote, configuration)
        localChanged -> when (configuration.direction) {
            FileSyncDirection.Bidirectional, FileSyncDirection.UploadOnly ->
                FileSyncOperation.Upload(path, baseline.remoteEtag)
            FileSyncDirection.DownloadOnly -> FileSyncOperation.Download(path, local.revision)
        }
        remoteChanged -> when (configuration.direction) {
            FileSyncDirection.Bidirectional, FileSyncDirection.DownloadOnly ->
                FileSyncOperation.Download(path, baseline.localRevision)
            FileSyncDirection.UploadOnly -> FileSyncOperation.Upload(path, remote.etag)
        }
        else -> null
    }
}

private fun planDirectory(
    path: String,
    local: LocalSyncEntry?,
    remote: RemoteSyncEntry?,
    baseline: FileSyncBaseline?,
    configuration: FileSyncConfiguration,
): FileSyncOperation? = when {
    local != null && remote != null -> null
    baseline == null && local == null && remote != null &&
        configuration.direction != FileSyncDirection.UploadOnly ->
        FileSyncOperation.Download(path, null)
    baseline == null && remote == null && local != null &&
        configuration.direction != FileSyncDirection.DownloadOnly ->
        FileSyncOperation.Upload(path, null)
    local == null && remote != null && configuration.direction == FileSyncDirection.DownloadOnly ->
        FileSyncOperation.Download(path, null)
    remote == null && local != null && configuration.direction == FileSyncDirection.UploadOnly ->
        FileSyncOperation.Upload(path, null)
    local == null && remote != null -> when (configuration.deletionPolicy) {
        FileSyncDeletionPolicy.Ask ->
            FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.LocalDeletion)
        FileSyncDeletionPolicy.Propagate -> if (configuration.hasPartialDirectoryView()) {
            FileSyncOperation.Skipped(path, PARTIAL_DIRECTORY_DELETION_REASON)
        } else {
            FileSyncOperation.DeleteRemote(path, remote.etag)
        }
        FileSyncDeletionPolicy.RestoreMissing ->
            FileSyncOperation.Download(path, null)
    }
    remote == null && local != null -> when (configuration.deletionPolicy) {
        FileSyncDeletionPolicy.Ask ->
            FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.RemoteDeletion)
        FileSyncDeletionPolicy.Propagate -> if (configuration.hasPartialDirectoryView()) {
            FileSyncOperation.Skipped(path, PARTIAL_DIRECTORY_DELETION_REASON)
        } else {
            FileSyncOperation.DeleteLocal(path, local.revision)
        }
        FileSyncDeletionPolicy.RestoreMissing ->
            FileSyncOperation.Upload(path, null)
    }
    else -> null
}

private fun FileSyncConfiguration.hasPartialDirectoryView(): Boolean =
    selectedPaths.isNotEmpty() || ignoredPatterns.isNotEmpty()

private const val PARTIAL_DIRECTORY_DELETION_REASON =
    "Directory deletion is paused because selective or ignored items may exist below it."

private fun planFirstSync(
    path: String,
    local: LocalSyncEntry?,
    remote: RemoteSyncEntry?,
    configuration: FileSyncConfiguration,
): FileSyncOperation? = when {
    local != null && remote != null -> when (configuration.direction) {
        FileSyncDirection.DownloadOnly -> FileSyncOperation.Download(path, local.revision)
        FileSyncDirection.UploadOnly -> FileSyncOperation.Upload(path, remote.etag)
        FileSyncDirection.Bidirectional -> resolveConflict(
            path,
            local,
            remote,
            configuration,
            FileSyncDecisionReason.FirstSyncCollision,
        )
    }
    local != null && configuration.direction != FileSyncDirection.DownloadOnly -> FileSyncOperation.Upload(path, null)
    remote != null && configuration.direction != FileSyncDirection.UploadOnly -> FileSyncOperation.Download(path, null)
    else -> FileSyncOperation.Skipped(path, "The configured sync direction excludes this item.")
}

private fun planLocalDeletion(
    path: String,
    remote: RemoteSyncEntry?,
    baseline: FileSyncBaseline,
    remoteChanged: Boolean,
    configuration: FileSyncConfiguration,
): FileSyncOperation? {
    if (remote == null) return null
    if (configuration.direction == FileSyncDirection.DownloadOnly) {
        return FileSyncOperation.Download(path, null)
    }
    if (remoteChanged) {
        return if (configuration.direction == FileSyncDirection.UploadOnly) {
            FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.LocalDeletion)
        } else {
            FileSyncOperation.Download(path, null)
        }
    }
    return when (configuration.deletionPolicy) {
        FileSyncDeletionPolicy.Ask -> FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.LocalDeletion)
        FileSyncDeletionPolicy.Propagate -> FileSyncOperation.DeleteRemote(path, remote.etag)
        FileSyncDeletionPolicy.RestoreMissing -> FileSyncOperation.Download(path, baseline.localRevision)
    }
}

private fun planRemoteDeletion(
    path: String,
    local: LocalSyncEntry,
    baseline: FileSyncBaseline,
    localChanged: Boolean,
    configuration: FileSyncConfiguration,
): FileSyncOperation? {
    if (configuration.direction == FileSyncDirection.UploadOnly) {
        return FileSyncOperation.Upload(path, null)
    }
    if (local.contentIdentityUnverified) {
        return FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.RemoteDeletion)
    }
    if (localChanged) {
        return if (configuration.direction == FileSyncDirection.DownloadOnly) {
            FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.RemoteDeletion)
        } else {
            FileSyncOperation.Upload(path, null)
        }
    }
    return when (configuration.deletionPolicy) {
        FileSyncDeletionPolicy.Ask -> FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.RemoteDeletion)
        FileSyncDeletionPolicy.Propagate -> FileSyncOperation.DeleteLocal(path, local.revision)
        FileSyncDeletionPolicy.RestoreMissing -> FileSyncOperation.Upload(path, baseline.remoteEtag)
    }
}

private fun resolveEditConflict(
    path: String,
    local: LocalSyncEntry,
    remote: RemoteSyncEntry,
    configuration: FileSyncConfiguration,
): FileSyncOperation = if (local.contentIdentityUnverified) {
    FileSyncOperation.Skipped(path, "Exact content verification is continuing in the background.")
} else resolveConflict(
    path,
    local,
    remote,
    configuration,
    FileSyncDecisionReason.SimultaneousEdit,
)

private fun resolveConflict(
    path: String,
    local: LocalSyncEntry,
    remote: RemoteSyncEntry,
    configuration: FileSyncConfiguration,
    reason: FileSyncDecisionReason,
): FileSyncOperation = when (configuration.conflictPolicy) {
    FileSyncConflictPolicy.Ask -> FileSyncOperation.NeedsDecision(path, reason)
    FileSyncConflictPolicy.PreferLocal -> FileSyncOperation.Upload(path, remote.etag)
    FileSyncConflictPolicy.PreferRemote -> FileSyncOperation.Download(path, local.revision)
    FileSyncConflictPolicy.KeepBoth -> {
        val label = configuration.deviceLabel.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("").trim('-').take(24).ifBlank { "device" }
        FileSyncOperation.KeepBoth(
            relativePath = path,
            localConflictPath = fileSyncConflictCopyPath(path, "$label-local"),
            remoteConflictPath = fileSyncConflictCopyPath(path, "server"),
        )
    }
}

internal fun fileSyncConflictCopyPath(path: String, label: String): String {
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    val name = path.substringAfterLast('/')
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    val stem = if (extension.isBlank()) name else name.removeSuffix(".$extension")
    val conflictName = if (extension.isBlank()) "$stem (conflict-$label)" else "$stem (conflict-$label).$extension"
    return if (parent.isBlank()) conflictName else "$parent/$conflictName"
}

private fun requireUniqueSyncPaths(paths: List<String>, source: String) {
    require(paths.distinct().size == paths.size) { "The $source sync snapshot contains duplicate paths." }
}

private const val SHA_256_HEX_LENGTH = 64
internal const val MAX_FILE_SYNC_SELECTION_PATHS = 256
internal const val MAX_FILE_SYNC_FILTER_PATTERNS = 256
internal const val MAX_FILE_SYNC_PRIORITY_RULES = 64
internal const val MAX_FILE_SYNC_GLOB_LENGTH = 1_024

internal fun requireValidSyncPath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/'))
    require('\u0000' !in path && '\\' !in path)
    require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." })
}
