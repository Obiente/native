package dev.obiente.nextcloudnative.app

enum class FileSyncDirection {
    Bidirectional,
    DownloadOnly,
    UploadOnly,
}

enum class FileSyncConflictPolicy {
    Ask,
    KeepBoth,
    PreferLocal,
    PreferRemote,
}

enum class FileSyncDeletionPolicy {
    Ask,
    Propagate,
    RestoreMissing,
}

enum class FileSyncNetworkPolicy {
    AnyConnection,
    Unmetered,
}

enum class FileSyncPowerPolicy {
    AnyPower,
    BatteryNotLow,
    Charging,
}

enum class SyncEntryKind { File, Directory }

data class LocalSyncEntry(
    val relativePath: String,
    val kind: SyncEntryKind,
    val revision: String,
    val size: Long? = null,
    val contentHash: String? = null,
) {
    init {
        requireValidSyncPath(relativePath)
        require(revision.isNotBlank())
        require(size == null || size >= 0L)
        require(contentHash == null || kind == SyncEntryKind.File) {
            "Only files can expose a sync content hash."
        }
        require(contentHash == null || normalizeSyncSha256(contentHash) == contentHash) {
            "The local sync content hash is invalid."
        }
    }
}

data class RemoteSyncEntry(
    val relativePath: String,
    val kind: SyncEntryKind,
    val etag: String,
    val size: Long? = null,
    val contentHash: String? = null,
) {
    init {
        requireValidSyncPath(relativePath)
        require(etag.isNotBlank())
        require(size == null || size >= 0L)
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
) {
    init {
        requireValidSyncPath(relativePath)
        require(localRevision != null || remoteEtag != null)
    }
}

data class FileSyncConfiguration(
    val direction: FileSyncDirection = FileSyncDirection.Bidirectional,
    val conflictPolicy: FileSyncConflictPolicy = FileSyncConflictPolicy.Ask,
    val deletionPolicy: FileSyncDeletionPolicy = FileSyncDeletionPolicy.Ask,
    val deviceLabel: String,
    val networkPolicy: FileSyncNetworkPolicy = FileSyncNetworkPolicy.AnyConnection,
    val powerPolicy: FileSyncPowerPolicy = FileSyncPowerPolicy.BatteryNotLow,
) {
    init {
        require(deviceLabel.isNotBlank())
    }
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

    val localChanged = local?.revision != baseline.localRevision
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
        FileSyncDeletionPolicy.Propagate ->
            FileSyncOperation.DeleteRemote(path, remote.etag)
        FileSyncDeletionPolicy.RestoreMissing ->
            FileSyncOperation.Download(path, null)
    }
    remote == null && local != null -> when (configuration.deletionPolicy) {
        FileSyncDeletionPolicy.Ask ->
            FileSyncOperation.NeedsDecision(path, FileSyncDecisionReason.RemoteDeletion)
        FileSyncDeletionPolicy.Propagate ->
            FileSyncOperation.DeleteLocal(path, local.revision)
        FileSyncDeletionPolicy.RestoreMissing ->
            FileSyncOperation.Upload(path, null)
    }
    else -> null
}

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
    if (configuration.direction == FileSyncDirection.DownloadOnly || remoteChanged) {
        return FileSyncOperation.Download(path, null)
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
    if (configuration.direction == FileSyncDirection.UploadOnly || localChanged) {
        return FileSyncOperation.Upload(path, null)
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
): FileSyncOperation = resolveConflict(
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

internal fun requireValidSyncPath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/'))
    require('\u0000' !in path && '\\' !in path)
    require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." })
}
