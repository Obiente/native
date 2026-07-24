package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal enum class NativeMailMessageActionKind {
    MarkRead,
    MarkUnread,
    Flag,
    Unflag,
    Archive,
    Delete,
}

internal data class NativeMailMessageActionPlan(
    val kind: NativeMailMessageActionKind,
    val label: String,
    val action: ActionSpec,
    private val values: Map<String, String>,
) {
    fun request(): NativeActionRequest.Submit = NativeActionRequest.Submit(
        action = action,
        values = values,
        confirmed = true,
    )
}

internal data class NativeMailMessageActionSet(
    val stateActions: List<NativeMailMessageActionPlan>,
    val archive: NativeMailMessageActionPlan?,
    val delete: NativeMailMessageActionPlan? = null,
) {
    val all: List<NativeMailMessageActionPlan>
        get() = stateActions + listOfNotNull(archive, delete)
}

/**
 * Plans message mutations from contract shape, semantic field names, and already loaded records.
 *
 * There is deliberately no app identifier here. A mail client, support inbox, notification center,
 * or future Nextcloud app gets the same actions when it exposes an exact message identity and a
 * uniquely bindable mutation. Equal-ranked actions and ambiguous archive destinations are omitted.
 */
internal fun nativeMailMessageActionPlan(
    schema: NativeAppSchema,
    displayedResource: ResourceSpec,
    displayedRecord: NativeRecord,
    context: NativeDatasetContext = NativeDatasetContext(),
): NativeMailMessageActionSet {
    val target = resolveMailActionTarget(schema, displayedResource, displayedRecord, context)
        ?: return NativeMailMessageActionSet(emptyList(), null)
    val presentation = nativeMailboxPresentation(target.resource, target.record)
    val candidates = schema.actions.filter { action ->
        action.resourceId.sameMailActionResource(target.resource.id) &&
            action.risk in setOf(ActionRisk.mutating, ActionRisk.destructive) &&
            action.binding.method in MAIL_MUTATION_METHODS &&
            action.intent in MAIL_MUTATION_INTENTS
    }
    val read = candidates.mapNotNull { action ->
        val bindingValues = target.record.mailActionBindingValues(action) ?: return@mapNotNull null
        val kind = if (presentation.unread) {
            NativeMailMessageActionKind.MarkRead
        } else {
            NativeMailMessageActionKind.MarkUnread
        }
        val label = if (presentation.unread) "Mark read" else "Mark unread"
        action.toBooleanStateCandidate(
            bindingValues = bindingValues,
            desiredState = presentation.unread,
            directFieldNames = MAIL_READ_STATE_FIELDS,
            kind = kind,
            label = label,
        ) ?: action.toFlagsMapStateCandidate(
            bindingValues = bindingValues,
            flagName = "seen",
            desiredState = presentation.unread,
            kind = kind,
            label = label,
        )
    }.singleHighestMailPlanOrNull()
    val flag = candidates.mapNotNull { action ->
        val bindingValues = target.record.mailActionBindingValues(action) ?: return@mapNotNull null
        val kind = if (presentation.flagged) NativeMailMessageActionKind.Unflag else NativeMailMessageActionKind.Flag
        val label = if (presentation.flagged) "Unflag" else "Flag"
        action.toBooleanStateCandidate(
            bindingValues = bindingValues,
            desiredState = !presentation.flagged,
            directFieldNames = MAIL_FLAG_STATE_FIELDS,
            kind = kind,
            label = label,
        ) ?: action.toFlagsMapStateCandidate(
            bindingValues = bindingValues,
            flagName = "flagged",
            desiredState = !presentation.flagged,
            kind = kind,
            label = label,
        )
    }.singleHighestMailPlanOrNull()
    val archiveDestination = context.uniqueArchiveMailboxId(schema, target.record)
    val archive = archiveDestination?.let { destination ->
        candidates.mapNotNull { action ->
            action.toArchiveCandidate(
                target.record.mailActionBindingValues(action) ?: return@mapNotNull null,
                destination,
            )
        }.singleHighestMailPlanOrNull()
    }
    val delete = candidates.mapNotNull { action ->
        action.toDeleteCandidate(
            target.record.mailActionBindingValues(action) ?: return@mapNotNull null,
        )
    }.singleHighestMailPlanOrNull()
    return NativeMailMessageActionSet(
        stateActions = listOfNotNull(read, flag),
        archive = archive,
        delete = delete,
    )
}

