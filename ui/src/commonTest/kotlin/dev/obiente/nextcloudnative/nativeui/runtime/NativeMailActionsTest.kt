package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeMailActionsTest {
    @Test
    fun `message body uses its safe parent identity and unique archive mailbox`() {
        val messages = resource("emails", "Messages")
        val bodies = resource("content", "Message body")
        val folders = resource("folders", "Folders")
        val accounts = resource("accounts", "Accounts")
        val move = mutation(
            id = "route-message-move",
            label = "Move message",
            resourceId = messages.id,
            method = HttpMethod.POST,
            path = "/api/emails/{id}/move",
            pathFields = listOf("id"),
            bodyFields = listOf("destFolderId"),
        )
        val schema = schema(
            resources = listOf(messages, bodies, folders, accounts),
            actions = listOf(move),
        )
        val summary = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Quarterly notes",
                "from" to "Sam <sam@example.test>",
                "seen" to "false",
                "accountId" to "7",
            ),
        )
        val body = NativeRecord(
            id = "response-1",
            values = mapOf(
                "subject" to "Quarterly notes",
                "from" to "Sam <sam@example.test>",
                "body" to "Attached are the notes.",
            ),
            actionSafeIdentity = false,
        )
        val context = NativeDatasetContext(
            parentResourceId = messages.id,
            parentRecord = summary,
            relatedRecords = mapOf(
                accounts.id to listOf(
                    NativeRecord("7", mapOf("id" to "7", "archiveMailboxId" to "55")),
                    NativeRecord("9", mapOf("id" to "9", "archiveMailboxId" to "99")),
                ),
                folders.id to listOf(
                    NativeRecord(
                        "YXJjaGl2ZQ==",
                        mapOf(
                            "databaseId" to "55",
                            "accountId" to "7",
                            "name" to "Archive",
                            "specialRole" to "archive",
                        ),
                    ),
                ),
            ),
        )

        val plan = nativeMailMessageActionPlan(schema, bodies, body, context)
        val request = requireNotNull(plan.archive).request()

        assertEquals(NativeMailMessageActionKind.Archive, requireNotNull(plan.archive).kind)
        assertEquals(move, request.action)
        assertEquals("42", request.values["id"])
        assertEquals("55", request.values["destFolderId"])
        assertTrue(request.confirmed)
    }

    @Test
    fun `direct boolean state actions are semantic and app independent`() {
        val messages = resource("notifications", "Inbox messages")
        val read = mutation(
            id = "set-seen-state",
            label = "Set seen",
            resourceId = messages.id,
            method = HttpMethod.PUT,
            path = "/api/notifications/{notificationId}/seen",
            pathFields = listOf("notificationId"),
            bodyFields = listOf("seen"),
        )
        val flag = mutation(
            id = "update-favorite",
            label = "Update favorite",
            resourceId = messages.id,
            method = HttpMethod.PATCH,
            path = "/api/notifications/{notificationId}",
            pathFields = listOf("notificationId"),
            bodyFields = listOf("favorite"),
        )
        val schema = schema(listOf(messages), listOf(read, flag), appId = "support-inbox")
        val record = NativeRecord(
            "91",
            mapOf(
                "subject" to "Incident assigned",
                "sender" to "Operations",
                "body" to "Please investigate.",
                "seen" to "false",
                "favorite" to "false",
            ),
        )

        val plan = nativeMailMessageActionPlan(schema, messages, record)
        val readRequest = requireNotNull(
            plan.stateActions.single { it.kind == NativeMailMessageActionKind.MarkRead },
        ).request()
        val flagRequest = requireNotNull(
            plan.stateActions.single { it.kind == NativeMailMessageActionKind.Flag },
        ).request()

        assertEquals("91", readRequest.values["id"])
        assertEquals("true", readRequest.values["seen"])
        assertEquals("true", flagRequest.values["favorite"])
    }

    @Test
    fun `signed exact item mutation may bind only an observed numeric database identity`() {
        val messages = resource("messages", "Messages")
        val folders = resource("mailboxes", "Mailboxes")
        val move = mutation(
            "route-message-move",
            "Move message",
            messages.id,
            HttpMethod.POST,
            "/api/messages/{id}/move",
            listOf("id"),
            listOf("destFolderId"),
        ).copy(
            evidence = listOf(Evidence(EvidenceSource.verifiedAppPackage, "Signed package route")),
        )
        val schema = schema(listOf(messages, folders), listOf(move))
        val responseOnlyMessage = NativeRecord(
            id = "481",
            values = emptyMap(),
            displayValues = mapOf(
                "databaseId" to "481",
                "subject" to "Signed identity",
                "from" to "A",
                "body" to "B",
                "mailboxId" to "12",
            ),
            actionSafeIdentity = false,
        )
        val archive = NativeRecord(
            id = "opaque-protocol-id",
            values = emptyMap(),
            displayValues = mapOf(
                "databaseId" to "55",
                "accountId" to "7",
                "specialRole" to "archive",
                "name" to "Archive",
            ),
            actionSafeIdentity = false,
        )

        val plan = nativeMailMessageActionPlan(
            schema,
            messages,
            responseOnlyMessage,
            NativeDatasetContext(relatedRecords = mapOf(folders.id to listOf(archive))),
        )

        val archivePlan = requireNotNull(plan.archive)
        assertEquals("481", archivePlan.request().values["id"])
        assertEquals("55", archivePlan.request().values["destFolderId"])
        val nonNumeric = nativeMailMessageActionPlan(
            schema,
            messages,
            responseOnlyMessage.copy(displayValues = responseOnlyMessage.displayValues + ("databaseId" to "opaque")),
            NativeDatasetContext(relatedRecords = mapOf(folders.id to listOf(archive))),
        )
        assertTrue(nonNumeric.all.isEmpty())
    }

    @Test
    fun `ambiguous actions destinations and response identities stay read only`() {
        val messages = resource("messages", "Messages")
        val readOne = mutation(
            "mark-seen-one",
            "Mark seen",
            messages.id,
            HttpMethod.PUT,
            "/api/messages/{id}/seen",
            listOf("id"),
            listOf("seen"),
        )
        val readTwo = readOne.copy(
            id = "mark-seen-two",
            binding = readOne.binding.copy(operationId = "mark-seen-two"),
        )
        val move = mutation(
            "move-message",
            "Move message",
            messages.id,
            HttpMethod.POST,
            "/api/messages/{id}/move",
            listOf("id"),
            listOf("destMailboxId"),
        )
        val folders = resource("mailboxes", "Mailboxes")
        val schema = schema(listOf(messages, folders), listOf(readOne, readTwo, move))
        val record = NativeRecord(
            "8",
            mapOf("subject" to "Hello", "from" to "A", "body" to "B", "seen" to "false"),
        )
        val context = NativeDatasetContext(
            relatedRecords = mapOf(
                folders.id to listOf(
                    NativeRecord("31", mapOf("name" to "Archive", "specialRole" to "archive")),
                    NativeRecord("32", mapOf("name" to "Archives", "specialRole" to "archive")),
                ),
            ),
        )

        val ambiguous = nativeMailMessageActionPlan(schema, messages, record, context)
        val unsafe = nativeMailMessageActionPlan(
            schema,
            messages,
            record.copy(actionSafeIdentity = false),
            context,
        )

        assertTrue(ambiguous.stateActions.none { it.kind == NativeMailMessageActionKind.MarkRead })
        assertNull(ambiguous.archive)
        assertTrue(unsafe.all.isEmpty())
    }

    @Test
    fun `archive is omitted when another required input cannot be proven`() {
        val messages = resource("messages", "Messages")
        val folders = resource("folders", "Folders")
        val move = mutation(
            "move-message",
            "Move message",
            messages.id,
            HttpMethod.POST,
            "/api/messages/{id}/move",
            listOf("id"),
            listOf("destFolderId", "auditReason"),
        )
        val schema = schema(listOf(messages, folders), listOf(move))
        val record = NativeRecord(
            "8",
            mapOf("subject" to "Hello", "from" to "A", "body" to "B"),
        )
        val context = NativeDatasetContext(
            relatedRecords = mapOf(
                folders.id to listOf(
                    NativeRecord("55", mapOf("databaseId" to "55", "name" to "Archive")),
                ),
            ),
        )

        assertNull(nativeMailMessageActionPlan(schema, messages, record, context).archive)
    }

    private fun resource(id: String, name: String) = ResourceSpec(id, name, Confidence.verified)

    private fun schema(
        resources: List<ResourceSpec>,
        actions: List<ActionSpec>,
        appId: String = "generic-messaging",
    ) = NativeAppSchema(
        schemaVersion = "0.1",
        app = AppIdentity(appId, "Generic messaging", "1"),
        confidence = Confidence.verified,
        resources = resources,
        actions = actions,
    )

    private fun mutation(
        id: String,
        label: String,
        resourceId: String,
        method: HttpMethod,
        path: String,
        pathFields: List<String>,
        bodyFields: List<String>,
    ) = ActionSpec(
        id = id,
        label = label,
        resourceId = resourceId,
        binding = ApiBinding(
            method = method,
            path = path,
            operationId = id,
            pathParameterNames = pathFields,
            requiredPathParameterNames = pathFields,
            bodyFieldNames = bodyFields,
            requiredBodyFieldNames = bodyFields,
            bodyContentType = "application/json",
        ),
        intent = if (method == HttpMethod.POST) ActionIntent.create else ActionIntent.update,
        risk = ActionRisk.mutating,
        requiresConfirmation = true,
        confidence = Confidence.verified,
    )
}
