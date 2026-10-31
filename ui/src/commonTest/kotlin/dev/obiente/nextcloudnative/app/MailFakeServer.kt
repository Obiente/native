package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers

/**
 * A synthetic Mail server for controller tests. It answers only the reviewed routes, keeps an
 * Outbox, and lets a test inject a status, a lost response, or a gate per route.
 */
internal class MailFakeServer {
    val requests = mutableListOf<NextcloudApiRequest>()
    val uploads = mutableListOf<NextcloudMultipartUploadRequest>()
    val outbox = mutableListOf<MailOutboxMessage>()
    var nextOutboxId = 500L

    /** Route key such as `POST /outbox` mapped to a forced status. */
    val forcedStatus = mutableMapOf<String, Int>()

    /** Routes whose request is applied on the server but whose response is lost. */
    val lostResponses = mutableSetOf<String>()

    /** Routes that fail before reaching the server. */
    val unreachable = mutableSetOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    var accountsBody = ACCOUNTS
    var mailboxesBody = MAILBOXES
    var messagesBody: (Map<String, String>) -> String = { query -> if (query["cursor"] == null) messages(101L..103L) else "[]" }
    var outboxSendStatus = MailOutboxStatus.Sent
    var storedAttachmentLimit = Int.MAX_VALUE

    fun transport() = MailTransport(::execute, ::upload, parseDispatcher = Dispatchers.Unconfined)

    fun count(route: String): Int = requests.count { it.route() == route }

    suspend fun execute(request: NextcloudApiRequest): NextcloudApiResponse {
        requests += request
        val route = request.route()
        gates[route]?.await()
        if (route in unreachable) throw IllegalStateException("network unavailable")
        forcedStatus[route]?.let { return response(it, "{}") }
        val result = handle(request, route)
        if (route in lostResponses) throw IllegalStateException("connection reset after the request was applied")
        return result
    }

    suspend fun upload(request: NextcloudMultipartUploadRequest): NextcloudApiResponse {
        uploads += request
        forcedStatus["UPLOAD"]?.let { return response(it, "{}") }
        return response(201, """{"id":${40 + uploads.size},"type":"local","fileName":"${request.file.displayName}"}""")
    }

    private fun handle(request: NextcloudApiRequest, route: String): NextcloudApiResponse {
        val body = request.body?.decodeToString()
        return when {
            route == "GET /accounts" -> response(200, accountsBody)
            route == "GET /mailboxes" -> response(200, mailboxesBody)
            route == "GET /messages" -> response(200, messagesBody(request.queryParameters))
            route.startsWith("GET /messages/") -> {
                val id = route.removePrefix("GET /messages/").removeSuffix("/body").toLong()
                response(200, detail(id))
            }
            route == "GET /outbox" -> response(200, outboxBody())
            route == "POST /outbox" -> {
                val stored = storeOutbox(requireNotNull(body))
                response(201, """{"status":"success","data":${stored.toJson()}}""")
            }
            route.startsWith("POST /outbox/") -> {
                val id = route.removePrefix("POST /outbox/").toLong()
                val message = outbox.firstOrNull { it.id == id } ?: return response(404, "{}")
                if (outboxSendStatus == MailOutboxStatus.Sent) {
                    outbox.remove(message)
                    response(202, """{"status":"success","data":"Message sent"}""")
                } else {
                    outbox[outbox.indexOf(message)] = message.copy(status = outboxSendStatus, failed = true)
                    response(500, """{"status":"error","message":"Could not send message"}""")
                }
            }
            route.startsWith("DELETE /outbox/") -> {
                val id = route.removePrefix("DELETE /outbox/").toLong()
                if (outbox.removeAll { it.id == id }) response(202, """{"status":"success","data":"Message deleted"}""") else response(404, "{}")
            }
            route == "POST /drafts" -> response(201, """{"status":"success","data":{"id":900,"accountId":3,"subject":"d"}}""")
            route.startsWith("POST /drafts/move/") -> response(202, """{"status":"success","data":"Message moved to IMAP"}""")
            route == "POST /tags" -> response(200, """{"id":9,"imapLabel":"${'$'}custom","displayName":"Custom","color":"#0082C9"}""")
            route == "GET /autoComplete" -> response(200, """[{"label":"Bob","email":"bob@example.test"}]""")
            request.method != NextcloudApiMethod.GET -> response(200, "[]")
            else -> response(404, "{}")
        }
    }

