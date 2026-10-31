package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Deck board sharing (ACL) contract.
 *
 * Source: Deck `appinfo/routes.php` (`board_api#addAcl`, `#updateAcl`, `#deleteAcl`),
 * `lib/Db/Acl.php` for participant types, and `BoardService::addAcl/updateAcl/deleteAcl` for the
 * permission checks: adding and updating require share permission, removing requires manage
 * permission. The server caps the edit, share, and manage flags a non-manager can grant, so a
 * successful response is always reconciled against a fresh board read.
 */
enum class DeckAclType(val wireValue: Int?, val label: String) {
    User(0, "User"),
    Group(1, "Group"),
    Remote(6, "Federated user"),
    Circle(7, "Team"),
    Other(null, "Other"),
    ;

    /** Remote shares require federation setup that this client does not negotiate. */
    val canBeAddedNatively: Boolean
        get() = this == User || this == Group || this == Circle

    companion object {
        fun fromWire(value: Int?): DeckAclType =
            entries.firstOrNull { it.wireValue != null && it.wireValue == value } ?: Other
    }
}

data class DeckAclParticipant(
    val id: String,
    val displayName: String,
)

data class DeckAcl(
    val id: Long,
    val boardId: Long,
    val type: DeckAclType,
    val participant: DeckAclParticipant,
    val canEdit: Boolean,
    val canShare: Boolean,
    val canManage: Boolean,
    val owner: Boolean = false,
) {
    fun permissions(): DeckAclPermissions = DeckAclPermissions(canEdit, canShare, canManage)
}

data class DeckAclPermissions(
    val canEdit: Boolean,
    val canShare: Boolean,
    val canManage: Boolean,
)

/** A participant returned by the server's own autocomplete. It is never typed by the user. */
data class DeckSharee(
    val id: String,
    val displayName: String,
    val type: DeckAclType,
    val detail: String? = null,
) {
    init {
        require(type.canBeAddedNatively) { "This Deck participant type cannot be added." }
        require(id.isNotBlank() && id.length <= DECK_SHARE_PARTICIPANT_LIMIT) {
            "The Deck participant is invalid."
        }
        require(id.none(Char::isISOControl)) { "The Deck participant is invalid." }
    }
}

/** Which permission toggles the signed-in user may change, mirroring the Deck web sharing tab. */
data class DeckAclEditability(
    val canChangeEdit: Boolean,
    val canChangeShare: Boolean,
    val canChangeManage: Boolean,
    val canRemove: Boolean,
) {
    val canChangeAny: Boolean
        get() = canChangeEdit || canChangeShare || canChangeManage
}

fun DeckBoard.canOpenSharing(): Boolean = aclReadable &&
    (permissions.canShare || permissions.canManage)

fun DeckBoard.canAddShares(): Boolean = aclReadable && !archived && permissions.canShare

fun DeckBoard.aclEditability(acl: DeckAcl, currentUserId: String): DeckAclEditability {
    val ownShare = acl.type == DeckAclType.User && acl.participant.id == currentUserId
    if (ownShare || archived) return DeckAclEditability(false, false, false, false)
    return DeckAclEditability(
        canChangeEdit = permissions.canManage || (permissions.canEdit && permissions.canShare),
        canChangeShare = permissions.canManage || permissions.canShare,
        canChangeManage = permissions.canManage,
        canRemove = permissions.canManage,
    )
}

fun DeckWriteRoutePlan.addBoardAcl(
    board: DeckBoard,
    sharee: DeckSharee,
): NextcloudApiRequest {
    require(board.id > 0L) { "The Deck board id is invalid." }
    require(board.canAddShares()) { "Deck share permission is required." }
    require(
        board.acl.none { it.type == sharee.type && it.participant.id == sharee.id },
    ) { "This board is already shared with ${sharee.displayName}." }
    require(!(sharee.type == DeckAclType.User && sharee.id == board.owner?.id)) {
        "The board owner already has access."
    }
    return deckSharingJsonRequest(
        method = NextcloudApiMethod.POST,
        path = "$apiRoot/boards/${board.id}/acl",
        body = buildJsonObject {
            put("type", checkNotNull(sharee.type.wireValue))
            put("participant", sharee.id)
            put("permissionEdit", false)
            put("permissionShare", false)
            put("permissionManage", false)
        },
    )
}

