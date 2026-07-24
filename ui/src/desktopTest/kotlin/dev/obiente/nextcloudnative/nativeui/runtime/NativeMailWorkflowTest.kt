package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeMailWorkflowTest {
    @Test
    fun messageBodyFacetKeepsEnvelopeActionsAndAttachments() {
        val messages = resource("messages", "Messages")
        val body = resource("message-body", "Body")
        val parent = NativeRecord(
            id = "42",
            values = mapOf(
                "subject" to "Native mail",
                "from" to """[{"name":"Ada","email":"ada@example.test"}]""",
                "accountId" to "7",
                "mailboxId" to "9",
                "unread" to "true",
            ),
        )
        val facet = NativeRecord(
            id = "42",
            values = mapOf(
                "body" to "<p>Hello <strong>world</strong></p>",
                "hasHtmlBody" to "true",
            ),
            structuredValues = mapOf(
                "attachments" to NativeStructuredValue.ListValue(
                    listOf(
                        NativeStructuredValue.ObjectValue(
                            entries = listOf(
                                NativeStructuredEntry(
                                    "fileName",
                                    "File name",
                                    NativeStructuredValue.Scalar("report.pdf", NativeStructuredScalarKind.string),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val schema = schema(resources = listOf(messages, body))

        val target = assertNotNull(
            nativeMailMessageRenderTarget(
                schema = schema,
                resource = body,
                record = facet,
                context = NativeDatasetContext(
                    parentResourceId = messages.id,
                    parentRecord = parent,
                ),
            ),
        )

        assertEquals(messages.id, target.resource.id)
        assertEquals("Native mail", target.presentation.subject)
        assertEquals("Ada <ada@example.test>", target.presentation.sender)
        assertTrue(target.presentation.htmlBody)
        assertEquals(1, target.presentation.attachmentCount)
        assertEquals("7", target.record.values["accountId"])
    }

    @Test
    fun verifiedFlagsMapAndDeleteBecomeDirectMessageActions() {
        val messages = resource("messages", "Messages")
        val setFlags = action(
            id = "messages-set-flags",
            method = HttpMethod.PUT,
            path = "/apps/mail/api/messages/{id}/flags",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            bodyFields = listOf("flags"),
        )
        val delete = action(
            id = "messages-destroy",
            method = HttpMethod.DELETE,
            path = "/apps/mail/api/messages/{id}",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
        )
        val plan = nativeMailMessageActionPlan(
            schema = schema(resources = listOf(messages), actions = listOf(setFlags, delete)),
            displayedResource = messages,
            displayedRecord = NativeRecord(
                id = "42",
                values = mapOf(
                    "subject" to "Unread message",
                    "from" to "sender@example.test",
                    "unread" to "true",
                ),
            ),
        )

        val markRead = assertNotNull(
            plan.stateActions.singleOrNull { item -> item.kind == NativeMailMessageActionKind.MarkRead },
        )
        assertEquals("""{"seen":true}""", markRead.request().values["flags"])
        assertEquals(NativeMailMessageActionKind.Delete, assertNotNull(plan.delete).kind)
        assertEquals("42", plan.delete.request().values["id"])
    }

    @Test
    fun moveFormBindsSourceContextAndUsesLabeledMailboxDestinations() {
        val destination = FieldSpec(
            id = "destFolderId",
            label = "Destination folder",
            kind = FieldKind.integer,
            required = true,
            readOnly = false,
        )
        val account = FieldSpec(
            id = "accountId",
            label = "Account",
            kind = FieldKind.integer,
            required = true,
            readOnly = false,
        )
        val messages = resource("messages", "Messages", listOf(destination, account))
        val mailboxes = resource("mailboxes", "Mailboxes")
        val move = action(
            id = "messages-move",
            method = HttpMethod.POST,
            path = "/apps/mail/api/messages/{id}/move",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            bodyFields = listOf("destFolderId", "accountId"),
        )
        val selectedMessage = NativeRecord(
            id = "42",
            values = mapOf("subject" to "Move me", "from" to "sender@example.test", "accountId" to "7", "mailboxId" to "9"),
        )
        val appSchema = schema(resources = listOf(messages, mailboxes), actions = listOf(move))

        assertEquals(
            mapOf("accountId" to "7"),
            nativeFormAutoBoundValues(move, messages, selectedMessage),
        )
        val options = nativeRelationOptions(
            field = destination,
            schema = appSchema,
            context = NativeDatasetContext(
                parentResourceId = messages.id,
                parentRecord = selectedMessage,
                relatedRecords = mapOf(
                    mailboxes.id to listOf(
                        NativeRecord("9", mapOf("databaseId" to "9", "accountId" to "7", "name" to "Inbox")),
                        NativeRecord("18", mapOf("databaseId" to "18", "accountId" to "7", "name" to "Archive")),
                        NativeRecord("21", mapOf("databaseId" to "21", "accountId" to "8", "name" to "Other account")),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Archive"), options.map(NativeRelationOption::label))
        assertEquals("18", options.single().value)
    }

    private fun resource(
        id: String,
        name: String,
        fields: List<FieldSpec> = emptyList(),
    ) = ResourceSpec(id, name, Confidence.verified, fields)

    private fun action(
        id: String,
        method: HttpMethod,
        path: String,
        intent: ActionIntent,
        risk: ActionRisk,
        bodyFields: List<String> = emptyList(),
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = "messages",
        binding = ApiBinding(
            method = method,
            path = path,
            operationId = id,
            pathParameterNames = listOf("id"),
            requiredPathParameterNames = listOf("id"),
            bodyFieldNames = bodyFields,
            requiredBodyFieldNames = bodyFields,
        ),
        intent = intent,
        risk = risk,
        requiresConfirmation = risk == ActionRisk.destructive,
        confidence = Confidence.verified,
    )

    private fun schema(
        resources: List<ResourceSpec>,
        actions: List<ActionSpec> = emptyList(),
    ) = NativeAppSchema(
        schemaVersion = "1",
        app = AppIdentity("mail", "Mail", "5.10.9"),
        confidence = Confidence.verified,
        resources = resources,
        views = emptyList(),
        actions = actions,
    )
}
