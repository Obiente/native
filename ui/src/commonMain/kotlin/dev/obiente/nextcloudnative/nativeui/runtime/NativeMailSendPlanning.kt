package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.repeatableObjectInputSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** An identity the user can send from, taken from a loaded account-like record. */
internal data class NativeMailSender(
    /** Exact action-safe record identity, or null when the operation has no identity field. */
    val id: String?,
    val email: String?,
    val label: String,
) {
    /** Local selection key; never sent anywhere. */
    val key: String get() = id ?: email ?: label
}

/** How one recipient field is serialized, read from its declared schema. */
internal sealed interface NativeMailRecipientEncoding {
    /** A declared array of objects; [emailKey] holds the address and [labelKey] the display name. */
    data class ObjectList(val emailKey: String, val labelKey: String?) : NativeMailRecipientEncoding

    /** A declared array of address strings. */
    data object StringList : NativeMailRecipientEncoding

    /** A single address string. More than one recipient cannot be expressed without guessing. */
    data object SingleAddress : NativeMailRecipientEncoding
}

internal data class NativeMailRecipientField(
    val name: String,
    val encoding: NativeMailRecipientEncoding,
)

/**
 * A send operation the installed app's own contract describes, with every required input bound to
 * a mail role. Nothing here comes from an app identifier or version: any app whose contract has the
 * same shape gets the same composer.
 */
internal data class NativeMailSendCapability(
    val action: ActionSpec,
    val senders: List<NativeMailSender>,
    val to: NativeMailRecipientField,
    val cc: NativeMailRecipientField?,
    val bcc: NativeMailRecipientField?,
    val subjectField: String?,
    val bodyField: String,
    val htmlFlagField: String?,
    /** Fields and parameters that take the sending account's record identity, and nothing else. */
    val senderIdentityFields: List<String>,
    val senderAddressField: String?,
    /** Takes the original's Message-ID. */
    val inReplyToField: String? = null,
    /** Takes the original's References chain followed by its Message-ID. */
    val referencesField: String? = null,
) {
    val supportsCc: Boolean get() = cc != null
    val supportsBcc: Boolean get() = bcc != null

    /** Every reason [draft] cannot be sent yet, in display order. Empty means it can be sent. */
    fun problems(draft: NativeMailComposeDraft): List<String> = buildList {
        if (senderFor(draft) == null) add("Choose the account to send from.")
        val (to, invalidTo) = parseNativeMailRecipients(draft.to)
        val (cc, invalidCc) = parseNativeMailRecipients(draft.cc)
        val (bcc, invalidBcc) = parseNativeMailRecipients(draft.bcc)
        if (to.isEmpty()) add("Add at least one recipient.")
        (invalidTo + invalidCc + invalidBcc).firstOrNull()?.let { add("\"$it\" is not a valid address.") }
        if (cc.isNotEmpty() && this@NativeMailSendCapability.cc == null) add("This app does not accept Cc recipients.")
        if (bcc.isNotEmpty() && this@NativeMailSendCapability.bcc == null) add("This app does not accept Bcc recipients.")
        listOfNotNull(
            this@NativeMailSendCapability.to to to,
            this@NativeMailSendCapability.cc?.let { it to cc },
            this@NativeMailSendCapability.bcc?.let { it to bcc },
        ).forEach { (field, addresses) ->
            if (field.encoding == NativeMailRecipientEncoding.SingleAddress && addresses.size > 1) {
                add("This app accepts one address per recipient field.")
            }
        }
        if (to.size + cc.size + bcc.size > MAX_SEND_RECIPIENTS) add("There are too many recipients.")
        if (draft.subject.length > MAX_SUBJECT_LENGTH) add("The subject is too long.")
        if (subjectField == null && draft.subject.isNotBlank()) add("This app does not accept a subject.")
    }.distinct()

    /**
     * The sender the draft names. Only a draft that names none falls back to the sole sender: a
     * chosen sender that is no longer offered is never replaced by another account silently.
     */
    fun senderFor(draft: NativeMailComposeDraft): NativeMailSender? =
        senders.firstOrNull { it.key == draft.senderId } ?: senders.singleOrNull()?.takeIf { draft.senderId == null }

    /** Builds the exact contract request. Throws [IllegalArgumentException] when [problems] is not empty. */
    fun request(draft: NativeMailComposeDraft): NativeActionRequest.Submit {
        val problems = problems(draft)
        require(problems.isEmpty()) { problems.first() }
        val sender = requireNotNull(senderFor(draft))
        val values = buildMap {
            put(to.name, to.encoding.encode(parseNativeMailRecipients(draft.to).first))
            cc?.let { field ->
                parseNativeMailRecipients(draft.cc).first.takeIf(List<NativeMailAddress>::isNotEmpty)
                    ?.let { put(field.name, field.encoding.encode(it)) }
            }
            bcc?.let { field ->
                parseNativeMailRecipients(draft.bcc).first.takeIf(List<NativeMailAddress>::isNotEmpty)
                    ?.let { put(field.name, field.encoding.encode(it)) }
            }
            subjectField?.let { put(it, draft.subject.trim()) }
            put(bodyField, nativeMailComposedBody(draft))
            htmlFlagField?.let { put(it, "false") }
            senderIdentityFields.forEach { field -> put(field, requireNotNull(sender.id)) }
            senderAddressField?.let { put(it, requireNotNull(sender.email)) }
            inReplyToField?.let { field -> draft.inReplyTo?.let { put(field, it) } }
            referencesField?.let { field -> (draft.references ?: draft.inReplyTo)?.let { put(field, it) } }
        }
        return NativeActionRequest.Submit(action = action, values = values, confirmed = true)
    }
}

