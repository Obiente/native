package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adapts descriptor v1 into the current presentation schema.
 *
 * Execution must retain [DynamicAction]: NativeAppSchema 0.1 intentionally cannot carry query,
 * body content type, authentication, permission, or OCS envelope metadata.
 */
fun DynamicAppDescriptor.toNativeAppSchema(): NativeAppSchema {
    requireValid()
    val formFields = forms.groupBy(DynamicForm::resourceId)
        .mapValues { (_, resourceForms) -> resourceForms.flatMap(DynamicForm::fields) }
    val nativeResources = resources.map { resource ->
        val fields = resource.fields.map(DynamicField::toNativeField).toMutableList()
        formFields[resource.id].orEmpty().forEach { input ->
            val index = fields.indexOfFirst { field -> field.id == input.fieldId }
            if (index < 0) {
                fields += input.toNativeField()
            } else {
                val existing = fields[index]
                fields[index] = existing.copy(
                    readOnly = false,
                    format = input.format ?: existing.format,
                    enumValues = input.enumValues ?: existing.enumValues,
                    enumLabels = input.enumLabels ?: existing.enumLabels,
                    repeatableObjectInput = input.repeatableObjectInput ?: existing.repeatableObjectInput,
                )
            }
        }
        ResourceSpec(
            id = resource.id,
            name = resource.label,
            confidence = resource.confidence,
            fields = fields,
            evidence = resource.provenance.map(Provenance::toEvidence),
            recordImagePreview = resource.recordImagePreview?.let { preview ->
                RecordImagePreviewSpec(
                    actionId = preview.actionId,
                    declaredContentTypes = preview.declaredContentTypes,
                )
            },
        )
    }
    val nativeViews = buildList {
        layouts.forEach { layout ->
            val resource = resources.first { it.id == layout.resourceId }
            val sourceAction = actions.firstOrNull { it.id == layout.sourceActionId }
            add(
                ViewSpec(
                    id = layout.id,
                    title = layout.title,
                    resourceId = layout.resourceId,
                    component = layout.toNativeComponent(
                        resource,
                        sourceAction,
                        hasWritableAction = actions.any { candidate ->
                            !candidate.fallbackOnly && candidate.resourceId == resource.id &&
                                candidate.intent == ActionIntent.update && candidate.risk == ActionRisk.mutating
                        },
                    ),
                    sourceActionId = layout.sourceActionId.orEmpty(),
                    confidence = layout.confidence,
                    evidence = layout.provenance.map(Provenance::toEvidence),
                ),
            )
        }
        forms.forEach { form ->
            add(
                ViewSpec(
                    id = form.id,
                    title = form.title,
                    resourceId = form.resourceId,
                    component = NativeComponent.form,
                    sourceActionId = form.actionId,
                    confidence = form.confidence,
                    evidence = form.provenance.map(Provenance::toEvidence),
                ),
            )
        }
        addAll(compositeDataGridViews())
    }
    val formsByActionId = forms.associateBy(DynamicForm::actionId)
    val nativeActions = actions.filterNot(DynamicAction::fallbackOnly).map { action ->
        ActionSpec(
            id = action.id,
            label = action.label,
            resourceId = action.resourceId,
            binding = ApiBinding(
                method = action.binding.method,
                path = action.binding.path,
                operationId = action.id,
                pathParameterNames = action.binding.pathParameters.map(HttpParameter::name),
                requiredPathParameterNames = action.binding.pathParameters.filter(HttpParameter::required)
                    .map(HttpParameter::name),
                queryParameterNames = action.binding.queryParameters.map(HttpParameter::name),
                requiredQueryParameterNames = action.binding.queryParameters.filter(HttpParameter::required)
                    .map(HttpParameter::name),
                bodyFieldNames = action.binding.body?.schema?.objectPropertyNames().orEmpty(),
                requiredBodyFieldNames = action.binding.body?.schema?.requiredPropertyNames().orEmpty(),
                bodyContentType = action.binding.body?.contentType,
                bodySchema = action.binding.body?.schema,
                allowsObservedBodyFields = (action.binding.body?.schema as? JsonObject)
                    ?.get("x-nextcloud-native-observed-settings-body")
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull == "true",
            ),
            intent = action.intent,
            risk = action.risk,
            requiresConfirmation = action.requiresConfirmation,
            confidence = action.confidence,
            inputSchema = formsByActionId[action.id]?.toNativeInputSchema(),
            evidence = action.provenance.map(Provenance::toEvidence),
            effect = action.effect,
            resultRecoveryActionId = action.resultRecoveryActionId,
        )
    }
    val linkedRelationships = links.mapNotNull { link ->
        val target = link.target as? DynamicLinkTarget.Action ?: return@mapNotNull null
        val childResourceId = actions.firstOrNull { it.id == target.actionId }?.resourceId ?: return@mapNotNull null
        if (childResourceId == link.resourceId) return@mapNotNull null
        val parentResource = resources.firstOrNull { it.id == link.resourceId } ?: return@mapNotNull null
        val childResource = resources.firstOrNull { it.id == childResourceId } ?: return@mapNotNull null
        ResourceRelationshipSpec(
            parentResourceId = parentResource.id,
            childResourceId = childResource.id,
            parentFieldId = link.sourceFieldId,
            childFieldId = childResource.fields.firstOrNull { field ->
                field.id.foreignKeyBase() in setOf(parentResource.id.relationBase(), parentResource.label.relationBase())
            }?.id,
            confidence = link.confidence,
        )
    }
    val inferredForeignKeyRelationships = resources.flatMap { childResource ->
        childResource.fields.mapNotNull { childField ->
            val parentBase = childField.id.foreignKeyBase() ?: return@mapNotNull null
            val parentResource = resources.filter { candidate ->
                parentBase in setOf(
                    candidate.id.relationBase(),
                    candidate.label.relationBase(),
                )
            }.singleOrNull() ?: return@mapNotNull null
            val parentIdentity = parentResource.fields
                .filter { field -> field.id.lowercase() in setOf("databaseid", "id", "uuid", "token") }
                .minByOrNull { field ->
                    when (field.id.lowercase()) {
                        "databaseid" -> 0
                        "id" -> 1
                        "uuid" -> 2
                        else -> 3
                    }
                } ?: return@mapNotNull null
            ResourceRelationshipSpec(
                parentResourceId = parentResource.id,
                childResourceId = childResource.id,
                parentFieldId = parentIdentity.id,
                childFieldId = childField.id,
                // Name inference may support labels and relation choices, but it cannot by itself
                // become verified evidence that authorizes an automatic write binding.
                confidence = minOf(
                    Confidence.high,
                    parentResource.confidence,
                    childResource.confidence,
                    parentIdentity.confidence,
                    childField.confidence,
                ),
            )
        }
    }
    val nativeRelationships = (linkedRelationships + inferredForeignKeyRelationships).distinctBy { relationship ->
        listOf(
            relationship.parentResourceId,
            relationship.childResourceId,
            relationship.parentFieldId,
            relationship.childFieldId.orEmpty(),
        )
    }
    return NativeAppSchema(
        schemaVersion = "0.1",
        app = app,
        confidence = resources.minOfOrNull(DynamicResource::confidence) ?: Confidence.low,
        resources = nativeResources,
        views = nativeViews,
        actions = nativeActions,
        relationships = nativeRelationships,
        warnings = buildList {
            addAll(warnings.map { CompilerWarning(it.code, it.message) })
            if (actions.any { action ->
                    action.binding.pathParameters.isNotEmpty() ||
                        action.binding.queryParameters.isNotEmpty() ||
                        action.binding.body != null ||
                        action.binding.auth.isNotEmpty() ||
                        action.binding.ocs != null
                }
            ) {
                add(
                    CompilerWarning(
                        code = "dynamic-executor-required",
                        message = "Execute actions from DynamicAppDescriptor v1 so parameter, auth, permission and OCS metadata is preserved",
                    ),
                )
            }
        },
    )
}

