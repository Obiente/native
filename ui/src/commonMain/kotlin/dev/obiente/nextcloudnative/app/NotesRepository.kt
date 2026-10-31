package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NOTE_FIELD_NAMES
import dev.obiente.nextcloudnative.nativeui.runtime.NoteField
import dev.obiente.nextcloudnative.nativeui.runtime.NoteOperation
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticNotesContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Which note writes the installed app's contract describes. */
internal data class NotesCapabilities(
    val canCreate: Boolean,
    val canUpdate: Boolean,
    val canDelete: Boolean,
    val canRename: Boolean,
    val canFavorite: Boolean,
    val canCategorize: Boolean,
)

/**
 * Typed note reads and writes for one notes-shaped app.
 *
 * The workspace and editor depend only on this boundary; they never build a request or parse a
 * response. Every write carries the note's last known ETag as a precondition.
 */
internal interface NotesRepository {
    /** Scopes caches and recovery so two notes apps on one account never share records. */
    val appId: String
    val capabilities: NotesCapabilities

    suspend fun listNotes(): List<NextcloudNote>
    suspend fun loadNote(noteId: Long): NextcloudNote

    /** Returns [NextcloudNotePresence.Absent] only when an authoritative read answers 404 or 410. */
    suspend fun inspectNotePresence(noteId: Long): NextcloudNotePresence

    suspend fun updateNote(
        noteId: Long,
        content: String,
        category: String,
        favorite: Boolean,
        expectedEtag: String?,
        title: String? = null,
    ): NextcloudNote

    suspend fun createNote(title: String, content: String, category: String): NextcloudNote
    suspend fun deleteNote(noteId: Long, expectedEtag: String?)

    suspend fun renameNoteCategory(oldCategory: String, newCategory: String) {
        val source = normalizeNoteCategory(oldCategory)
        val destination = normalizeNoteCategory(newCategory)
        require(source.isNotEmpty() && destination.isNotEmpty() && source != destination) {
            "Choose a valid destination folder."
        }
        require(!destination.startsWith("$source/")) { "A folder cannot be moved inside itself." }
        executeNoteFolderRename(
            summaries = listNotes(),
            oldCategory = source,
            newCategory = destination,
            loadNote = ::loadNote,
            updateNote = { mutation ->
                val note = mutation.note
                updateNote(
                    noteId = note.id,
                    content = requireNotNull(note.content),
                    category = requireNotNull(mutation.destinationCategory),
                    favorite = note.favorite,
                    expectedEtag = requireNotNull(note.etag),
                    title = note.title,
                )
            },
            reloadSummaries = ::listNotes,
        )
    }

    suspend fun deleteNoteCategory(category: String) {
        val path = normalizeNoteCategory(category)
        require(path.isNotEmpty()) { "The root note folder cannot be deleted." }
        executeNoteFolderDelete(
            summaries = listNotes(),
            category = path,
            loadNote = ::loadNote,
            deleteNote = { mutation -> deleteNote(mutation.note.id, requireNotNull(mutation.note.etag)) },
            reloadSummaries = ::listNotes,
        )
    }
}

/**
 * Notes reads and writes built only from the operations the app's own contract describes.
 *
 * Paths and body fields come from [contract]; the shared dynamic request builder validates them.
 * A field the contract does not declare is never sent, and the matching action is not offered.
 */
