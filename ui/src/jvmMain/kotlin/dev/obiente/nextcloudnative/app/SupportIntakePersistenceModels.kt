package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

@Serializable
internal data class PersistedCompletedSubmission(
    val originAccountIdentity: String,
    val receipt: SupportIntakeReceipt,
    val acknowledgedStatus: String = receipt.status,
    val lastReadMaintainerMessageId: String? = null,
    val replyDeliveryUnknown: Boolean = false,
    val replyRecovery: PersistedSupportReplyRecoveryMarker? = null,
) {
    fun restoredReplyRecovery(): SupportReplyRecoveryMarker? =
        replyRecovery?.let { persisted -> SupportReplyRecoveryMarker.restored(persisted) }
            ?: if (replyDeliveryUnknown) SupportReplyRecoveryMarker.legacyUnknown() else null
}

@Serializable
internal data class PersistedPendingSubmission(
    val archiveName: String?,
    val metadata: SupportIntakeMetadata,
    val idempotencyKey: String,
    val createdAtEpochMillis: Long,
    val originAccountIdentity: String,
    val context: PreparedSupportSubmissionContext,
    val cancellationPending: Boolean = false,
    val outcomeAmbiguous: Boolean = true,
    val cancellationRequiresTombstone: Boolean? = null,
    val latestUploadAttemptAtEpochMillis: Long? = null,
    // Early PR #386 descriptors included wall time, but only the server tombstone confirms cancellation.
    val cancellationRequestedAtEpochMillis: Long? = null,
    val retryNotBeforeEpochMillis: Long? = null,
    val receipt: SupportIntakeReceipt? = null,
)

@Serializable
internal data class SupportIntakeProblem(val contractVersion: Int, val code: String, val message: String)

@Serializable
internal data class SupportConversationMessageInput(val body: String)

@Serializable
internal data class SupportPrivateStatus(
    val contractVersion: Int,
    val supportCode: String,
    val productId: String,
    val requestType: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val retentionUntil: String,
    val messages: List<SupportPrivateMessage>,
)

@Serializable
internal data class SupportPrivateMessage(
    val id: String,
    val author: String,
    val body: String,
    val createdAt: String,
)