private fun DynamicAppDescriptor.compositeDataGridViews(): List<ViewSpec> {
    val actionsById = actions.associateBy(DynamicAction::id)
    val resourcesById = resources.associateBy(DynamicResource::id)
    val linkedReads = links.mapNotNull { link ->
        val target = link.target as? DynamicLinkTarget.Action ?: return@mapNotNull null
        val action = actionsById[target.actionId]?.takeIf {
            it.binding.method == HttpMethod.GET && it.intent == ActionIntent.list && it.risk == ActionRisk.readOnly
        } ?: return@mapNotNull null
        val child = resourcesById[action.resourceId] ?: return@mapNotNull null
        CompositeLinkedRead(link.resourceId, action, child)
    }
    return linkedReads.groupBy(CompositeLinkedRead::parentResourceId).mapNotNull { (parentResourceId, reads) ->
        val columns = reads.mapNotNull { read -> read.resource.columnDefinitionShape()?.let { read to it } }
        val rows = reads.mapNotNull { read -> read.resource.rowCellMapField()?.let { read to it } }
        if (columns.size != 1 || rows.size != 1) return@mapNotNull null
        val (columnRead, columnShape) = columns.single()
        val (rowRead, rowCellField) = rows.single()
        if (columnRead.action.id == rowRead.action.id || columnRead.resource.id == rowRead.resource.id) {
            return@mapNotNull null
        }
        ViewSpec(
            id = "$parentResourceId.table",
            title = "Table",
            resourceId = rowRead.resource.id,
            component = NativeComponent.dataTable,
            sourceActionId = rowRead.action.id,
            confidence = minOf(columnRead.resource.confidence, rowRead.resource.confidence),
            evidence = (columnRead.resource.provenance + rowRead.resource.provenance).map(Provenance::toEvidence),
            compositeDataGrid = CompositeDataGridSpec(
                parentResourceId = parentResourceId,
                columnResourceId = columnRead.resource.id,
                rowResourceId = rowRead.resource.id,
                columnSourceActionId = columnRead.action.id,
                rowSourceActionId = rowRead.action.id,
                columnIdentityFieldId = columnShape.identityFieldId,
                columnAliasFieldId = columnShape.aliasFieldId,
                columnTitleFieldId = columnShape.titleFieldId,
                columnTypeFieldId = columnShape.typeFieldId,
                columnOrderFieldId = columnShape.orderFieldId,
                rowCellMapFieldId = rowCellField.id,
            ),
        )
    }.sortedBy(ViewSpec::id)
}