internal class SemanticNotesRepository(
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val contract: SemanticNotesContract,
) : NotesRepository {
    override val appId: String = contract.descriptor.app.id

    override val capabilities: NotesCapabilities = NotesCapabilities(
        canCreate = contract.supports(NoteOperation.CreateNote),
        canUpdate = contract.supports(NoteOperation.UpdateNote),
        canDelete = contract.supports(NoteOperation.DeleteNote),
        canRename = contract.canWrite(NoteField.Title),
        canFavorite = contract.canWrite(NoteField.Favorite),
        canCategorize = contract.canWrite(NoteField.Category),
    )

    override suspend fun listNotes(): List<NextcloudNote> {
        val binding = contract.binding(NoteOperation.ListNotes)
        val values = binding.excludeContentQuery?.let { (name, value) -> mapOf(name to value) }.orEmpty()
        val response = execute(read(NoteOperation.ListNotes, values))
        check(response.status in 200..299) { "Loading notes failed (HTTP ${response.status})." }
        val records = parseNotesResponseOffUi { response.notesPayload() as? JsonArray }
            ?: error("The notes list returned an unexpected response.")
        return records.mapNotNull { (it as? JsonObject)?.toNote(null) }
            .sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
    }

    override suspend fun loadNote(noteId: Long): NextcloudNote = when (val presence = inspectNotePresence(noteId)) {
        NextcloudNotePresence.Absent -> error("The note no longer exists.")
        is NextcloudNotePresence.Present -> presence.note
    }

    override suspend fun inspectNotePresence(noteId: Long): NextcloudNotePresence {
        val response = execute(read(NoteOperation.ReadNote, identity(NoteOperation.ReadNote, noteId)))
        if (response.status == 404 || response.status == 410) return NextcloudNotePresence.Absent
        check(response.status in 200..299) { "Loading the note failed (HTTP ${response.status})." }
        return NextcloudNotePresence.Present(response.requireNote("The note response is invalid."))
    }

    override suspend fun updateNote(
        noteId: Long,
        content: String,
        category: String,
        favorite: Boolean,
        expectedEtag: String?,
        title: String?,
    ): NextcloudNote {
        require(content.encodeToByteArray().size.toLong() <= MAX_NOTE_BYTES) {
            "Notes larger than ${MAX_NOTE_BYTES / (1024 * 1024)} MiB cannot be edited in the app."
        }
        val response = execute(
            write(
                NoteOperation.UpdateNote,
                identity(NoteOperation.UpdateNote, noteId) + fields(
                    NoteOperation.UpdateNote,
                    NoteField.Content to content,
                    NoteField.Category to category,
                    NoteField.Favorite to favorite.toString(),
                    NoteField.Title to title,
                ),
                expectedEtag,
            ),
        )
        check(response.status != 412) { "This note changed on the server. Reload it before saving your changes." }
        check(response.status != 423) { "This note is temporarily locked on the server." }
        check(response.status in 200..299) { "Saving the note failed (HTTP ${response.status})." }
        return response.requireNote("The saved note response is invalid.")
    }

    override suspend fun createNote(title: String, content: String, category: String): NextcloudNote {
        require(content.encodeToByteArray().size.toLong() <= MAX_NOTE_BYTES) {
            "Notes larger than ${MAX_NOTE_BYTES / (1024 * 1024)} MiB cannot be created in the app."
        }
        val response = execute(
            write(
                NoteOperation.CreateNote,
                fields(
                    NoteOperation.CreateNote,
                    NoteField.Title to normalizeNoteTitle(title),
                    NoteField.Content to content,
                    NoteField.Category to normalizeNoteCategory(category),
                ),
                expectedEtag = null,
            ),
        )
        check(response.status in 200..299) { "Creating the note failed (HTTP ${response.status})." }
        return response.requireNote("The created note response is invalid.")
    }

    override suspend fun deleteNote(noteId: Long, expectedEtag: String?) {
        val response = execute(write(NoteOperation.DeleteNote, identity(NoteOperation.DeleteNote, noteId), expectedEtag))
        check(response.status != 404) { "The note no longer exists." }
        check(response.status != 412) { "This note changed on the server. Reload it before deleting it." }
        check(response.status != 423) { "This note is temporarily locked on the server." }
        check(response.status in 200..299) { "Deleting the note failed (HTTP ${response.status})." }
    }

    private fun identity(operation: NoteOperation, noteId: Long): Map<String, String> {
        require(noteId >= 0L) { "The note ID is invalid." }
        val parameter = contract.binding(operation).identityParameter ?: error("The note route has no identity.")
        return mapOf(parameter to noteId.toString())
    }

    private fun fields(operation: NoteOperation, vararg values: Pair<NoteField, String?>): Map<String, String> {
        val declared = contract.binding(operation).bodyFields
        return values.mapNotNull { (field, value) ->
            val name = declared[field] ?: return@mapNotNull null
            value?.let { name to it }
        }.toMap()
    }

    private fun read(operation: NoteOperation, values: Map<String, String>): NextcloudApiRequest =
        buildDynamicApiRequest(contract.descriptor, contract.binding(operation).action, values, values).copy(
            cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
            maximumResponseBytes = NOTES_RESPONSE_BYTES,
        )

    private fun write(operation: NoteOperation, values: Map<String, String>, expectedEtag: String?): NextcloudApiRequest =
        buildDynamicApiRequest(contract.descriptor, contract.binding(operation).action, values, values).copy(
            maximumResponseBytes = NOTES_RESPONSE_BYTES,
            ifMatch = notesMutationHeaders(expectedEtag)["If-Match"],
        )
}