    private fun storeOutbox(body: String): MailOutboxMessage {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        fun recipients(key: String) = (json[key] as kotlinx.serialization.json.JsonArray).map { element ->
            val email = (element as kotlinx.serialization.json.JsonObject)["email"].toString().trim('"')
            MailRecipient(email)
        }
        val attachments = (json["attachments"] as kotlinx.serialization.json.JsonArray).size
        val message = MailOutboxMessage(
            id = nextOutboxId++,
            accountId = json["accountId"].toString().toLong(),
            subject = json["subject"].toString().trim('"'),
            sendAtEpochSeconds = json["sendAt"].toString().toLongOrNull(),
            to = recipients("to"),
            cc = recipients("cc"),
            bcc = recipients("bcc"),
            attachmentCount = attachments.coerceAtMost(storedAttachmentLimit),
            failed = false,
            status = MailOutboxStatus.Pending,
        )
        outbox += message
        return message
    }

    private fun outboxBody(): String =
        """{"status":"success","data":{"messages":[${outbox.joinToString(",") { it.toJson() }}]}}"""

    private fun MailOutboxMessage.toJson(): String {
        fun list(recipients: List<MailRecipient>) = recipients.joinToString(",", "[", "]") { """{"email":"${it.email}","label":"${it.email}"}""" }
        val attachments = (1..attachmentCount).joinToString(",", "[", "]") { """{"id":$it}""" }
        return """{"id":$id,"accountId":$accountId,"subject":"$subject","sendAt":${sendAtEpochSeconds ?: "null"},""" +
            """"to":${list(to)},"cc":${list(cc)},"bcc":${list(bcc)},"attachments":$attachments,"failed":$failed,"status":${status.wireValue}}"""
    }

    companion object {
        const val ACCOUNTS = """[{"accountId":3,"name":"Ada","emailAddress":"ada@example.test","signature":null,
            "editorMode":"plaintext","sentMailboxId":12,"trashMailboxId":13,"archiveMailboxId":14,"aliases":[]}]"""
        const val MAILBOXES = """{"id":3,"delimiter":"/","mailboxes":[
            {"databaseId":21,"accountId":3,"name":"INBOX","specialUse":["inbox"],"unread":2},
            {"databaseId":12,"accountId":3,"name":"Sent","specialUse":["sent"]},
            {"databaseId":13,"accountId":3,"name":"Trash","specialUse":["trash"]},
            {"databaseId":14,"accountId":3,"name":"Archive","specialUse":["archive"]},
            {"databaseId":30,"accountId":3,"name":"Projects","myAcls":"lrswipkxte"}]}"""

        fun messages(ids: LongRange, mailboxId: Long = 21): String = ids.joinToString(",", "[", "]") { id ->
            """{"databaseId":$id,"uid":$id,"mailboxId":$mailboxId,"subject":"Message $id","dateInt":${1_700_000_000 + id},
               "flags":{"seen":false,"flagged":false},"tags":[],"from":[{"email":"bob@example.test"}],"to":[],"cc":[],
               "messageId":"<m$id@example.test>"}"""
        }

        fun detail(id: Long): String =
            """{"databaseId":$id,"uid":$id,"mailboxId":21,"accountId":3,"subject":"Message $id","hasHtmlBody":false,
                "body":"Body $id","flags":{"seen":false},"from":[{"email":"bob@example.test"}],"to":[{"email":"ada@example.test"}],
                "cc":[],"messageId":"<m$id@example.test>","attachments":[]}"""

        fun response(status: Int, body: String) = NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
    }
}

/** `METHOD /path` relative to the Mail API, for example `POST /outbox/500`. */
internal fun NextcloudApiRequest.route(): String = "${method.name} ${relativePath.removePrefix(MAIL_API_PATH)}"