fun DeckWriteRoutePlan.updateBoardAcl(
    board: DeckBoard,
    acl: DeckAcl,
    currentUserId: String,
    requested: DeckAclPermissions,
): NextcloudApiRequest {
    val current = board.requireAcl(acl)
    val editability = board.aclEditability(current, currentUserId)
    require(editability.canChangeAny) { "You cannot change this share." }
    require(editability.canChangeEdit || requested.canEdit == current.canEdit) {
        "You cannot change edit access for this share."
    }
    require(editability.canChangeShare || requested.canShare == current.canShare) {
        "You cannot change sharing access for this share."
    }
    require(editability.canChangeManage || requested.canManage == current.canManage) {
        "You cannot change manage access for this share."
    }
    return deckSharingJsonRequest(
        method = NextcloudApiMethod.PUT,
        path = "$apiRoot/boards/${board.id}/acl/${current.id}",
        body = buildJsonObject {
            put("permissionEdit", requested.canEdit)
            put("permissionShare", requested.canShare)
            put("permissionManage", requested.canManage)
        },
    )
}

fun DeckWriteRoutePlan.deleteBoardAcl(
    board: DeckBoard,
    acl: DeckAcl,
    currentUserId: String,
): NextcloudApiRequest {
    val current = board.requireAcl(acl)
    require(board.aclEditability(current, currentUserId).canRemove) {
        "Deck manage permission is required to remove access."
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.DELETE,
        relativePath = "$apiRoot/boards/${board.id}/acl/${current.id}",
        ocsApiRequest = true,
        maximumResponseBytes = DECK_SHARING_RESPONSE_BYTES,
    )
}

/**
 * Searches participants with the same core autocomplete request the Deck web sharing tab uses
 * (`/core/autocomplete/get`, `itemType=deck`). Remote participants are excluded because adding
 * them requires federation setup this client does not negotiate.
 */
fun deckShareeSearchRequest(boardId: Long, query: String): NextcloudApiRequest {
    require(boardId > 0L) { "The Deck board id is invalid." }
    val search = query.trim()
    require(search.length <= DECK_SHARE_SEARCH_LIMIT) { "The search is too long." }
    require(search.none(Char::isISOControl)) { "The search contains unsupported characters." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/ocs/v2.php/core/autocomplete/get",
        queryParameters = buildMap {
            put("format", "json")
            put("search", search)
            put("itemType", "deck")
            put("itemId", boardId.toString())
            put("limit", DECK_SHARE_RESULT_LIMIT.toString())
            DECK_SEARCHABLE_SHARE_TYPES.forEachIndexed { index, type ->
                put("shareTypes[$index]", checkNotNull(type.wireValue).toString())
            }
        },
        ocsApiRequest = true,
        maximumResponseBytes = DECK_SHARING_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )
}

fun parseDeckSharees(response: NextcloudApiResponse): List<DeckSharee> {
    require(response.status in 200..299) { "Deck could not search people (HTTP ${response.status})." }
    require(response.body.size <= DECK_SHARING_RESPONSE_BYTES) { "The people search returned too much data." }
    val root = runCatching { deckSharingJson.parseToJsonElement(response.body.decodeToString()) }
        .getOrNull() as? JsonObject ?: error("The people search returned invalid JSON.")
    val ocs = root["ocs"] as? JsonObject ?: error("The people search returned an unexpected response.")
    val status = ((ocs["meta"] as? JsonObject)?.get("statuscode"))?.let { value ->
        if (value is JsonNull) null else value.jsonPrimitive.intOrNull
    }
    require(status == null || status in 200..299) { "The people search was rejected." }
    val values = ocs["data"] as? JsonArray ?: error("The people search returned an unexpected response.")
    return values.mapNotNull { element ->
        val value = element as? JsonObject ?: return@mapNotNull null
        val type = when (value.text("source")) {
            "users" -> DeckAclType.User
            "groups" -> DeckAclType.Group
            "circles", "teams" -> DeckAclType.Circle
            else -> return@mapNotNull null
        }
        val id = value.text("id")?.takeIf { it.length <= DECK_SHARE_PARTICIPANT_LIMIT }
            ?.takeIf { candidate -> candidate.none(Char::isISOControl) }
            ?: return@mapNotNull null
        DeckSharee(
            id = id,
            displayName = value.text("label") ?: id,
            type = type,
            detail = value.text("subline") ?: value.text("shareWithDisplayNameUnique")?.takeIf { it != id },
        )
    }.distinctBy { it.type to it.id }.take(DECK_SHARE_RESULT_LIMIT)
}