private data class MailActionTarget(
    val resource: ResourceSpec,
    val record: NativeRecord,
)

private fun resolveMailActionTarget(
    schema: NativeAppSchema,
    displayedResource: ResourceSpec,
    displayedRecord: NativeRecord,
    context: NativeDatasetContext,
): MailActionTarget? {
    val parent = context.parentResourceId
        ?.let(schema::resource)
        ?.let { resource -> context.parentRecord?.let { record -> MailActionTarget(resource, record) } }
        ?.takeIf { target ->
            nativeMailboxPresentation(target.resource, target.record).kind == NativeMailboxItemKind.Message
        }
    if (parent != null) return parent
    return MailActionTarget(displayedResource, displayedRecord).takeIf { target ->
        nativeMailboxPresentation(target.resource, target.record).kind == NativeMailboxItemKind.Message
    }
}

/**
 * A signed static item mutation can safely bind the conventional numeric `databaseId` observed on
 * a same-resource record even when the sparse read schema omitted that field. This proof remains
 * local to the exact action: the record is not globally promoted to mutation-safe, and arbitrary
 * observed `id`, UUID, name, or protocol identifiers are never accepted.
 */
private fun NativeRecord.mailActionBindingValues(action: ActionSpec): Map<String, String>? {
    if (actionSafeIdentity) return actionBindingValues(allowUnsafeIdentity = true)
    if (!canResolveUnsafeActionIdentity()) return null
    if (action.evidence.none { evidence ->
            evidence.source in setOf(EvidenceSource.verifiedAppPackage, EvidenceSource.appStoreLinkedSourceTag)
        }
    ) return null
    if (
        action.binding.requiredPathParameterNames.size != 1 ||
        action.binding.requiredPathParameterNames.single().mailSemanticId() != "id"
    ) return null
    val databaseId = displayValues.entries
        .singleOrNull { (key, _) -> key.mailSemanticId() == "databaseid" }
        ?.value
        ?.takeIf { value -> value.toLongOrNull()?.let { it > 0 } == true }
        ?: return null
    return actionBindingValues(allowUnsafeIdentity = true) + ("id" to databaseId)
}

private data class RankedMailPlan(
    val plan: NativeMailMessageActionPlan,
    val rank: Int,
)

private fun ActionSpec.toBooleanStateCandidate(
    bindingValues: Map<String, String>,
    desiredState: Boolean,
    directFieldNames: Set<String>,
    kind: NativeMailMessageActionKind,
    label: String,
): RankedMailPlan? {
    val matchingFields = binding.bodyFieldNames.filter { it.mailSemanticId() in directFieldNames }
    val fieldName = matchingFields.singleOrNull() ?: return null
    val semantic = "$id $label ${binding.operationId} ${binding.path} $fieldName".mailSemanticWords()
    if (semantic.none { it in MAIL_STATE_ACTION_WORDS }) return null
    val wireState = when (fieldName.mailSemanticId()) {
        "unread", "isunread", "unseen" -> (!desiredState).toString()
        else -> desiredState.toString()
    }
    val available = bindingValues.keys + fieldName
    if (!binding.canResolveRequiredMailValues(available)) return null
    val rank = when {
        "mark" in semantic -> 500
        "set" in semantic -> 450
        "update" in semantic -> 400
        "toggle" in semantic -> 350
        else -> 250
    }
    return RankedMailPlan(
        plan = NativeMailMessageActionPlan(
            kind = kind,
            label = label,
            action = this,
            values = bindingValues + (fieldName to wireState),
        ),
        rank = rank,
    )
}

