package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NATIVE_SYNTHETIC_RESOURCE_FIELD
import dev.obiente.nextcloudnative.nativeui.runtime.effectiveNativeResourceId
import dev.obiente.nextcloudnative.nativeui.runtime.presentationValue

internal data class DynamicMessageReadSelection(
    val record: NativeRecord,
    val resourceId: String,
    val parameters: Map<String, String>,
)

/** A verified thread route contains message envelopes, not new thread identities. */
internal fun dynamicThreadMessageReadSelection(
    descriptor: DynamicAppDescriptor,
    sourceActionId: String,
    record: NativeRecord,
    parameters: Map<String, String>,
): DynamicMessageReadSelection? {
    val source = descriptor.actions.singleOrNull { it.id == sourceActionId } ?: return null
    if (!source.isVerifiedMessageRead(descriptor) ||
        record.effectiveNativeResourceId(source.resourceId) != source.resourceId ||
        !record.actionBindingProvenanceValid) return null
    val segments = source.binding.path.split('/').filter(String::isNotBlank)
    if (segments.size < 3 || segments.last() != "thread") return null
    val parentName = segments[segments.lastIndex - 2]
    if (parentName !in setOf("messages", "message", "emails", "email")) return null
    val parameter = source.binding.pathParameters.singleOrNull() ?: return null
    if (segments[segments.lastIndex - 1] != "{${parameter.name}}" || !parameter.required) return null
    val parent = descriptor.resources.singleOrNull { it.id == parentName }
        ?: descriptor.resources.singleOrNull { it.id.sameDynamicResourceAs(parentName) } ?: return null
    val bodyPath = source.binding.path.removeSuffix("thread") + "body"
    if (descriptor.actions.none { candidate ->
            candidate.binding.path == bodyPath && candidate.isVerifiedMessageRead(descriptor) &&
                candidate.binding.pathParameters == source.binding.pathParameters
        }) return null
    // The thread controller's database identity is distinct from IMAP UID, mailbox and Message-ID.
    val messageId = record.presentationValue("databaseId") ?: return null
    val mailboxId = record.presentationValue("mailboxId") ?: return null
    if (!messageId.isBoundedDatabaseIdentity() || !mailboxId.isBoundedDatabaseIdentity() ||
        record.id != messageId || parameters["mailboxId"]?.let { it != mailboxId } == true) return null
    val selected = record.copy(
        values = record.values + (NATIVE_SYNTHETIC_RESOURCE_FIELD to parent.id),
        actionSafeIdentity = false,
    )
    return DynamicMessageReadSelection(selected, parent.id,
        parameters.filterKeys { it != parameter.name && !it.equals("messageId", ignoreCase = true) } + (parameter.name to messageId))
}

private fun DynamicAction.isVerifiedMessageRead(descriptor: DynamicAppDescriptor): Boolean =
    binding.method == HttpMethod.GET && risk == ActionRisk.readOnly &&
        intent in setOf(ActionIntent.list, ActionIntent.read) &&
        confidence in setOf(Confidence.high, Confidence.verified) &&
        binding.path.isApproved(descriptor.endpointPolicy) &&
        provenance.any { it.kind in setOf(ProvenanceKind.advertisedOpenApi,
            ProvenanceKind.verifiedAppPackage, ProvenanceKind.verifiedAdapter) }

private fun String.isBoundedDatabaseIdentity(): Boolean =
    length in 1..19 && all { it in '0'..'9' } && toLongOrNull()?.let { it > 0 } == true
