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
    val reporterMessageCountBeforeAttempt: Int?,
    val lastReporterMessageIdBeforeAttempt: String?,
    val deliveryConfirmed: Boolean,
) {
    val presentationState: SupportDiagnosticsReplyRecoveryState
        get() = if (deliveryConfirmed) SupportDiagnosticsReplyRecoveryState.DeliveredAwaitingAcknowledgement
        else SupportDiagnosticsReplyRecoveryState.RefreshRequired

    fun markDelivered(): SupportReplyRecoveryMarker =
        if (deliveryConfirmed) this else copy(deliveryConfirmed = true)

    fun afterAuthoritativeGet(reporterMessageIds: Collection<String>): SupportReplyRecoveryMarker? {
        if (deliveryConfirmed) return this
        reporterMessageIdsBeforeAttempt?.let { previousIds ->
            return if (reporterMessageIds.any { it !in previousIds }) markDelivered() else null
        }
        val previousCount = reporterMessageCountBeforeAttempt ?: return this
        val current = reporterMessageIds.normalizedRecoveryCursor() ?: return this
        return when {
            current.messageCount > previousCount -> markDelivered()
            current.messageCount == previousCount &&
                current.lastMessageId == lastReporterMessageIdBeforeAttempt -> null
            else -> this
        }
    }

    fun persisted(): PersistedSupportReplyRecoveryMarker = PersistedSupportReplyRecoveryMarker(
        reporterMessageIdsBeforeAttempt = reporterMessageIdsBeforeAttempt?.sorted(),
        reporterMessageCountBeforeAttempt = reporterMessageCountBeforeAttempt,
        lastReporterMessageIdBeforeAttempt = lastReporterMessageIdBeforeAttempt,
        deliveryConfirmed = deliveryConfirmed,
    )

    companion object {
        fun awaiting(reporterMessageIds: Collection<String>): SupportReplyRecoveryMarker? =
            reporterMessageIds.normalizedRecoveryCursor()?.let { cursor ->
                SupportReplyRecoveryMarker(
                    reporterMessageIdsBeforeAttempt = null,
                    reporterMessageCountBeforeAttempt = cursor.messageCount,
                    lastReporterMessageIdBeforeAttempt = cursor.lastMessageId,
                    deliveryConfirmed = false,
                )
            }

        fun restored(persisted: PersistedSupportReplyRecoveryMarker): SupportReplyRecoveryMarker {
            val persistedIds = persisted.reporterMessageIdsBeforeAttempt
            val ids = persistedIds?.let { requireNotNull(it.normalizedRecoveryIds()) }
            val cursor = persisted.reporterMessageCountBeforeAttempt?.let { messageCount ->
                require(messageCount in 0..MAX_SUPPORT_CONVERSATION_MESSAGES)
                val lastMessageId = persisted.lastReporterMessageIdBeforeAttempt
                require((messageCount == 0) == (lastMessageId == null))
                require(lastMessageId == null || lastMessageId.matches(SUPPORT_REPLY_RECOVERY_ID))
                SupportReplyRecoveryCursor(messageCount, lastMessageId)
            }
            require(ids == null || cursor == null)
            require(!persisted.deliveryConfirmed || ids != null || cursor != null)
            return SupportReplyRecoveryMarker(
                reporterMessageIdsBeforeAttempt = ids,
                reporterMessageCountBeforeAttempt = cursor?.messageCount,
                lastReporterMessageIdBeforeAttempt = cursor?.lastMessageId,
                deliveryConfirmed = persisted.deliveryConfirmed,
            )
        }

        fun legacyUnknown(): SupportReplyRecoveryMarker = SupportReplyRecoveryMarker(null, null, null, false)
    }
}

@Serializable
internal data class PersistedSupportReplyRecoveryMarker(
    val reporterMessageIdsBeforeAttempt: List<String>? = null,
    val reporterMessageCountBeforeAttempt: Int? = null,
    val lastReporterMessageIdBeforeAttempt: String? = null,
    val deliveryConfirmed: Boolean = false,
)

private data class SupportReplyRecoveryCursor(
    val messageCount: Int,
    val lastMessageId: String?,
)

private fun Collection<String>.normalizedRecoveryCursor(): SupportReplyRecoveryCursor? {
    if (size > MAX_SUPPORT_CONVERSATION_MESSAGES || size != distinct().size ||
        any { !it.matches(SUPPORT_REPLY_RECOVERY_ID) }
    ) return null
    return SupportReplyRecoveryCursor(size, lastOrNull())
}

private fun Collection<String>.normalizedRecoveryIds(): Set<String>? {
    val distinct = toSet()
    return distinct.takeIf { ids ->
        ids.size == size && ids.size <= MAX_SUPPORT_CONVERSATION_MESSAGES &&
            ids.all { it.matches(SUPPORT_REPLY_RECOVERY_ID) }
    }
}

internal const val MAX_SUPPORT_CONVERSATION_MESSAGES = 1_000
private val SUPPORT_REPLY_RECOVERY_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