private fun ActionSpec.toArchiveCandidate(
    bindingValues: Map<String, String>,
    archiveMailboxId: String,
): RankedMailPlan? {
    val semantic = "$id $label ${binding.operationId} ${binding.path}".mailSemanticWords()
    if (semantic.none { it in MAIL_MOVE_ACTION_WORDS }) return null
    val destinationFields = binding.bodyFieldNames.filter { it.mailSemanticId() in MAIL_DESTINATION_FIELDS }
    val destinationField = destinationFields.singleOrNull() ?: return null
    val available = bindingValues.keys + destinationField
    if (!binding.canResolveRequiredMailValues(available)) return null
    val rank = when {
        "archive" in semantic -> 600
        "move" in semantic -> 550
        "relocate" in semantic -> 500
        else -> 300
    }
    return RankedMailPlan(
        plan = NativeMailMessageActionPlan(
            kind = NativeMailMessageActionKind.Archive,
            label = "Archive",
            action = this,
            values = bindingValues + (destinationField to archiveMailboxId),
        ),
        rank = rank,
    )
}

private fun ActionSpec.toFlagsMapStateCandidate(
    bindingValues: Map<String, String>,
    flagName: String,
    desiredState: Boolean,
    kind: NativeMailMessageActionKind,
    label: String,
): RankedMailPlan? {
    val fieldName = binding.bodyFieldNames
        .filter { field -> field.mailSemanticId() in setOf("flags", "flagchanges", "states") }
        .singleOrNull()
        ?: return null
    val semantic = "$id $label ${binding.operationId} ${binding.path} $fieldName".mailSemanticWords()
    if (semantic.none { it in MAIL_STATE_ACTION_WORDS } || "flags" !in semantic && "flag" !in semantic) return null
    if (!binding.canResolveRequiredMailValues(bindingValues.keys + fieldName)) return null
    return RankedMailPlan(
        plan = NativeMailMessageActionPlan(
            kind = kind,
            label = label,
            action = this,
            values = bindingValues + (fieldName to """{"$flagName":$desiredState}"""),
        ),
        rank = 650,
    )
}

private fun ActionSpec.toDeleteCandidate(bindingValues: Map<String, String>): RankedMailPlan? {
    if (binding.method != HttpMethod.DELETE || intent != ActionIntent.delete || risk != ActionRisk.destructive) {
        return null
    }
    val semantic = "$id $label ${binding.operationId} ${binding.path}".mailSemanticWords()
    if (semantic.none { it in MAIL_DELETE_ACTION_WORDS }) return null
    if (!binding.canResolveRequiredMailValues(bindingValues.keys)) return null
    return RankedMailPlan(
        plan = NativeMailMessageActionPlan(
            kind = NativeMailMessageActionKind.Delete,
            label = "Delete",
            action = this,
            values = bindingValues,
        ),
        rank = when {
            "delete" in semantic -> 600
            "destroy" in semantic -> 550
            else -> 500
        },
    )
}

private fun NativeDatasetContext.uniqueArchiveMailboxId(
    schema: NativeAppSchema,
    message: NativeRecord,
): String? {
    val accountId = message.valueForMailName("accountId") ?: message.valueForMailName("mailboxId")?.let { mailboxId ->
        relatedRecords.values.asSequence()
            .flatten()
            .filter { record ->
                record.id == mailboxId || record.valueForMailName("databaseId") == mailboxId
            }
            .mapNotNull { record -> record.valueForMailName("accountId") }
            .distinct()
            .singleOrNull()
    }
    val candidates = buildSet {
        relatedRecords.forEach { (resourceId, records) ->
            val resource = schema.resource(resourceId)
            records.forEach recordLoop@ { record ->
                val recordAccountId = record.valueForMailName("accountId")
                val directArchiveId = record.valueForMailName("archiveMailboxId")
                val effectiveAccountId = recordAccountId ?: directArchiveId?.let {
                    record.valueForMailName("id") ?: record.id
                }
                if (accountId != null && effectiveAccountId != null && effectiveAccountId != accountId) {
                    return@recordLoop
                }
                directArchiveId
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
                if (
                    resource != null &&
                    nativeMailboxPresentation(resource, record).kind == NativeMailboxItemKind.Folder &&
                    record.isArchiveMailbox()
                ) {
                    record.valueForMailName("databaseId")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::add)
                        ?: record.id.takeIf { record.canResolveUnsafeActionIdentity() }?.let(::add)
                }
            }
        }
    }
    return candidates.singleOrNull()
}

