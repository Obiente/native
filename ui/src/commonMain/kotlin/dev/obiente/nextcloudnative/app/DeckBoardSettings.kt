package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Deck user settings that have a documented server effect.
 *
 * Source: Deck `ConfigController` (`GET /ocs/v2.php/apps/deck/api/v1.0/config`,
 * `POST .../config/{key}` with a `value`) and `ConfigService::set`. The `board:{id}:notify-due`
 * key accepts `off`, `assigned`, or `all`; `board:{id}:calendar` and the global `calendar` key
 * are booleans that control whether boards appear in the Calendar app. Board-scoped keys need
 * edit permission on that board. Web-only display preferences such as `cardDetailsInModal` and
 * `cardIdBadge` have no effect in this client and are not offered.
 *
 * Setting a value is idempotent, so a write with an unknown outcome is verified by reading the
 * board or user configuration again instead of being presented as failed or complete.
 */
enum class DeckDueNotification(val wireValue: String, val label: String) {
    Off("off", "No notifications"),
    Assigned("assigned", "Cards assigned to me"),
    All("all", "All cards"),
    ;

    companion object {
        fun fromWire(value: String?): DeckDueNotification? = entries.firstOrNull { it.wireValue == value }
    }
}

data class DeckBoardSettings(
    val dueNotification: DeckDueNotification?,
    val showInCalendar: Boolean?,
)

data class DeckUserSettings(
    val showBoardsInCalendar: Boolean?,
)

sealed interface DeckSettingChange {
    val key: String

    data class BoardDueNotification(
        val boardId: Long,
        val value: DeckDueNotification,
    ) : DeckSettingChange {
        override val key: String get() = "board:${boardId.requireDeckSettingsBoardId()}:notify-due"
    }

    data class BoardCalendar(
        val boardId: Long,
        val value: Boolean,
    ) : DeckSettingChange {
        override val key: String get() = "board:${boardId.requireDeckSettingsBoardId()}:calendar"
    }

    data class GlobalCalendar(val value: Boolean) : DeckSettingChange {
        override val key: String get() = "calendar"
    }
}

fun deckUserSettingsRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = DECK_CONFIG_PATH,
    queryParameters = mapOf("format" to "json"),
    ocsApiRequest = true,
    maximumResponseBytes = DECK_SETTINGS_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
)

fun deckSettingWriteRequest(board: DeckBoard?, change: DeckSettingChange): NextcloudApiRequest {
    when (change) {
        is DeckSettingChange.BoardDueNotification -> board.requireSettingsAccess(change.boardId)
        is DeckSettingChange.BoardCalendar -> board.requireSettingsAccess(change.boardId)
        is DeckSettingChange.GlobalCalendar -> Unit
    }
    val body = buildJsonObject {
        when (change) {
            is DeckSettingChange.BoardDueNotification -> put("value", change.value.wireValue)
            is DeckSettingChange.BoardCalendar -> put("value", change.value)
            is DeckSettingChange.GlobalCalendar -> put("value", change.value)
        }
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "$DECK_CONFIG_PATH/${change.key}",
        queryParameters = mapOf("format" to "json"),
        contentType = "application/json",
        body = body.toString().encodeToByteArray(),
        ocsApiRequest = true,
        maximumResponseBytes = DECK_SETTINGS_RESPONSE_BYTES,
    )
}

/** Board settings are only meaningful to change when the board is editable. */
fun DeckBoard.canChangeDeckSettings(): Boolean = permissions.canEdit

fun parseDeckUserSettings(response: NextcloudApiResponse): DeckUserSettings {
    val data = response.deckSettingsData("Deck settings") as? JsonObject
        ?: error("Deck settings returned an unexpected response.")
    return DeckUserSettings(showBoardsInCalendar = data["calendar"]?.deckSettingBoolean())
}

/** Confirms the server accepted the write. The caller still re-reads to verify the value. */
fun parseDeckSettingWrite(response: NextcloudApiResponse) {
    if (response.status == 404) error("This Deck version does not support that setting.")
    response.deckSettingsData("The Deck setting")
}

internal fun parseDeckBoardSettings(value: JsonObject?): DeckBoardSettings? {
    if (value == null) return null
    return DeckBoardSettings(
        dueNotification = (value["notify-due"] as? JsonPrimitive)?.contentOrNull
            ?.let(DeckDueNotification::fromWire),
        showInCalendar = value["calendar"]?.deckSettingBoolean(),
    )
}

fun verifyDeckSettingChange(
    change: DeckSettingChange,
    board: DeckBoard?,
    user: DeckUserSettings?,
): Boolean = when (change) {
    is DeckSettingChange.BoardDueNotification ->
        board?.id == change.boardId && board.settings?.dueNotification == change.value
    is DeckSettingChange.BoardCalendar ->
        board?.id == change.boardId && board.settings?.showInCalendar == change.value
    is DeckSettingChange.GlobalCalendar -> user?.showBoardsInCalendar == change.value
}

/**
 * PHP stores these values with a string cast, so `true` is read back as `"1"` and `false` as
 * an empty string. Anything else is treated as unknown rather than guessed.
 */
private fun JsonElement.deckSettingBoolean(): Boolean? {
    if (this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    if (!primitive.isString) {
        return when (primitive.intOrNull) {
            1 -> true
            0 -> false
            else -> null
        }
    }
    return when (primitive.content.trim().lowercase()) {
        "1", "true", "yes" -> true
        "", "0", "false", "no" -> false
        else -> null
    }
}

private fun NextcloudApiResponse.deckSettingsData(label: String): JsonElement {
    require(status in 200..299) { "$label failed (HTTP $status)." }
    require(body.size <= DECK_SETTINGS_RESPONSE_BYTES) { "$label returned too much data." }
    val root = runCatching { deckSettingsJson.parseToJsonElement(body.decodeToString()) }
        .getOrNull() as? JsonObject ?: error("$label returned invalid JSON.")
    val ocs = root["ocs"] as? JsonObject ?: return root
    val code = ((ocs["meta"] as? JsonObject)?.get("statuscode") as? JsonPrimitive)?.intOrNull
    require(code == null || code in 200..299) { "$label was rejected." }
    return ocs["data"] ?: JsonNull
}

private fun DeckBoard?.requireSettingsAccess(boardId: Long) {
    val board = checkNotNull(this) { "The Deck board is unavailable." }
    require(board.id == boardId) { "The Deck setting belongs to another board." }
    require(board.canChangeDeckSettings()) { "Deck edit permission is required for board settings." }
}

private fun Long.requireDeckSettingsBoardId(): Long {
    require(this > 0L) { "The Deck board id is invalid." }
    return this
}

private val deckSettingsJson = Json { ignoreUnknownKeys = true }
private const val DECK_CONFIG_PATH = "/ocs/v2.php/apps/deck/api/v1.0/config"
private const val DECK_SETTINGS_RESPONSE_BYTES = 256L * 1024L
