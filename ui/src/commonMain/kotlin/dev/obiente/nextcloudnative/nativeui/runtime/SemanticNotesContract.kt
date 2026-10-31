package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticRouteShape.Token

/** Semantic roles of note fields, matched by name rather than by app identity. */
internal enum class NoteField { Title, Content, Category, Favorite, Modified }

internal enum class NoteOperation(val write: Boolean) {
    ListNotes(false), ReadNote(false), CreateNote(true), UpdateNote(true), DeleteNote(true),
}

/** One contract action chosen for a note operation, with its declared inputs mapped to roles. */
internal data class NoteOperationBinding(
    val operation: NoteOperation,
    val action: DynamicAction,
    /** The single path placeholder that carries the note identity, when the route has one. */
    val identityParameter: String?,
    val bodyFields: Map<NoteField, String>,
    /** Declared optional query inputs whose name says they omit note bodies from a list. */
    val excludeContentQuery: Pair<String, String>?,
)

/**
 * Note operations an installed app's own contract describes.
 *
 * A collection is note-shaped when its records carry a title and a text body, and either the
 * contract words it as notes or its records also carry note metadata (folder, favorite, or
 * modification time). There is no app identifier or version check.
 */
internal data class SemanticNotesContract(
    val descriptor: DynamicAppDescriptor,
    val operations: Map<NoteOperation, NoteOperationBinding>,
    val settings: SemanticSettingsContract?,
) {
    fun supports(operation: NoteOperation): Boolean = operation in operations

    fun binding(operation: NoteOperation): NoteOperationBinding = operations[operation]
        ?: throw IllegalStateException("This app does not describe how to ${operation.describe()}.")

    fun canWrite(field: NoteField): Boolean = operations[NoteOperation.UpdateNote]?.bodyFields?.containsKey(field) == true
}

internal fun resolveSemanticNotesContract(
    descriptor: DynamicAppDescriptor,
    writesAuthorized: Boolean,
): SemanticNotesContract? {
    val collections = descriptor.semanticRoutes()
        .mapNotNull { (action, shape) -> shape.noteRoute()?.let { Triple(action, shape, it) } }
        .groupBy { (_, _, route) -> route.collection }
    return collections.values.firstNotNullOfOrNull { routes ->
        val operations = routes.mapNotNull { (action, shape, route) ->
            classifyNoteRoute(action, route, writesAuthorized)?.let { it to (action to shape) }
        }.groupBy { (binding, _) -> binding.operation }
            .mapValues { (_, matches) ->
                matches.sortedWith { left, right -> semanticRoutePreference.compare(left.second, right.second) }
                    .first().first
            }
        val list = operations[NoteOperation.ListNotes] ?: return@firstNotNullOfOrNull null
        if (NoteOperation.ReadNote !in operations) return@firstNotNullOfOrNull null
        val collection = routes.first().third.collection
        val evidence = (
            list.action.responseFieldIds +
                operations[NoteOperation.ReadNote]?.action?.responseFieldIds.orEmpty() +
                descriptor.resources.filter { it.id == list.action.resourceId }.flatMap { r -> r.fields.map { it.id } } +
                // Declared request fields describe the record shape even while writes are not
                // authorized, so an unverified version keeps its readable notes surface.
                routes.flatMap { (action, _, _) -> action.declaredBodyProperties().keys }
            ).mapTo(mutableSetOf(), String::semanticRouteId)
        val hasBody = evidence.any { it in NOTE_FIELD_NAMES.getValue(NoteField.Content) }
        val hasTitle = evidence.any { it in NOTE_FIELD_NAMES.getValue(NoteField.Title) }
        val metadata = listOf(NoteField.Category, NoteField.Favorite, NoteField.Modified).count { field ->
            evidence.any { it in NOTE_FIELD_NAMES.getValue(field) }
        }
        val worded = collection in NOTE_WORDS
        if (!hasBody || !(worded || hasTitle && metadata >= 2)) return@firstNotNullOfOrNull null
        SemanticNotesContract(
            descriptor = descriptor,
            operations = operations,
            settings = resolveSemanticSettingsContract(descriptor, writesAuthorized),
        )
    }
}

private data class NoteRoute(val collection: String, val identity: String?)

/** `/{collection}` or `/{collection}/{identity}` after the app mount, and nothing more. */
private fun SemanticRouteShape.noteRoute(): NoteRoute? {
    val word = tokens.firstOrNull() as? Token.Word ?: return null
    if (word.word in NOTE_EXCLUDED_COLLECTIONS) return null
    return when (tokens.size) {
        1 -> NoteRoute(word.word, null)
        2 -> (tokens[1] as? Token.Placeholder)
            ?.takeIf { it.normalized == "id" || it.normalized.semanticIdentityStem()?.let { stem -> word.word.startsWith(stem) } == true }
            ?.let { NoteRoute(word.word, it.name) }
        else -> null
    }
}

private fun classifyNoteRoute(
    action: DynamicAction,
    route: NoteRoute,
    writesAuthorized: Boolean,
): NoteOperationBinding? {
    val method = action.binding.method
    val readOnly = action.isSemanticRead() && action.risk == ActionRisk.readOnly
    if (!readOnly && !(action.risk != ActionRisk.readOnly && action.isAuthorizedSemanticWrite(writesAuthorized))) {
        return null
    }
    if (action.binding.queryParameters.any { it.required }) return null
    val operation = when {
        readOnly && route.identity == null -> NoteOperation.ListNotes
        readOnly -> NoteOperation.ReadNote
        method == HttpMethod.POST && route.identity == null -> NoteOperation.CreateNote
        method in setOf(HttpMethod.PUT, HttpMethod.PATCH) && route.identity != null -> NoteOperation.UpdateNote
        method == HttpMethod.DELETE && route.identity != null -> NoteOperation.DeleteNote
        else -> return null
    }
    val properties = action.declaredBodyProperties()
    val bodyFields = NoteField.entries.mapNotNull { field ->
        properties.keys.firstOrNull { it.semanticRouteId() in NOTE_FIELD_NAMES.getValue(field) }?.let { field to it }
    }.toMap()
    val required = action.requiredBodyProperties()
    if (required.any { name -> name !in bodyFields.values }) return null
    if (operation == NoteOperation.CreateNote && NoteField.Content !in bodyFields && NoteField.Title !in bodyFields) {
        return null
    }
    if (operation == NoteOperation.UpdateNote && NoteField.Content !in bodyFields) return null
    val exclude = action.binding.queryParameters.firstOrNull { parameter ->
        parameter.name.semanticRouteId() == "exclude"
    }?.let { it.name to "content" }.takeIf { operation == NoteOperation.ListNotes }
    return NoteOperationBinding(operation, action, route.identity, bodyFields, exclude)
}

private fun NoteOperation.describe(): String = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()

internal val NOTE_FIELD_NAMES: Map<NoteField, Set<String>> = mapOf(
    NoteField.Title to setOf("title", "name"),
    NoteField.Content to setOf("content", "body", "text", "markdown"),
    NoteField.Category to setOf("category", "folder", "notebook"),
    NoteField.Favorite to setOf("favorite", "favourite", "starred", "pinned"),
    NoteField.Modified to setOf("modified", "updatedat", "lastmodified", "mtime"),
)

private val NOTE_WORDS = setOf("notes", "note", "memos", "memo")
private val NOTE_EXCLUDED_COLLECTIONS = setOf(
    "settings", "setting", "config", "configuration", "preferences", "capabilities", "status",
)