private data class CompositeLinkedRead(
    val parentResourceId: String,
    val action: DynamicAction,
    val resource: DynamicResource,
)

private data class ColumnDefinitionShape(
    val identityFieldId: String,
    val aliasFieldId: String?,
    val titleFieldId: String,
    val typeFieldId: String?,
    val orderFieldId: String?,
)

private fun DynamicResource.columnDefinitionShape(): ColumnDefinitionShape? {
    val bySemanticId = fields.associateBy { it.id.semanticId() }
    val identity = listOf("id", "uuid").firstNotNullOfOrNull(bySemanticId::get) ?: return null
    val title = listOf("title", "name", "label").firstNotNullOfOrNull(bySemanticId::get) ?: return null
    val alias = listOf("alias", "technicalname", "key", "slug").firstNotNullOfOrNull(bySemanticId::get)
    val type = listOf("type", "datatype", "kind").firstNotNullOfOrNull(bySemanticId::get)
    val order = listOf("order", "orderweight", "position", "index").firstNotNullOfOrNull(bySemanticId::get)
    if (listOfNotNull(alias, type, order).size < 2) return null
    return ColumnDefinitionShape(identity.id, alias?.id, title.id, type?.id, order?.id)
}

private fun DynamicResource.rowCellMapField(): DynamicField? = fields
    .filter { field -> field.kind == FieldKind.objectValue && field.id.semanticId() in CELL_MAP_FIELD_PREFERENCE }
    .minByOrNull { field -> CELL_MAP_FIELD_PREFERENCE.indexOf(field.id.semanticId()) }