/** Parses the `acl` relation of a full Deck board record. Malformed entries are rejected. */
internal fun parseDeckBoardAcl(boardId: Long, values: JsonArray): List<DeckAcl> = values.map { element ->
    val value = element as? JsonObject ?: error("The Deck board share list is malformed.")
    val id = value.long("id")?.takeIf { it > 0L } ?: error("A Deck board share has no valid id.")
    val aclBoardId = value.long("boardId") ?: boardId
    require(aclBoardId == boardId) { "A Deck board share belongs to an unexpected board." }
    val participant = when (val raw = value["participant"]) {
        is JsonObject -> {
            val participantId = raw.text("uid") ?: raw.text("primaryKey") ?: raw.text("id")
                ?: error("A Deck board share has no participant.")
            DeckAclParticipant(participantId, raw.text("displayname") ?: raw.text("displayName") ?: participantId)
        }
        else -> {
            val participantId = value.text("participant") ?: error("A Deck board share has no participant.")
            DeckAclParticipant(participantId, participantId)
        }
    }
    DeckAcl(
        id = id,
        boardId = boardId,
        type = DeckAclType.fromWire(value.int("type")),
        participant = participant,
        canEdit = value.flag("permissionEdit"),
        canShare = value.flag("permissionShare"),
        canManage = value.flag("permissionManage"),
        owner = value.flag("owner"),
    )
}

/** Postcondition checks run against a fresh authoritative board after each sharing write. */
fun verifyDeckAclAdded(board: DeckBoard, sharee: DeckSharee): DeckWriteVerification =
    if (board.acl.any { it.type == sharee.type && it.participant.id == sharee.id }) {
        DeckWriteVerification.Confirmed
    } else {
        DeckWriteVerification.NotApplied("Deck did not add ${sharee.displayName} to this board.")
    }

fun verifyDeckAclUpdated(
    board: DeckBoard,
    aclId: Long,
    requested: DeckAclPermissions,
): DeckWriteVerification {
    val current = board.acl.firstOrNull { it.id == aclId }
        ?: return DeckWriteVerification.NotApplied("This share no longer exists.")
    return if (current.permissions() == requested) {
        DeckWriteVerification.Confirmed
    } else {
        DeckWriteVerification.Adjusted(
            "Deck kept some permissions unchanged. Your own access limits what you can grant.",
        )
    }
}

fun verifyDeckAclRemoved(board: DeckBoard, aclId: Long): DeckWriteVerification =
    if (board.acl.none { it.id == aclId }) {
        DeckWriteVerification.Confirmed
    } else {
        DeckWriteVerification.NotApplied("Deck did not remove this share.")
    }

private fun DeckBoard.requireAcl(acl: DeckAcl): DeckAcl {
    require(acl.boardId == id) { "The Deck share belongs to another board." }
    return this.acl.firstOrNull { it.id == acl.id }
        ?: error("The Deck share is not part of the current board.")
}

private fun deckSharingJsonRequest(
    method: NextcloudApiMethod,
    path: String,
    body: JsonObject,
): NextcloudApiRequest = NextcloudApiRequest(
    method = method,
    relativePath = path,
    contentType = "application/json",
    body = body.toString().encodeToByteArray(),
    ocsApiRequest = true,
    maximumResponseBytes = DECK_SHARING_RESPONSE_BYTES,
)

private fun JsonObject.text(name: String): String? = this[name]?.let { value ->
    if (value is JsonNull) null else runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
}?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.long(name: String): Long? = this[name]?.let { value ->
    if (value is JsonNull) null else runCatching { value.jsonPrimitive.longOrNull }.getOrNull()
}

private fun JsonObject.int(name: String): Int? = this[name]?.let { value ->
    if (value is JsonNull) null else runCatching { value.jsonPrimitive.intOrNull }.getOrNull()
}

private fun JsonObject.flag(name: String): Boolean = this[name]?.let { value ->
    if (value is JsonNull) {
        false
    } else {
        val primitive = runCatching { value.jsonPrimitive }.getOrNull() ?: return@let false
        primitive.booleanOrNull ?: (primitive.contentOrNull == "1")
    }
} ?: false

private val deckSharingJson = Json { ignoreUnknownKeys = true }
private val DECK_SEARCHABLE_SHARE_TYPES = listOf(DeckAclType.User, DeckAclType.Group, DeckAclType.Circle)
private const val DECK_SHARE_RESULT_LIMIT = 20
private const val DECK_SHARE_SEARCH_LIMIT = 200
private const val DECK_SHARE_PARTICIPANT_LIMIT = 255
private const val DECK_SHARING_RESPONSE_BYTES = 1L * 1024L * 1024L
