package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A synthetic Talk server behind the [TalkTransportRequest] boundary.
 *
 * It follows Spreed's documented behavior: history and long polls, 304 when nothing is new,
 * edit, delete, and reaction system messages that carry the changed parent, and reaction maps.
 * Long polls wait until the test releases them. A write can be made to fail before or after it
 * is applied, to model a lost response.
 */
internal class FakeTalkServer(
    var features: List<String> = ALL_FEATURES,
    var readOnly: Int = 0,
    var lobbyState: Int = 0,
    var participantType: Int = 3,
    var permissions: Int? = 510,
    var type: Int = 2,
    var nowSeconds: Long = NOW,
) {
    enum class WriteFault { None, LostBeforeApplying, LostAfterApplying, GatewayTimeoutAfterApplying }

    data class StoredMessage(
        val id: Long,
        val actorId: String,
        val actorDisplayName: String,
        var message: String,
        val timestamp: Long,
        val referenceId: String? = null,
        val replyTo: Long? = null,
        val systemMessage: String = "",
        val systemParent: Long? = null,
        var deleted: Boolean = false,
        var lastEditTimestamp: Long? = null,
        val reactions: LinkedHashMap<String, MutableList<String>> = linkedMapOf(),
    )

    val stored = mutableListOf<StoredMessage>()
    val requests = mutableListOf<TalkTransportRequest>()
    var writeFault = WriteFault.None
    var writeStatus: Int? = null
    var historyStatus = 200
    var pollStatus: Int? = null
    var pollFailure: Exception? = null
    var roomFailure = false
    private val pendingPolls = ArrayDeque<CompletableDeferred<Unit>>()
    private var nextId = 1L

    val pollsWaiting: Int get() = pendingPolls.size

    fun post(actorId: String, text: String, actorDisplayName: String = actorId.replaceFirstChar(Char::uppercase), timestamp: Long = nowSeconds): StoredMessage =
        StoredMessage(nextId++, actorId, actorDisplayName, text, timestamp).also(stored::add)

    /** Lets the oldest waiting long poll answer with whatever is newer than its cursor. */
    fun releasePoll() {
        pendingPolls.removeFirstOrNull()?.complete(Unit)
    }

    fun writes(): List<TalkTransportRequest> = requests.filterNot(TalkTransportRequest::isRead)

    suspend fun execute(request: TalkTransportRequest): TalkTransportResponse {
        requests += request
        val path = request.relativePath
        val query = request.queryParameters.toMap()
        return when {
            path == "/ocs/v2.php/cloud/capabilities" -> ok(capabilities())
            path.startsWith("/ocs/v2.php/apps/spreed/api/v4/room/") ->
                if (roomFailure) error("Synthetic room failure") else ok(room())
            path.startsWith("$TALK_API_PREFIX/reaction/") -> reaction(request, path.substringAfterLast('/').toLong())
            path.endsWith("/context") -> {
                val id = path.removeSuffix("/context").substringAfterLast('/').toLong()
                ok(JsonArray(stored.filter { it.id == id }.map(::json)))
            }
            request.method == TalkTransportMethod.GET && query["lookIntoFuture"] == "1" -> poll(query.getValue("lastKnownMessageId").toLong())
            request.method == TalkTransportMethod.GET -> history(query.getValue("lastKnownMessageId").toLong(), query.getValue("limit").toInt())
            else -> chatWrite(request)
        }
    }

    private suspend fun poll(cursor: Long): TalkTransportResponse {
        val gate = CompletableDeferred<Unit>()
        pendingPolls += gate
        gate.await()
        pollFailure?.let { throw it }
        pollStatus?.let { return TalkTransportResponse(it, byteArrayOf(), null) }
        val newer = stored.filter { it.id > cursor }
        if (newer.isEmpty()) return TalkTransportResponse(304, byteArrayOf(), null)
        return ok(JsonArray(newer.map(::json)), lastGiven = newer.maxOf(StoredMessage::id))
    }

    private fun history(cursor: Long, limit: Int): TalkTransportResponse {
        if (historyStatus != 200) return TalkTransportResponse(historyStatus, byteArrayOf(), null)
        val page = stored.filter { cursor == 0L || it.id < cursor }.sortedByDescending(StoredMessage::id).take(limit)
        if (page.isEmpty()) return TalkTransportResponse(304, byteArrayOf(), null)
        return ok(JsonArray(page.map(::json)), lastGiven = page.minOf(StoredMessage::id))
    }

    private fun chatWrite(request: TalkTransportRequest): TalkTransportResponse {
        writeStatus?.let { return TalkTransportResponse(it, byteArrayOf(), null) }
        if (writeFault == WriteFault.LostBeforeApplying) error("Synthetic connection reset before the request was sent")
        val form = request.formFields.toMap()
        val idSegment = request.relativePath.substringAfterLast('/').toLongOrNull()
        val (status, body) = when (request.method) {
            TalkTransportMethod.POST -> {
                val created = StoredMessage(
                    id = nextId++,
                    actorId = SELF,
                    actorDisplayName = "Self",
                    message = form.getValue("message"),
                    timestamp = nowSeconds,
                    referenceId = form["referenceId"],
                    replyTo = form["replyTo"]?.toLong(),
                ).also(stored::add)
                201 to json(created)
            }
            TalkTransportMethod.PUT -> {
                val target = stored.first { it.id == idSegment }
                target.message = form.getValue("message")
                target.lastEditTimestamp = nowSeconds
                200 to json(system("message_edited", target.id))
            }
            TalkTransportMethod.DELETE -> {
                val target = stored.first { it.id == idSegment }
                target.deleted = true
                target.message = "Message deleted by you"
                target.reactions.clear()
                200 to json(system("message_deleted", target.id))
            }
            TalkTransportMethod.GET -> error("Reads are not writes")
        }
        return faultAfterApplying() ?: ok(body, status = status)
    }

    private fun reaction(request: TalkTransportRequest, messageId: Long): TalkTransportResponse {
        val target = stored.firstOrNull { it.id == messageId } ?: return TalkTransportResponse(404, byteArrayOf(), null)
        if (request.method == TalkTransportMethod.GET) {
            val emoji = request.queryParameters.toMap()["reaction"]
            return ok(reactionMap(target, emoji))
        }
        writeStatus?.let { return TalkTransportResponse(it, byteArrayOf(), null) }
        if (writeFault == WriteFault.LostBeforeApplying) error("Synthetic connection reset before the request was sent")
        val emoji = (request.formFields + request.queryParameters).toMap().getValue("reaction")
        val status = if (request.method == TalkTransportMethod.POST) {
            val actors = target.reactions.getOrPut(emoji) { mutableListOf() }
            if (SELF in actors) {
                200
            } else {
                actors += SELF
                system("reaction", target.id)
                201
            }
        } else {
            val actors = target.reactions[emoji]
            if (actors == null || SELF !in actors) return TalkTransportResponse(404, byteArrayOf(), null)
            actors -= SELF
            if (actors.isEmpty()) target.reactions.remove(emoji)
            system("reaction_revoked", target.id)
            200
        }
        return faultAfterApplying() ?: ok(reactionMap(target, null), status = status)
    }

    private fun faultAfterApplying(): TalkTransportResponse? = when (writeFault) {
        WriteFault.LostAfterApplying -> error("Synthetic connection reset after the server applied the request")
        WriteFault.GatewayTimeoutAfterApplying -> TalkTransportResponse(504, byteArrayOf(), null)
        else -> null
    }

    private fun system(kind: String, parent: Long): StoredMessage =
        StoredMessage(nextId++, SELF, "Self", kind, nowSeconds, systemMessage = kind, systemParent = parent).also(stored::add)

    private fun reactionMap(message: StoredMessage, only: String?): JsonElement {
        val entries = message.reactions.filterKeys { only == null || it == only }
        if (entries.isEmpty()) return JsonArray(emptyList())
        return buildJsonObject {
            entries.forEach { (emoji, actors) ->
                putJsonArray(emoji) {
                    actors.forEach { actor ->
                        add(buildJsonObject {
                            put("actorType", "users")
                            put("actorId", actor)
                            put("actorDisplayName", actor)
                            put("timestamp", NOW)
                        })
                    }
                }
            }
        }
    }

    private fun json(message: StoredMessage, includeParent: Boolean = true): JsonObject = buildJsonObject {
        put("id", message.id)
        put("token", TOKEN)
        put("actorType", "users")
        put("actorId", message.actorId)
        put("actorDisplayName", message.actorDisplayName)
        put("timestamp", message.timestamp)
        put("message", message.message)
        put("messageParameters", JsonArray(emptyList()))
        put("systemMessage", message.systemMessage)
        put("messageType", if (message.systemMessage.isNotEmpty()) "system" else if (message.deleted) "comment_deleted" else "comment")
        put("isReplyable", message.systemMessage.isEmpty() && !message.deleted)
        message.referenceId?.let { put("referenceId", it) }
        message.lastEditTimestamp?.let {
            put("lastEditTimestamp", it)
            put("lastEditActorDisplayName", message.actorDisplayName)
        }
        if (message.reactions.isNotEmpty()) {
            putJsonObject("reactions") { message.reactions.forEach { (emoji, actors) -> put(emoji, actors.size) } }
            put("reactionsSelf", buildJsonArray {
                message.reactions.filterValues { SELF in it }.keys.forEach { add(JsonPrimitive(it)) }
            })
        }
        val parentId = message.systemParent ?: message.replyTo
        if (includeParent && parentId != null) {
            stored.firstOrNull { it.id == parentId }?.let { put("parent", json(it, includeParent = false)) }
        }
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        putJsonObject("capabilities") {
            putJsonObject("spreed") {
                putJsonArray("features") { features.forEach { add(JsonPrimitive(it)) } }
                putJsonObject("config") { putJsonObject("chat") { put("max-length", 32_000) } }
            }
        }
    }

    private fun room(): JsonObject = buildJsonObject {
        put("token", TOKEN)
        put("type", type)
        put("readOnly", readOnly)
        put("lobbyState", lobbyState)
        put("participantType", participantType)
        permissions?.let { put("permissions", it) }
    }

    private fun ok(data: JsonElement, status: Int = 200, lastGiven: Long? = null) = TalkTransportResponse(
        status,
        buildJsonObject { putJsonObject("ocs") { put("data", data) } }.toString().encodeToByteArray(),
        lastGiven,
    )

    companion object {
        const val TOKEN = "synthroom1"
        const val SELF = "self-user"
        const val NOW = 1_800_000_000L
        const val REFERENCE_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val ALL_FEATURES = listOf(
            "chat-replies",
            "reactions",
            "edit-messages",
            "delete-messages",
            "chat-reference-id",
            "chat-get-context",
            "chat-permission",
            "react-permission",
        )
    }
}
