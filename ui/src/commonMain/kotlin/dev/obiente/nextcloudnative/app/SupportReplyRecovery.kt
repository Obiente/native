package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

enum class SupportDiagnosticsReplyRecoveryState {
    None,
    RefreshRequired,
    DeliveredAwaitingAcknowledgement,
    DeliveryUnknownAwaitingAcknowledgement,
}

@ConsistentCopyVisibility
internal data class SupportReplyRecoveryMarker private constructor(
    val reporterMessageIdsBeforeAttempt: Set<String>?,
    val reporterMessageCountBeforeAttempt: Int?,
    val lastReporterMessageIdBeforeAttempt: String?,
    val attemptedReplyIdentity: SupportReplyRecoveryIdentity?,
    val deliveryConfirmed: Boolean,
    val deliveryUnknownAfterRefresh: Boolean,
) {
    val presentationState: SupportDiagnosticsReplyRecoveryState
        get() = when {
            deliveryConfirmed -> SupportDiagnosticsReplyRecoveryState.DeliveredAwaitingAcknowledgement
            deliveryUnknownAfterRefresh ->
                SupportDiagnosticsReplyRecoveryState.DeliveryUnknownAwaitingAcknowledgement
            else -> SupportDiagnosticsReplyRecoveryState.RefreshRequired
        }

    val acknowledgementRequired: Boolean
        get() = deliveryConfirmed || deliveryUnknownAfterRefresh

    fun markDelivered(): SupportReplyRecoveryMarker =
        if (deliveryConfirmed) this else copy(deliveryConfirmed = true, deliveryUnknownAfterRefresh = false)

    private fun markDeliveryUnknownAfterRefresh(): SupportReplyRecoveryMarker =
        if (deliveryUnknownAfterRefresh) this else copy(deliveryUnknownAfterRefresh = true)

    fun afterAuthoritativeGet(
        reporterMessages: Collection<SupportReplyRecoveryObservation>,
    ): SupportReplyRecoveryMarker? {
        if (acknowledgementRequired) return this
        val current = reporterMessages.normalizedRecoveryObservations() ?: return this
        reporterMessageIdsBeforeAttempt?.let { previousIds ->
            val additions = current.filter { it.id !in previousIds }
            return when {
                additions.any(SupportReplyRecoveryObservation::attemptedReplyMatch) -> markDelivered()
                additions.isEmpty() -> null
                else -> markDeliveryUnknownAfterRefresh()
            }
        }
        val previousCount = reporterMessageCountBeforeAttempt
            ?: return markDeliveryUnknownAfterRefresh()
        if (current.size < previousCount || previousCount > 0 &&
            current[previousCount - 1].id != lastReporterMessageIdBeforeAttempt
        ) return this
        val additions = current.drop(previousCount)
        return when {
            additions.any(SupportReplyRecoveryObservation::attemptedReplyMatch) -> markDelivered()
            attemptedReplyIdentity == null && additions.isNotEmpty() -> markDeliveryUnknownAfterRefresh()
            else -> null
        }
    }

    fun persisted(): PersistedSupportReplyRecoveryMarker = PersistedSupportReplyRecoveryMarker(
        reporterMessageIdsBeforeAttempt = reporterMessageIdsBeforeAttempt?.sorted(),
        reporterMessageCountBeforeAttempt = reporterMessageCountBeforeAttempt,
        lastReporterMessageIdBeforeAttempt = lastReporterMessageIdBeforeAttempt,
        attemptedReplyIdentity = null,
        deliveryConfirmed = deliveryConfirmed,
        deliveryUnknownAfterRefresh = deliveryUnknownAfterRefresh,
    )

    companion object {
        fun awaiting(
            reporterMessageIds: Collection<String>,
            attemptedReplyIdentity: SupportReplyRecoveryIdentity,
        ): SupportReplyRecoveryMarker? =
            reporterMessageIds.normalizedRecoveryCursor()?.let { cursor ->
                SupportReplyRecoveryMarker(
                    reporterMessageIdsBeforeAttempt = null,
                    reporterMessageCountBeforeAttempt = cursor.messageCount,
                    lastReporterMessageIdBeforeAttempt = cursor.lastMessageId,
                    attemptedReplyIdentity = attemptedReplyIdentity,
                    deliveryConfirmed = false,
                    deliveryUnknownAfterRefresh = false,
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
            require(!persisted.deliveryConfirmed || !persisted.deliveryUnknownAfterRefresh)
            return SupportReplyRecoveryMarker(
                reporterMessageIdsBeforeAttempt = ids,
                reporterMessageCountBeforeAttempt = cursor?.messageCount,
                lastReporterMessageIdBeforeAttempt = cursor?.lastMessageId,
                attemptedReplyIdentity = null,
                deliveryConfirmed = persisted.deliveryConfirmed,
                deliveryUnknownAfterRefresh = persisted.deliveryUnknownAfterRefresh,
            )
        }

        fun legacyUnknown(): SupportReplyRecoveryMarker = SupportReplyRecoveryMarker(
            null, null, null, null, deliveryConfirmed = false, deliveryUnknownAfterRefresh = false,
        )
    }
}

@Serializable
internal data class PersistedSupportReplyRecoveryMarker(
    val reporterMessageIdsBeforeAttempt: List<String>? = null,
    val reporterMessageCountBeforeAttempt: Int? = null,
    val lastReporterMessageIdBeforeAttempt: String? = null,
    val attemptedReplyIdentity: SupportReplyRecoveryIdentity? = null,
    val deliveryConfirmed: Boolean = false,
    val deliveryUnknownAfterRefresh: Boolean = false,
)

@Serializable
internal data class SupportReplyRecoveryIdentity(
    val salt: String,
    val digest: String,
) {
    init {
        require(salt.matches(SUPPORT_REPLY_RECOVERY_SALT))
        require(digest.matches(SUPPORT_REPLY_RECOVERY_DIGEST))
    }
}

internal data class SupportReplyRecoveryObservation(
    val id: String,
    val attemptedReplyMatch: Boolean,
)

private data class SupportReplyRecoveryCursor(
    val messageCount: Int,
    val lastMessageId: String?,
)

private fun Collection<SupportReplyRecoveryObservation>.normalizedRecoveryObservations(): List<SupportReplyRecoveryObservation>? {
    val observations = toList()
    return observations.takeIf { values ->
        values.size <= MAX_SUPPORT_CONVERSATION_MESSAGES &&
            values.distinctBy(SupportReplyRecoveryObservation::id).size == values.size &&
            values.all { it.id.matches(SUPPORT_REPLY_RECOVERY_ID) }
    }
}

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
private val SUPPORT_REPLY_RECOVERY_SALT = Regex("^[0-9a-f]{32}$")
private val SUPPORT_REPLY_RECOVERY_DIGEST = Regex("^[0-9a-f]{64}$")