private fun String.semanticId(): String = lowercase().filter(Char::isLetterOrDigit)

private val CELL_MAP_FIELD_PREFERENCE = listOf("databyalias", "cells", "values", "fields", "data")

private fun kotlinx.serialization.json.JsonElement.objectPropertyNames(): List<String> =
    ((this as? kotlinx.serialization.json.JsonObject)?.get("properties") as? kotlinx.serialization.json.JsonObject)
        ?.keys
        ?.toList()
        .orEmpty()

private fun kotlinx.serialization.json.JsonElement.requiredPropertyNames(): List<String> =
    ((this as? kotlinx.serialization.json.JsonObject)?.get("required") as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { value -> (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        .orEmpty()

private fun String.foreignKeyBase(): String? {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return when {
        normalized.length > 3 && normalized.endsWith("ids") -> normalized.dropLast(3).relationBase()
        normalized.length > 2 && normalized.endsWith("id") -> normalized.dropLast(2).relationBase()
        else -> null
    }
}

private fun String.relationBase(): String {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return when {
        normalized.endsWith("ies") && normalized.length > 3 -> normalized.dropLast(3) + "y"
        normalized.endsWith("ses") && normalized.length > 3 -> normalized.dropLast(2)
        normalized.endsWith('s') && normalized.length > 1 -> normalized.dropLast(1)
        else -> normalized
    }
}

private fun DynamicForm.toNativeInputSchema() = buildJsonObject {
    put("properties", buildJsonObject {
        fields.forEach { field -> put(field.fieldId, buildJsonObject {}) }
    })
    put("required", buildJsonArray {
        fields.filter(FormField::required).forEach { field ->
            add(kotlinx.serialization.json.JsonPrimitive(field.fieldId))
        }
    })
}

private fun DynamicField.toNativeField(): FieldSpec = FieldSpec(
    id = id,
    label = label,
    kind = kind,
    required = required,
    readOnly = readOnly,
    format = format,
    enumValues = enumValues,
)

private fun FormField.toNativeField(): FieldSpec = FieldSpec(
    id = fieldId,
    label = label,
    kind = kind,
    required = required,
    readOnly = false,
    format = format,
    enumValues = enumValues,
    enumLabels = enumLabels,
    repeatableObjectInput = repeatableObjectInput,
)

private fun DynamicLayout.toNativeComponent(
    resource: DynamicResource,
    action: DynamicAction?,
    hasWritableAction: Boolean,
): NativeComponent {
    if (kind == LayoutKind.grid) return NativeComponent.mediaGrid

    val words = semanticWords(
        resource.id,
        resource.label,
        id,
        title,
        action?.id.orEmpty(),
        action?.label.orEmpty(),
        action?.binding?.path.orEmpty(),
    )
    val fields = resource.fields.mapTo(mutableSetOf()) { it.id.lowercase() }
    val normalizedFields = resource.fields.associateBy { it.id.lowercase().filter(Char::isLetterOrDigit) }
    val hasCellGridShape = normalizedFields.any { (id, field) ->
        field.kind == FieldKind.objectValue && id in setOf("data", "databyalias", "values", "cells", "fields")
    }
    val hasTitle = fields.any { it in setOf("title", "name", "summary", "subject") }
    val hasBoardGrouping = normalizedFields.keys.any {
        it in setOf("stackid", "stack", "columnid", "column", "laneid", "lane", "listid", "list", "stage", "status")
    }
    val hasBoardOrdering = normalizedFields.keys.any { it in setOf("order", "position", "sortorder", "sort", "index") }
    val hasCompletionShape = normalizedFields.any { (id, field) ->
        id in setOf("completed", "done", "iscompleted", "isdone") &&
            field.kind == FieldKind.boolean
    }
    val hasMeasure = normalizedFields.any { (id, field) ->
        field.kind in setOf(FieldKind.integer, FieldKind.decimal, FieldKind.currency) &&
            id in setOf(
                "amount", "total", "totalspent", "totalexpense", "totalexpenses", "spending", "spent",
                "thismonthexpenses", "thismonthincome",
                "balance", "currentbalance", "availablebalance", "closingbalance", "openingbalance",
                "cost", "price", "value", "expense", "expenses", "income", "revenue",
                "budget", "budgetamount", "budgeted", "accruedinterest", "average", "avgtransaction",
                "creditlimit", "minimum", "minimumpayment", "overdraftlimit",
            )
    }
    val hasFinancialSemantics = words.any {
        it in setOf(
            "account", "accounts", "category", "categories", "finance", "financial",
            "bill", "bills", "expense", "expenses", "payment", "payments", "transaction", "transactions",
            "project", "projects", "spending", "budget", "budgets", "income", "revenue",
        )
    }
    val hasCategoryCollectionSemantics = hasTitle && words.any {
        it == "category" || it == "categories"
    }
    val hasMailboxSemantics = words.any { it in setOf("mail", "mailbox", "mailboxes", "inbox", "outbox", "email", "emails") }
    val hasMessageShape = normalizedFields.keys.any {
        it in setOf("subject", "from", "sender", "to", "recipients", "sentat", "receivedat", "unread", "flags")
    }
    val hasContactSemantics = words.any {
        it in setOf("contact", "contacts", "addressbook", "addressbooks")
    }
    val hasContactShape = normalizedFields.keys.any {
        it in setOf("fn", "formattedname", "email", "emails", "tel", "phone", "telephone", "org", "organization")
    }
    val hasMediaLibrarySemantics = words.any {
        it in setOf(
            "music", "audio", "artist", "artists", "album", "albums", "track", "tracks",
            "song", "songs", "playlist", "playlists", "genre", "genres", "radio", "podcast", "podcasts",
        )
    }
    val hasMediaLibraryShape = normalizedFields.keys.any {
        it in setOf(
            "artist", "artistname", "album", "albumname", "track", "tracknumber", "duration",
            "bitrate", "cover", "coverurl", "playcount", "genre", "genres",
        )
    }
    val hasSettingsSemantics = words.any {
        it in setOf("setting", "settings", "preference", "preferences", "configuration", "config")
    }
    val hasDocumentSemantics = words.any {
        it in setOf(
            "document", "documents", "editor", "note", "notes", "office", "richdocuments",
            "collective", "collectives", "markdown", "text",
        )
    }
    val hasDocumentBody = normalizedFields.any { (id, field) ->
        id in setOf("body", "content", "document", "markdown", "text", "html") &&
            field.kind in setOf(FieldKind.string, FieldKind.longText)
    }
    if (kind == LayoutKind.detail) {
        return if (hasDocumentSemantics && hasDocumentBody && hasWritableAction) {
            NativeComponent.documentEditor
        } else {
            NativeComponent.detail
        }
    }
    val hasFileSemantics = words.any {
        it in setOf("file", "files", "folder", "folders", "directory", "directories")
    }
    val hasFileShape = normalizedFields.keys.any {
        it in setOf("path", "filename", "mimetype", "etag", "filesize", "parentpath")
    }
    val hasActivitySemantics = words.any {
        it in setOf("activity", "activities", "audit", "history", "timeline")
    }
    val hasActivityShape = normalizedFields.keys.any {
        it in setOf("actor", "actorid", "app", "datetime", "message", "objectname", "subject", "timestamp")
    }

    return when {
        hasCellGridShape ||
            words.any { it in setOf("row", "rows") } ||
            ("columnid" in fields && fields.any { it in setOf("data", "databyalias", "value") }) -> {
            NativeComponent.dataTable
        }
        words.any { it in setOf("board", "boards") } ||
            words.any { it in setOf("card", "cards") } &&
            fields.any { it in setOf("boardid", "stackid", "order", "position") } -> {
            NativeComponent.board
        }
        hasTitle && hasCompletionShape -> NativeComponent.taskList
        hasTitle && hasBoardGrouping && hasBoardOrdering -> NativeComponent.board
        hasCategoryCollectionSemantics -> NativeComponent.collectionList
        hasMeasure && hasFinancialSemantics -> NativeComponent.dashboard
        hasSettingsSemantics && action?.binding?.method == HttpMethod.GET -> NativeComponent.detail
        hasActivitySemantics && hasActivityShape -> NativeComponent.timeline
        hasFileSemantics && hasFileShape -> NativeComponent.fileBrowser
        hasMailboxSemantics &&
            (words.any { it in setOf("account", "accounts", "mailbox", "mailboxes", "message", "messages") } ||
                hasMessageShape || resource.fields.isEmpty()) -> NativeComponent.mailbox
        hasMediaLibrarySemantics &&
            (hasMediaLibraryShape || words.any {
                it in setOf(
                    "artist", "artists", "album", "albums", "track", "tracks", "song", "songs",
                    "playlist", "playlists", "genre", "genres", "radio", "podcast", "podcasts", "collection",
                )
            }) -> NativeComponent.mediaLibrary
        hasContactSemantics && hasContactShape -> NativeComponent.contactList
        words.any { it in setOf("task", "tasks", "todo", "todos") } ||
            hasTitle && fields.any { it in setOf("completed", "done", "status") } &&
            fields.any { it in setOf("due", "duedate", "start", "startdate") } -> {
            NativeComponent.taskList
        }
        "calendar" in words || "calendars" in words ||
            words.any { it in setOf("event", "events") } && hasTitle &&
            fields.any { it in setOf("start", "startdate", "dtstart") } -> {
            NativeComponent.calendar
        }
        words.any { it in setOf("message", "messages", "chat", "thread") } &&
            fields.any { it in setOf("body", "content", "message", "text") } -> {
            NativeComponent.chatThread
        }
        words.any { it in setOf("conversation", "conversations", "room", "rooms", "talk") } &&
            fields.any { it in setOf("displayname", "lastmessage", "name", "token", "unreadmessages") } -> {
            NativeComponent.conversationList
        }
        words.any { it in setOf("recipe", "recipes", "cookbook") } &&
            (hasTitle || fields.any { it in setOf("ingredients", "instructions") }) -> {
            NativeComponent.recipeList
        }
        words.any { it in setOf("gallery", "image", "images", "media", "memories", "photo", "photos") } &&
            (
                resource.recordImagePreview != null ||
                    resource.fields.any { it.kind == FieldKind.image || it.kind == FieldKind.file }
                ) -> {
            NativeComponent.mediaGrid
        }
        resource.fields.any { it.kind == FieldKind.image } -> NativeComponent.mediaGrid
        else -> NativeComponent.collectionList
    }
}

private fun semanticWords(vararg values: String): Set<String> = values
    .flatMap { value -> value.lowercase().split(Regex("[^a-z0-9]+")) }
    .filter(String::isNotBlank)
    .toSet()

private fun Provenance.toEvidence(): Evidence = Evidence(
    source = when (kind) {
        ProvenanceKind.appMetadata -> EvidenceSource.appMetadata
        ProvenanceKind.capability -> EvidenceSource.capability
        ProvenanceKind.advertisedOpenApi -> EvidenceSource.openApi
        ProvenanceKind.successfulReadObservation -> EvidenceSource.networkObservation
        ProvenanceKind.verifiedAdapter -> EvidenceSource.verifiedAdapter
        ProvenanceKind.verifiedAppPackage -> EvidenceSource.verifiedAppPackage
        ProvenanceKind.appStoreLinkedSourceTag -> EvidenceSource.appStoreLinkedSourceTag
        ProvenanceKind.deterministicInference -> EvidenceSource.localInference
    },
    detail = "$source: $detail",
)
