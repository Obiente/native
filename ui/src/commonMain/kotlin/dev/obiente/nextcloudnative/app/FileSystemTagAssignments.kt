package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Reviewed adapter for assigning Nextcloud system tags to one file over WebDAV.
 *
 * https://github.com/nextcloud/server/blob/master/apps/dav/lib/SystemTag/SystemTagsObjectMappingCollection.php
 * https://github.com/nextcloud/server/blob/master/apps/dav/lib/SystemTag/SystemTagPlugin.php
 *
 * - List: PROPFIND Depth 1 on /remote.php/dav/systemtags-relations/files/{fileId}
 * - Assign: PUT .../{fileId}/{tagId} (201). An already assigned tag answers 409.
 * - Unassign: DELETE .../{fileId}/{tagId} (204). A tag that is no longer assigned answers 404.
 * - Create and assign: POST JSON {name, userVisible, userAssignable} to .../{fileId}. An existing
 *   name answers 409, which also makes an unknown result safe to verify by reading.
 * Only tags whose `oc:can-assign` is true are offered for changes.
 */

fun fileTagAssignmentsRequest(fileId: Long): FilesDavRequest = FilesDavRequest(
    method = FilesDavMethod.PROPFIND,
    relativePath = tagRelationsPath(fileId),
    depth = 1,
    contentType = "application/xml; charset=utf-8",
    body = FILE_TAGS_PROPFIND_BODY.encodeToByteArray(),
)

fun fileTagAssignRequest(fileId: Long, tagId: Long): FilesDavRequest =
    FilesDavRequest(FilesDavMethod.PUT, "${tagRelationsPath(fileId)}/${requirePositive(tagId)}")

fun fileTagUnassignRequest(fileId: Long, tagId: Long): FilesDavRequest =
    FilesDavRequest(FilesDavMethod.DELETE, "${tagRelationsPath(fileId)}/${requirePositive(tagId)}")

fun fileTagCreateAndAssignRequest(fileId: Long, name: String): FilesDavRequest {
    require(fileTagNameProblem(name) == null) { "The tag name is invalid." }
    return FilesDavRequest(
        method = FilesDavMethod.POST,
        relativePath = tagRelationsPath(fileId),
        contentType = "application/json",
        body = JsonObject(
            mapOf(
                "name" to JsonPrimitive(name.trim()),
                "userVisible" to JsonPrimitive(true),
                "userAssignable" to JsonPrimitive(true),
            ),
        ).toString().encodeToByteArray(),
    )
}

fun fileTagNameProblem(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Enter a tag name."
        trimmed.length > MAX_FILE_TAG_NAME_LENGTH -> "Tag names can be at most $MAX_FILE_TAG_NAME_LENGTH characters."
        trimmed.any(Char::isISOControl) -> "The tag name contains unsupported characters."
        else -> null
    }
}

fun parseFileTagAssignments(response: NextcloudApiResponse): List<NextcloudSystemTag> {
    val records = parseFilesDavMultistatus(response, MAX_FILE_TAGS)
    return try {
        records.mapNotNull { record ->
            SystemTagDavRecord(
                href = record.href,
                id = record.text("id"),
                displayName = record.text("display-name"),
                userVisible = record.text("user-visible"),
                userAssignable = record.text("user-assignable"),
                canAssign = record.text("can-assign"),
                color = record.text("color"),
            ).toNextcloudSystemTagOrNull()
        }.distinctBy(NextcloudSystemTag::id).sortedBy { it.name.lowercase() }
    } catch (failure: IllegalStateException) {
        throw FilesDavProtocolException(failure.message ?: "The tag response is malformed.")
    } catch (failure: IllegalArgumentException) {
        throw FilesDavProtocolException(failure.message ?: "The tag response is malformed.")
    }
}

data class FileTagsState(
    val assigned: List<NextcloudSystemTag>? = null,
    /** Every tag the account can see; null until loaded. */
    val catalog: List<NextcloudSystemTag>? = null,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    val failure: FilesDavFailureKind? = null,
    val pendingIds: Set<Long> = emptySet(),
    val creating: Boolean = false,
    val unconfirmedIds: Set<Long> = emptySet(),
    val notice: String? = null,
) {
    val assignedIds: Set<Long> get() = assigned.orEmpty().mapTo(HashSet(), NextcloudSystemTag::id)

    fun canToggle(tag: NextcloudSystemTag): Boolean =
        assigned != null && tag.canAssign && tag.id !in pendingIds && tag.id !in unconfirmedIds && !creating

    /** Tags that can be offered: visible and assignable by this account, assigned ones first. */
    val offered: List<NextcloudSystemTag>
        get() {
            val ids = assignedIds
            val all = (assigned.orEmpty() + catalog.orEmpty().filter { it.userVisible && it.canAssign })
                .distinctBy(NextcloudSystemTag::id)
            return all.sortedWith(compareByDescending<NextcloudSystemTag> { it.id in ids }.thenBy { it.name.lowercase() })
        }
}

