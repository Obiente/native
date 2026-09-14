package dev.obiente.nextcloudnative

import org.json.JSONObject

internal fun readPriorUnownedUploadPeerDigests(payload: JSONObject): Set<String> {
    if (!payload.has("priorUnownedPeerDigests")) return emptySet()
    val entries = payload.getJSONArray("priorUnownedPeerDigests")
    require(entries.length() <= 64) { "Too many prior upload capability peers." }
    return (0 until entries.length()).map { index ->
        val id = entries.get(index)
        require(id is String && id.matches(Regex("[0-9a-f]{64}"))) { "Invalid upload capability peer identity." }
        id
    }.toSet()
}

internal fun malformedPeerCleanupDisposition(
    malformedCapabilities: Map<String, MalformedDurableUploadCapability>,
    targetSelectionId: String,
    targetPermissionIdentity: String,
    targetGrantPreExisting: Boolean = false,
    priorUnownedPeerDigests: Set<String> = emptySet(),
): DurableUploadMalformedPeerCleanupDisposition = durableUploadMalformedPeerCleanupDisposition(
    malformedPeers = malformedCapabilities.values.asSequence().map { capability ->
        DurableUploadPermissionPeer(
            capability.selectionId,
            capability.cleanupPermissionIdentity,
            capability.grantPreExisting,
        )
    }.asIterable(),
    targetSelectionId = targetSelectionId,
    targetPermission = targetPermissionIdentity,
    samePermission = String::equals,
    targetGrantPreExisting = targetGrantPreExisting,
    priorUnownedPeerDigests = priorUnownedPeerDigests,
)

internal fun uploadPeerEvidenceKey(selectionId: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(selectionId.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
