package dev.obiente.nextcloudnative.app

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal fun createSupportReplyRecoveryMarker(
    reporterMessageIds: Collection<String>,
    attemptedReply: String,
    salt: String = UUID.randomUUID().toString().replace("-", ""),
): SupportReplyRecoveryMarker? {
    val identity = SupportReplyRecoveryIdentity(salt, supportReplyDigest(salt, attemptedReply))
    return SupportReplyRecoveryMarker.awaiting(reporterMessageIds, identity)
}

internal fun SupportReplyRecoveryMarker.observeReporterMessage(
    message: SupportPrivateMessage,
): SupportReplyRecoveryObservation = observeReporterMessage(message.id, message.body)

internal fun SupportReplyRecoveryMarker.observeReporterMessage(
    id: String,
    body: String,
): SupportReplyRecoveryObservation = SupportReplyRecoveryObservation(
    id = id,
    attemptedReplyMatch = attemptedReplyIdentity?.let { identity ->
        supportReplyDigest(identity.salt, body) == identity.digest
    } ?: false,
)

private fun supportReplyDigest(salt: String, reply: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(salt.toByteArray(StandardCharsets.US_ASCII))
    digest.update(0)
    return digest.digest(reply.toByteArray(StandardCharsets.UTF_8)).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
}