/** Owns tag assignments of one file. Writes are idempotent but are still verified before reuse. */
class FileTagsController(
    private val scope: CoroutineScope,
    private val fileId: Long,
    private val execute: suspend (FilesDavRequest) -> NextcloudApiResponse,
    private val loadCatalog: suspend () -> List<NextcloudSystemTag>,
) {
    var state by mutableStateOf(FileTagsState())
        private set

    fun start() {
        if (state.assigned == null && !state.loading) refresh()
    }

    fun refresh() {
        state = state.copy(loading = true)
        scope.launch {
            try {
                when (val read = readAssigned()) {
                    is TagsRead.Loaded -> state = state.copy(failure = null, unconfirmedIds = emptySet())
                    is TagsRead.Failed -> state = state.copy(failure = read.kind)
                }
                if (state.catalog == null && !state.unavailable) {
                    val catalog = try {
                        loadCatalog()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The catalog only adds unassigned choices; assigned tags remain usable.
                        null
                    }
                    state = state.copy(catalog = catalog)
                }
            } finally {
                state = state.copy(loading = false)
            }
        }
    }

    fun toggle(tag: NextcloudSystemTag) {
        if (!state.canToggle(tag)) return
        val assign = tag.id !in state.assignedIds
        state = state.copy(pendingIds = state.pendingIds + tag.id, notice = null)
        scope.launch {
            val request = if (assign) fileTagAssignRequest(fileId, tag.id) else fileTagUnassignRequest(fileId, tag.id)
            val outcome = sendFilesDavWrite(execute, request)
            val alreadyDone = (outcome as? FilesDavWriteOutcome.Rejected)?.let {
                (assign && it.status == 409) || (!assign && it.kind == FilesDavFailureKind.NotFound)
            } == true
            val notice = when {
                outcome is FilesDavWriteOutcome.Confirmed || alreadyDone -> {
                    readAssigned()
                    null
                }
                outcome is FilesDavWriteOutcome.Rejected -> "Couldn't ${if (assign) "add" else "remove"} ${tag.name}. ${tagFailureReason(outcome.kind)}"
                else -> when (val read = readAssigned()) {
                    is TagsRead.Loaded -> if ((tag.id in read.ids) == assign) null else "${tag.name} was not changed. You can try again."
                    is TagsRead.Failed -> {
                        state = state.copy(unconfirmedIds = state.unconfirmedIds + tag.id)
                        "Couldn't confirm whether ${tag.name} changed. Refresh before trying again."
                    }
                }
            }
            state = state.copy(pendingIds = state.pendingIds - tag.id, notice = notice)
        }
    }

    /** Creates a new public tag and assigns it; [onCreated] runs only after it is verified. */
    fun createAndAssign(name: String, onCreated: () -> Unit) {
        if (fileTagNameProblem(name) != null || state.creating || state.assigned == null) return
        val trimmed = name.trim()
        if (state.offered.any { it.name.equals(trimmed, ignoreCase = true) }) {
            state = state.copy(notice = "A tag named $trimmed already exists. Choose it from the list.")
            return
        }
        state = state.copy(creating = true, notice = null)
        scope.launch {
            val outcome = sendFilesDavWrite(execute, fileTagCreateAndAssignRequest(fileId, trimmed))
            val read = readAssigned()
            val present = (read as? TagsRead.Loaded)?.let { state.assigned.orEmpty().any { it.name.equals(trimmed, ignoreCase = true) } }
            val notice = when {
                present == true -> {
                    state = state.copy(catalog = null)
                    onCreated()
                    null
                }
                outcome is FilesDavWriteOutcome.Rejected && outcome.status == 409 ->
                    "A tag named $trimmed already exists but is not visible to you."
                outcome is FilesDavWriteOutcome.Rejected -> "Couldn't create the tag. ${tagFailureReason(outcome.kind)}"
                present == false -> "The tag was not created. You can try again."
                else -> "Couldn't confirm whether the tag was created. Refresh before trying again."
            }
            state = state.copy(creating = false, notice = notice)
        }
    }

    private suspend fun readAssigned(): TagsRead {
        val read = try {
            val response = execute(fileTagAssignmentsRequest(fileId))
            filesDavFailureForStatus(response.status)?.let { TagsRead.Failed(it) }
                ?: TagsRead.Loaded(parseFileTagAssignments(response))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FilesDavProtocolException) {
            TagsRead.Failed(FilesDavFailureKind.Malformed)
        } catch (_: Exception) {
            TagsRead.Failed(FilesDavFailureKind.Offline)
        }
        when (read) {
            is TagsRead.Loaded -> state = state.copy(assigned = read.tags, unavailable = false)
            is TagsRead.Failed -> if (state.assigned == null && read.kind in TAGS_UNAVAILABLE) {
                state = state.copy(unavailable = true, assigned = emptyList())
            }
        }
        return read
    }
}

private sealed interface TagsRead {
    data class Loaded(val tags: List<NextcloudSystemTag>) : TagsRead {
        val ids: Set<Long> get() = tags.mapTo(HashSet(), NextcloudSystemTag::id)
    }
    data class Failed(val kind: FilesDavFailureKind) : TagsRead
}

private val TAGS_UNAVAILABLE = setOf(FilesDavFailureKind.NotFound, FilesDavFailureKind.Unavailable)

internal fun tagFailureReason(kind: FilesDavFailureKind): String = when (kind) {
    FilesDavFailureKind.Forbidden -> "You are not allowed to change this tag on this file."
    FilesDavFailureKind.PreconditionFailed -> "This tag is no longer visible to you."
    FilesDavFailureKind.Unauthorized -> "Sign in to this account again."
    FilesDavFailureKind.Offline -> "Check your connection and try again."
    FilesDavFailureKind.Throttled -> "The server asked to slow down. Wait a moment."
    else -> "The server could not complete it. Try again later."
}

private fun tagRelationsPath(fileId: Long): String = "${FILES_DAV_ROOT}systemtags-relations/files/${requirePositive(fileId)}"

private fun requirePositive(value: Long): Long {
    require(value > 0L) { "The identifier is invalid." }
    return value
}

private const val MAX_FILE_TAGS = 500
private const val MAX_FILE_TAG_NAME_LENGTH = 64

private val FILE_TAGS_PROPFIND_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
      <d:prop>
        <oc:id/><oc:display-name/><oc:user-visible/><oc:user-assignable/><oc:can-assign/><nc:color/>
      </d:prop>
    </d:propfind>
""".trimIndent()
