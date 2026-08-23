package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

enum class SupportDiagnosticsReplyRecoveryState {
    None,
    RefreshRequired,
    DeliveredAwaitingAcknowledgement,
}

@ConsistentCopyVisibility
internal data class SupportReplyRecoveryMarker private constructor(
    val reporterMessageIdsBeforeAttempt: Set<String>?,
    val deliveryConfirmed: Boolean,
) {
    val presentationState: SupportDiagnosticsReplyRecoveryState
        get() = if (deliveryConfirmed) SupportDiagnosticsReplyRecoveryState.DeliveredAwaitingAcknowledgement
        else SupportDiagnosticsReplyRecoveryState.RefreshRequired

    fun markDelivered(): SupportReplyRecoveryMarker =
        if (deliveryConfirmed) this else copy(deliveryConfirmed = true)

    fun afterAuthoritativeGet(reporterMessageIds: Collection<String>): SupportReplyRecoveryMarker? = when {
        deliveryConfirmed -> this
        reporterMessageIdsBeforeAttempt == null -> this
        reporterMessageIds.any { messageId -> messageId !in reporterMessageIdsBeforeAttempt } -> markDelivered()
        else -> null
    }

    fun persisted(): PersistedSupportReplyRecoveryMarker = PersistedSupportReplyRecoveryMarker(
        reporterMessageIdsBeforeAttempt = reporterMessageIdsBeforeAttempt?.sorted(),
        deliveryConfirmed = deliveryConfirmed,
    )

    companion object {
        fun awaiting(reporterMessageIds: Collection<String>): SupportReplyRecoveryMarker? =
            reporterMessageIds.normalizedRecoveryIds()?.let { ids -> SupportReplyRecoveryMarker(ids, false) }

        fun restored(persisted: PersistedSupportReplyRecoveryMarker): SupportReplyRecoveryMarker {
            val persistedIds = persisted.reporterMessageIdsBeforeAttempt
            val ids = persistedIds?.let { requireNotNull(it.normalizedRecoveryIds()) }
            require(!persisted.deliveryConfirmed || ids != null)
            return SupportReplyRecoveryMarker(ids, persisted.deliveryConfirmed)
        }

        fun legacyUnknown(): SupportReplyRecoveryMarker = SupportReplyRecoveryMarker(null, false)
    }
}

@Serializable
internal data class PersistedSupportReplyRecoveryMarker(
    val reporterMessageIdsBeforeAttempt: List<String>? = null,
    val deliveryConfirmed: Boolean = false,
)

private fun Collection<String>.normalizedRecoveryIds(): Set<String>? {
    val distinct = toSet()
    return distinct.takeIf { ids ->
        ids.size <= MAX_SUPPORT_REPLY_RECOVERY_IDS && ids.all { it.matches(SUPPORT_REPLY_RECOVERY_ID) }
    }
}

private const val MAX_SUPPORT_REPLY_RECOVERY_IDS = 512
private val SUPPORT_REPLY_RECOVERY_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