private suspend fun NextcloudApiResponse.requireNote(message: String): NextcloudNote =
    parseNotesResponseOffUi { (notesPayload() as? JsonObject)?.toNote(etag) }
        ?: error(message)

private suspend fun <T> parseNotesResponseOffUi(block: () -> T): T = parseDeckResponseOffUi(parse = block)

private fun NextcloudApiResponse.notesPayload(): JsonElement {
    require(body.size <= NOTES_RESPONSE_BYTES) { "The notes response is too large." }
    val root = runCatching { notesJson.parseToJsonElement(body.decodeToString()) }.getOrNull()
        ?: error("The notes response is not valid JSON.")
    val ocs = (root as? JsonObject)?.get("ocs") as? JsonObject ?: return root
    return ocs["data"] ?: error("The notes response has no data.")
}

/** Maps a note record by field roles. Records without a numeric identity are not notes here. */
private fun JsonObject.toNote(responseEtag: String?): NextcloudNote? {
    val id = (this["id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.longOrNull?.takeIf { it >= 0L } ?: return null
    fun text(field: NoteField): String? = NOTE_FIELD_NAMES.getValue(field).firstNotNullOfOrNull { name ->
        entries.firstOrNull { it.key.lowercase() == name }?.value?.scalar()
    }
    val contentPresent = NOTE_FIELD_NAMES.getValue(NoteField.Content).any { name -> keys.any { it.lowercase() == name } }
    return NextcloudNote(
        id = id,
        title = text(NoteField.Title)?.takeIf(String::isNotBlank) ?: "Untitled note",
        modified = text(NoteField.Modified)?.toLongOrNull() ?: 0L,
        category = text(NoteField.Category).orEmpty(),
        favorite = NOTE_FIELD_NAMES.getValue(NoteField.Favorite).firstNotNullOfOrNull { name ->
            (entries.firstOrNull { it.key.lowercase() == name }?.value as? JsonPrimitive)?.let { value ->
                value.booleanOrNull ?: (value.contentOrNull == "1")
            }
        } ?: false,
        readOnly = listOf("readonly", "readOnly", "isReadOnly").firstNotNullOfOrNull { name ->
            (this[name] as? JsonPrimitive)?.booleanOrNull
        } ?: false,
        content = if (contentPresent) text(NoteField.Content).orEmpty() else null,
        etag = responseEtag?.takeIf(String::isNotBlank) ?: (this["etag"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
        internalPath = (this["internalPath"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank),
        isShared = (this["isShared"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

private fun JsonElement.scalar(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

private val notesJson = Json { ignoreUnknownKeys = true }
private const val NOTES_RESPONSE_BYTES = 16L * 1024L * 1024L