/**
 * Finds the one send operation that the contract describes for this mailbox.
 *
 * The operation must be a verified write whose name or route says it sends, and whose body declares
 * recipients and a message body. Every required input must map to a known mail role: recipients,
 * subject, body, an HTML flag, the sending account's identity or address, or a threading header
 * (In-Reply-To or References).
 * Attachments and every other required input make the operation unavailable, because the client
 * would have to invent a value. Two equally good candidates are ambiguous and offer nothing.
 */
internal fun nativeMailSendCapability(
    schema: NativeAppSchema,
    context: NativeDatasetContext,
): NativeMailSendCapability? {
    // A send is an external effect (R6): generic surfaces never see it, so it is found among the
    // operations reserved for dedicated surfaces. The composer is that surface: it records a
    // durable attempt before the request and never resends without explicit confirmation.
    val candidates = (schema.actions + schema.dedicatedActions).mapNotNull { action -> action.toSendCandidate(schema, context) }
    return candidates.singleOrNull()
}

private fun ActionSpec.toSendCandidate(
    schema: NativeAppSchema,
    context: NativeDatasetContext,
): NativeMailSendCapability? {
    // A declared send is a POST, or an idempotent replacement with an exact recovery read. Either
    // way it is sent once through the exactly-once attempt record and never resent unconfirmed.
    val recoverableReplacement = binding.method == HttpMethod.PUT && resultRecoveryActionId != null
    if (binding.method != HttpMethod.POST && !recoverableReplacement) return null
    if (risk != ActionRisk.mutating || intent !in SEND_INTENTS) return null
    if (confidence !in setOf(Confidence.high, Confidence.verified)) return null
    if (evidence.none { it.source in TRUSTED_SEND_EVIDENCE }) return null
    val words = "$id $label ${binding.operationId} ${binding.path}".sendWords()
    if ("send" !in words || words.any { it in NON_SEND_WORDS }) return null
    if (binding.bodyContentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") return null
    val properties = ((binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject) ?: return null
    val required = binding.requiredBodyFieldNames.toSet()

    fun fieldFor(roles: Set<String>): String? =
        properties.keys.filter { it.nativeMailRoleKey() in roles }
            .minByOrNull { name -> roles.indexOf(name.nativeMailRoleKey()) }

    val toName = fieldFor(TO_ROLES) ?: return null
    val to = properties[toName]?.recipientEncoding()?.let { NativeMailRecipientField(toName, it) } ?: return null
    val cc = fieldFor(CC_ROLES)?.let { name -> properties[name]?.recipientEncoding()?.let { NativeMailRecipientField(name, it) } }
    val bcc = fieldFor(BCC_ROLES)?.let { name -> properties[name]?.recipientEncoding()?.let { NativeMailRecipientField(name, it) } }
    val subject = fieldFor(SUBJECT_ROLES)?.takeIf { properties[it].declaredType() == "string" }
    val body = fieldFor(BODY_ROLES)?.takeIf { properties[it].declaredType() == "string" } ?: return null
    val html = fieldFor(HTML_FLAG_ROLES)?.takeIf { properties[it].declaredType() == "boolean" }
    val senderAddress = fieldFor(SENDER_ADDRESS_ROLES)?.takeIf { properties[it].declaredType() == "string" }
    // Each identity input has exactly one role, read from its own name. Sending accounts are the
    // only identity records the composer loads, so only account-role inputs can be bound. A
    // required sender-identity (alias) input would need a record from another resource and makes
    // the operation unavailable; an optional one is left for the server to default.
    val accountBody = properties.keys.filter { it.nativeMailRoleKey() in ACCOUNT_IDENTITY_ROLES }
    if (properties.keys.any { it.nativeMailRoleKey() in ALIAS_IDENTITY_ROLES && it in required }) return null
    val inReplyTo = fieldFor(IN_REPLY_TO_ROLES)?.takeIf { properties[it].declaredType() == "string" }
    val references = fieldFor(REFERENCES_ROLES)?.takeIf { properties[it].declaredType() == "string" }
    val assigned = setOfNotNull(toName, cc?.name, bcc?.name, subject, body, html, senderAddress, inReplyTo, references) +
        accountBody
    // A required field without a role would need an invented value.
    if (!assigned.containsAll(required)) return null
    val parameterNames = binding.requiredPathParameterNames + binding.requiredQueryParameterNames
    if (parameterNames.any { it.nativeMailRoleKey() !in ACCOUNT_IDENTITY_ROLES }) return null
    val identityFields = (accountBody + parameterNames).distinct()
    val addressRequired = senderAddress != null && senderAddress in required
    val senders = context.nativeMailSenders(schema).filter { sender ->
        (identityFields.isEmpty() || sender.id != null) && (!addressRequired || sender.email != null)
    }.ifEmpty {
        // The server chooses the sender when the operation has no sender input at all.
        if (identityFields.isEmpty() && senderAddress == null) listOf(NativeMailSender(null, null, "Your account")) else emptyList()
    }
    if (senders.isEmpty()) return null
    return NativeMailSendCapability(
        action = this,
        senders = senders,
        to = to,
        cc = cc,
        bcc = bcc,
        subjectField = subject,
        bodyField = body,
        htmlFlagField = html,
        senderIdentityFields = identityFields,
        senderAddressField = senderAddress,
        inReplyToField = inReplyTo,
        referencesField = references,
    )
}

/**
 * Sending accounts already loaded in this workspace, in a stable order. Only records of a resource
 * named as a mail account count: a contact, suggestion, or recipient list also carries addresses,
 * but its identities are not accounts the operation can send from.
 */
internal fun NativeDatasetContext.nativeMailSenders(schema: NativeAppSchema): List<NativeMailSender> {
    val records = buildList {
        relatedRecords.forEach { (resourceId, records) ->
            schema.resource(resourceId)?.let { resource -> records.forEach { add(resource to it) } }
        }
        parentResourceId?.let(schema::resource)?.let { resource -> parentRecord?.let { add(resource to it) } }
    }
    return records.filter { (resource, _) -> resource.isMailAccountResource() }.mapNotNull { (resource, record) ->
        if (nativeMailboxPresentation(resource, record).kind != NativeMailboxItemKind.Account) return@mapNotNull null
        val id = record.id.takeIf { record.actionSafeIdentity && record.actionBindingProvenanceValid && it.isNotBlank() }
        val email = record.nativeMailAddresses("email", "emailaddress", "address").firstOrNull()?.email
        if (id == null && email == null) return@mapNotNull null
        val label = listOf("name", "accountname", "displayname").firstNotNullOfOrNull { key ->
            record.values.entries.firstOrNull { it.key.nativeMailRoleKey() == key }?.value?.takeIf(String::isNotBlank)
        }
        NativeMailSender(
            id = id,
            email = email,
            label = when {
                label != null && email != null && label != email -> "$label <$email>"
                else -> email ?: label ?: "Account"
            },
        )
    }.distinctBy { it.id to it.email }.sortedBy(NativeMailSender::label)
}

private fun ResourceSpec.isMailAccountResource(): Boolean =
    "$id $name".nativeMailWords().any { it in ACCOUNT_RESOURCE_WORDS }

/** Lowercase words of identifiers such as `mail_accounts` or `mailAccounts`. */
internal fun String.nativeMailWords(): Set<String> = replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .lowercase()
    .split(Regex("[^a-z0-9]+"))
    .filter(String::isNotBlank)
    .toSet()

private fun JsonElement.recipientEncoding(): NativeMailRecipientEncoding? {
    val schema = this as? JsonObject ?: return null
    val format = (schema["format"] as? JsonPrimitive)?.contentOrNull
    return when (schema.declaredType()) {
        "array" -> when (format) {
            DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT -> {
                val spec = schema.repeatableObjectInputSpec() ?: return null
                val email = spec.fields.singleOrNull { field ->
                    field.id.nativeMailRoleKey() in ADDRESS_KEYS && field.kind == RepeatableObjectInputScalarKind.String
                } ?: return null
                val label = spec.fields.firstOrNull { field ->
                    field.id.nativeMailRoleKey() in LABEL_KEYS && field.kind == RepeatableObjectInputScalarKind.String
                }
                // Any other required item field would need an invented value.
                if (spec.fields.any { it.required && it.id != email.id && it.id != label?.id }) return null
                NativeMailRecipientEncoding.ObjectList(email.id, label?.id)
            }
            DYNAMIC_STRING_ARRAY_FORMAT, DYNAMIC_STRING_LIST_FORMAT -> NativeMailRecipientEncoding.StringList
            else -> null
        }
        "string" -> NativeMailRecipientEncoding.SingleAddress
        else -> null
    }
}

private fun NativeMailRecipientEncoding.encode(addresses: List<NativeMailAddress>): String = when (this) {
    is NativeMailRecipientEncoding.ObjectList -> buildJsonArray {
        addresses.forEach { address ->
            add(
                buildJsonObject {
                    put(emailKey, JsonPrimitive(address.email))
                    if (labelKey != null && !address.label.isNullOrBlank()) put(labelKey, JsonPrimitive(address.label))
                },
            )
        }
    }.toString()
    NativeMailRecipientEncoding.StringList -> addresses.joinToString("\n", transform = NativeMailAddress::email)
    NativeMailRecipientEncoding.SingleAddress -> addresses.single().email
}

private fun JsonElement?.declaredType(): String? = when (val type = (this as? JsonObject)?.get("type")) {
    is JsonPrimitive -> type.contentOrNull
    is JsonArray -> type.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeUnless { t -> t == "null" } }
    else -> null
}

private fun String.sendWords(): Set<String> = lowercase()
    .map { if (it.isLetterOrDigit()) it else ' ' }
    .joinToString("")
    .split(' ')
    .filter(String::isNotBlank)
    .toSet()

private val SEND_INTENTS = setOf(ActionIntent.create, ActionIntent.execute, ActionIntent.update)
private val TRUSTED_SEND_EVIDENCE = setOf(
    EvidenceSource.openApi,
    EvidenceSource.verifiedAdapter,
    EvidenceSource.verifiedAppPackage,
    EvidenceSource.appStoreLinkedSourceTag,
)
private val NON_SEND_WORDS = setOf("draft", "drafts", "test", "preview", "invitation", "invitations", "share", "shares")
private val TO_ROLES = setOf("to", "recipients", "torecipients", "toaddresses", "recipient")
private val CC_ROLES = setOf("cc", "ccrecipients", "ccaddresses")
private val BCC_ROLES = setOf("bcc", "bccrecipients", "bccaddresses")
private val SUBJECT_ROLES = setOf("subject")
private val BODY_ROLES = setOf("body", "bodyplain", "plainbody", "bodytext", "messagebody", "text", "content", "message")
private val HTML_FLAG_ROLES = setOf("ishtml", "html", "bodyishtml")
private val SENDER_ADDRESS_ROLES = setOf("fromemail", "fromaddress", "senderemail", "senderaddress", "from")
private val ACCOUNT_IDENTITY_ROLES = setOf("accountid", "mailaccountid", "account")
private val ALIAS_IDENTITY_ROLES = setOf("identityid", "aliasid", "senderidentityid", "fromidentityid")
private val ACCOUNT_RESOURCE_WORDS = setOf("account", "accounts")
private val IN_REPLY_TO_ROLES = setOf("inreplyto", "inreplytomessageid")
private val REFERENCES_ROLES = setOf("references")
private val ADDRESS_KEYS = setOf("email", "address", "mail", "emailaddress")
private val LABEL_KEYS = setOf("label", "name", "displayname")
private const val MAX_SEND_RECIPIENTS = 100
private const val MAX_SUBJECT_LENGTH = 998