private fun NativeRecord.isArchiveMailbox(): Boolean {
    val semanticValues = (values + displayValues).entries
        .filter { (key, _) ->
            key.mailSemanticId() in setOf("specialuse", "specialrole", "name", "displayname")
        }
        .mapNotNull { (_, value) -> value }
        .flatMap(String::mailSemanticWords)
        .toSet()
    return "archive" in semanticValues || "archives" in semanticValues
}

private fun dev.obiente.nextcloudnative.nativeui.model.ApiBinding.canResolveRequiredMailValues(
    available: Set<String>,
): Boolean {
    val normalized = available.mapTo(mutableSetOf(), String::mailSemanticId)
    fun String.resolvable(): Boolean {
        val name = mailSemanticId()
        return name in normalized || name.endsWith("id") && "id" in normalized
    }
    return requiredPathParameterNames.all(String::resolvable) &&
        requiredQueryParameterNames.all(String::resolvable) &&
        requiredBodyFieldNames.all(String::resolvable)
}

private fun List<RankedMailPlan>.singleHighestMailPlanOrNull(): NativeMailMessageActionPlan? {
    val highest = maxOfOrNull(RankedMailPlan::rank) ?: return null
    return filter { it.rank == highest }.singleOrNull()?.plan
}

private fun NativeRecord.valueForMailName(name: String): String? =
    values.entries.firstOrNull { it.key.mailSemanticId() == name.mailSemanticId() }?.value
        ?: displayValues.entries.firstOrNull { it.key.mailSemanticId() == name.mailSemanticId() }?.value

private fun String.mailSemanticWords(): Set<String> = lowercase()
    .map { if (it.isLetterOrDigit()) it else ' ' }
    .joinToString("")
    .split(' ')
    .filter(String::isNotBlank)
    .toSet()

private fun String.mailSemanticId(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.sameMailActionResource(other: String): Boolean {
    fun String.singular(): String = mailSemanticId().let {
        when {
            it.endsWith("ies") && it.length > 3 -> it.dropLast(3) + "y"
            it.endsWith("ses") || it.endsWith("xes") || it.endsWith("zes") -> it.dropLast(2)
            it.endsWith('s') && !it.endsWith("ss") && it.length > 1 -> it.dropLast(1)
            else -> it
        }
    }
    return singular() == other.singular()
}

private val MAIL_MUTATION_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
private val MAIL_MUTATION_INTENTS =
    setOf(ActionIntent.create, ActionIntent.update, ActionIntent.execute, ActionIntent.delete)
private val MAIL_READ_STATE_FIELDS = setOf("seen", "read", "isread", "unread", "isunread", "unseen")
private val MAIL_FLAG_STATE_FIELDS = setOf("flagged", "starred", "favorite", "favourite")
private val MAIL_STATE_ACTION_WORDS =
    setOf("mark", "set", "update", "toggle", "seen", "read", "unread", "flag", "flags", "flagged")
private val MAIL_MOVE_ACTION_WORDS = setOf("archive", "move", "relocate")
private val MAIL_DELETE_ACTION_WORDS = setOf("delete", "destroy", "remove", "trash")
private val MAIL_DESTINATION_FIELDS = setOf(
    "destfolderid",
    "destmailboxid",
    "destinationfolderid",
    "destinationmailboxid",
    "archivemailboxid",
)
