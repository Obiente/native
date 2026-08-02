package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/** Durable, account-scoped intent for content inside the virtual filesystem namespace. */
@Serializable
enum class VirtualFolderRetention {
    Automatic,
    KeepOnDevice,
}

@Serializable
enum class VirtualFolderHydrationPhase {
    Queued,
    Downloading,
    AvailableOffline,
    Failed,
}

data class VirtualFolderHydrationStatus(
    val relativePath: String,
    val phase: VirtualFolderHydrationPhase,
    val detail: String? = null,
    val refreshFailure: String? = null,
) {
    init {
        require(relativePath.isNotEmpty())
        FileOfflineKey("account", relativePath)
        require(detail == null || detail.isNotBlank() && detail.length <= MAX_VIRTUAL_FOLDER_HYDRATION_DETAIL_LENGTH)
        require(phase == VirtualFolderHydrationPhase.Failed || detail == null)
        require(
            refreshFailure == null ||
                phase == VirtualFolderHydrationPhase.AvailableOffline &&
                refreshFailure.isNotBlank() &&
                refreshFailure.length <= MAX_VIRTUAL_FOLDER_HYDRATION_DETAIL_LENGTH
        )
    }
}

data class VirtualFolderRetentionRule(
    val relativePath: String,
    val retention: VirtualFolderRetention,
) {
    init {
        require(relativePath.isNotEmpty()) { "Choose a folder below the virtual filesystem root." }
        FileOfflineKey("account", relativePath)
    }
}

data class VirtualFolderRetentionState(
    val rules: List<VirtualFolderRetentionRule> = emptyList(),
) {
    init {
        require(rules.size <= MAX_VIRTUAL_FOLDER_RETENTION_RULES)
        require(rules.map(VirtualFolderRetentionRule::relativePath).distinct().size == rules.size)
    }

    fun retentionFor(relativePath: String): VirtualFolderRetention {
        val normalized = FileOfflineKey("account", relativePath).relativePath
        return rules
            .asSequence()
            .filter { rule -> normalized == rule.relativePath || normalized.startsWith("${rule.relativePath}/") }
            .maxByOrNull { rule -> rule.relativePath.length }
            ?.retention
            ?: VirtualFolderRetention.Automatic
    }

    /** Replaces one subtree's intent and removes rules that can no longer affect its descendants. */
    fun withRetention(relativePath: String, retention: VirtualFolderRetention): VirtualFolderRetentionState {
        val normalized = FileOfflineKey("account", relativePath).relativePath
        val inherited = rules
            .filter { rule -> normalized.startsWith("${rule.relativePath}/") }
            .maxByOrNull { rule -> rule.relativePath.length }
            ?.retention
            ?: VirtualFolderRetention.Automatic
        val retained = rules.filterNot { rule ->
            rule.relativePath == normalized || rule.relativePath.startsWith("$normalized/")
        }
        val next = if (retention == inherited) retained else retained + VirtualFolderRetentionRule(normalized, retention)
        return VirtualFolderRetentionState(next.sortedBy(VirtualFolderRetentionRule::relativePath))
    }
}

data class VirtualFolderContentState(
    val key: FileOfflineKey,
    val remoteRevision: String,
    val sizeBytes: Long,
    val hydratedBytes: Long,
    val directory: Boolean = false,
    val dirty: Boolean = false,
    val activeLeaseCount: Int = 0,
    val activity: VirtualFileActivity = VirtualFileActivity.Idle,
) {
    init {
        require(remoteRevision.isNotBlank())
        require(sizeBytes >= 0L && hydratedBytes in 0L..sizeBytes)
        require(!directory || sizeBytes == 0L)
        require(activeLeaseCount >= 0)
    }
}

sealed interface VirtualFolderRetentionAction {
    val key: FileOfflineKey

    data class Hydrate(
        override val key: FileOfflineKey,
        val expectedRemoteRevision: String,
        val remainingBytes: Long,
    ) : VirtualFolderRetentionAction

    data class Dehydrate(
        override val key: FileOfflineKey,
        val expectedRemoteRevision: String,
        val reclaimableBytes: Long,
    ) : VirtualFolderRetentionAction

    data class RetainUntilSafe(
        override val key: FileOfflineKey,
        val reason: String,
    ) : VirtualFolderRetentionAction
}

data class VirtualFolderRetentionPlan(
    val actions: List<VirtualFolderRetentionAction>,
    val hydrationBytes: Long,
    val reclaimableBytes: Long,
) {
    init {
        require(hydrationBytes >= 0L && reclaimableBytes >= 0L)
        require(actions.map(VirtualFolderRetentionAction::key).distinct().size == actions.size)
    }
}

/**
 * Plans retention changes without treating virtual-only entries as deletions.
 *
 * Dehydration is revision guarded and is blocked for edits, open handles, transfers, conflicts,
 * and any other non-idle state. Executors must repeat these checks immediately before releasing
 * bytes because this plan is only a snapshot.
 */
fun planVirtualFolderRetention(
    state: VirtualFolderRetentionState,
    contents: List<VirtualFolderContentState>,
): VirtualFolderRetentionPlan {
    require(contents.map(VirtualFolderContentState::key).distinct().size == contents.size)
    val actions = contents.asSequence().filterNot(VirtualFolderContentState::directory).mapNotNull { content ->
        when (state.retentionFor(content.key.relativePath)) {
            VirtualFolderRetention.KeepOnDevice -> if (content.hydratedBytes < content.sizeBytes) {
                VirtualFolderRetentionAction.Hydrate(
                    content.key,
                    content.remoteRevision,
                    content.sizeBytes - content.hydratedBytes,
                )
            } else {
                null
            }
            VirtualFolderRetention.Automatic -> if (content.hydratedBytes == 0L) {
                null
            } else if (content.dirty) {
                VirtualFolderRetentionAction.RetainUntilSafe(content.key, "Local changes must be uploaded first.")
            } else if (content.activeLeaseCount > 0) {
                VirtualFolderRetentionAction.RetainUntilSafe(content.key, "The file is currently open.")
            } else if (content.activity != VirtualFileActivity.Idle) {
                VirtualFolderRetentionAction.RetainUntilSafe(content.key, "Active or failed work must finish first.")
            } else {
                VirtualFolderRetentionAction.Dehydrate(
                    content.key,
                    content.remoteRevision,
                    content.hydratedBytes,
                )
            }
        }
    }.toList()
    return VirtualFolderRetentionPlan(
        actions = actions,
        hydrationBytes = actions.filterIsInstance<VirtualFolderRetentionAction.Hydrate>().sumOf { it.remainingBytes },
        reclaimableBytes = actions.filterIsInstance<VirtualFolderRetentionAction.Dehydrate>().sumOf { it.reclaimableBytes },
    )
}

private const val MAX_VIRTUAL_FOLDER_RETENTION_RULES = 1_024
private const val MAX_VIRTUAL_FOLDER_HYDRATION_DETAIL_LENGTH = 256
