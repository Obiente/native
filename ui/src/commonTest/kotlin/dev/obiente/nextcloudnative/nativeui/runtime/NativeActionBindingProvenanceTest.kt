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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeActionBindingProvenanceTest {
    @Test
    fun `safe action values reject request response and semantic alias conflicts`() {
        val requestConflict = NativeRecord(
            id = "item-7",
            values = mapOf("containerId" to "response-parent"),
            bindingContext = mapOf("containerId" to "request-parent"),
        )
        val semanticAliasConflict = NativeRecord(
            id = "item-7",
            values = mapOf("container_id" to "response-parent"),
            bindingContext = mapOf("containerId" to "request-parent"),
        )

        assertNull(requestConflict.safeActionBindingValues())
        assertNull(semanticAliasConflict.safeActionBindingValues())
    }

    @Test
    fun `safe action values retain confirming aliases and exact request names`() {
        val record = NativeRecord(
            id = "item-7",
            values = mapOf("container_id" to "parent-4", "title" to "Example"),
            bindingContext = mapOf("containerId" to "parent-4"),
        )

        assertEquals(
            mapOf(
                "containerId" to "parent-4",
                "container_id" to "parent-4",
                "title" to "Example",
                "id" to "item-7",
            ),
            record.safeActionBindingValues(),
        )
    }

    @Test
    fun `canonical identity may differ from a protocol id without disabling writes`() {
        val record = NativeRecord(
            id = "73",
            values = mapOf("databaseId" to "73", "id" to "protocol-1"),
        )

        assertEquals("73", record.actionBindingValues()["id"])
        assertEquals(
            mapOf("databaseId" to "73", "id" to "73"),
            record.safeActionBindingValues(),
        )
    }

    @Test
    fun `canonical identity rejects a conflicting request identity`() {
        val record = NativeRecord(
            id = "73",
            values = mapOf("id" to "protocol-1"),
            bindingContext = mapOf("id" to "72"),
        )

        assertNull(record.safeActionBindingValues())
        assertTrue(record.actionBindingValues().isEmpty())
    }

    @Test
    fun `board actions reject conflicting lane provenance`() {
        val resource = resource(
            "cards",
            listOf(
                field("title", FieldKind.string),
                field("stackId", FieldKind.integer),
            ),
        )
        val move = mutation(
            id = "move-card",
            resourceId = resource.id,
            method = HttpMethod.PUT,
            path = "/cards/{cardId}/move",
            pathFields = listOf("cardId"),
            bodyFields = listOf("stackId"),
            intent = ActionIntent.update,
        )
        val record = NativeRecord(
            id = "42",
            values = mapOf("title" to "Example", "stackId" to "10"),
            bindingContext = mapOf("boardId" to "7"),
        )
        val lanes = listOf(
            NativeBoardLane(
                key = "10",
                title = "To do",
                records = listOf(record),
                contextValues = mapOf("boardId" to "8", "stackId" to "10"),
            ),
            NativeBoardLane("11", "Doing", emptyList()),
        )

        val plan = nativeBoardCardActionPlan(schema(resource, move), resource, record, lanes)

        assertNull(plan.edit)
        assertNull(plan.move)
        assertTrue(plan.directActions.isEmpty())
    }

    @Test
    fun `lane creation rejects a context that disagrees with the selected lane`() {
        val resource = resource("cards")
        val create = mutation(
            id = "create-card",
            resourceId = resource.id,
            method = HttpMethod.POST,
            path = "/cards",
            bodyFields = listOf("stackId", "title"),
            intent = ActionIntent.create,
        )
        val lane = NativeBoardLane(
            key = "11",
            title = "Doing",
            records = emptyList(),
            contextValues = mapOf("stackId" to "10"),
        )

        assertNull(nativeBoardLaneCreatePlan(schema(resource, create), resource, lane))
    }

    @Test
    fun `flat board move cannot reuse a source lane path value as destination body value`() {
        val resource = resource(
            "cards",
            listOf(
                field("title", FieldKind.string),
                field("stackId", FieldKind.integer),
            ),
        )
        val move = mutation(
            id = "move-card",
            resourceId = resource.id,
            method = HttpMethod.PUT,
            path = "/stacks/{stackId}/cards/{cardId}",
            pathFields = listOf("stackId", "cardId"),
            bodyFields = listOf("stackId"),
            intent = ActionIntent.update,
        )
        val record = NativeRecord("42", mapOf("title" to "Example", "stackId" to "10"))
        val lanes = listOf(
            NativeBoardLane("10", "To do", listOf(record)),
            NativeBoardLane("11", "Doing", emptyList()),
        )

        assertNull(nativeBoardCardActionPlan(schema(resource, move), resource, record, lanes).move)
    }

    @Test
    fun `board actions do not promote a response only generic identity`() {
        val resource = resource(
            "cards",
            listOf(
                field("title", FieldKind.string),
                field("stackId", FieldKind.integer),
            ),
        )
        val move = mutation(
            id = "move-card",
            resourceId = resource.id,
            method = HttpMethod.PUT,
            path = "/cards/{cardId}/move",
            pathFields = listOf("cardId"),
            bodyFields = listOf("stackId"),
            intent = ActionIntent.update,
        )
        val record = NativeRecord(
            id = "42",
            values = mapOf("id" to "protocol-card", "title" to "Example", "stackId" to "10"),
            actionSafeIdentity = false,
        )
        val lanes = listOf(
            NativeBoardLane("10", "To do", listOf(record)),
            NativeBoardLane("11", "Doing", emptyList()),
        )

        assertNull(nativeBoardCardActionPlan(schema(resource, move), resource, record, lanes).move)
    }

    @Test
    fun `inline cell mutation rejects conflicting projected row context`() {
        val valueField = field("value", FieldKind.string)
        val resource = resource("rows", listOf(valueField))
        val update = mutation(
            id = "update-cell",
            resourceId = resource.id,
            method = HttpMethod.PATCH,
            path = "/rows/{rowId}",
            pathFields = listOf("rowId"),
            bodyFields = listOf("value"),
            intent = ActionIntent.update,
        )
        val record = NativeRecord(
            id = "row-7",
            values = mapOf("rowId" to "7", "value" to "Before"),
        )
        val projection = NativeTableProjection(
            resource = resource,
            records = listOf(record),
            cellsByRecord = mapOf(
                record.id to mapOf(
                    valueField.id to NativeProjectedCell(
                        sourceFieldId = "cells",
                        cellKey = "value",
                        value = "Before",
                        contextValues = mapOf("rowId" to "8"),
                        valueShape = NativeCellValueShape.scalar,
                        declaredKind = FieldKind.string,
                    ),
                ),
            ),
        )

        assertNull(nativeCellEditPlan(schema(resource, update), resource, projection, record, valueField))
    }

    @Test
    fun `inline cell mutation does not treat row identity as missing container identity`() {
        val valueField = field("value", FieldKind.string)
        val resource = resource("rows", listOf(valueField))
        val update = mutation(
            id = "update-cell",
            resourceId = resource.id,
            method = HttpMethod.PATCH,
            path = "/containers/{containerId}/rows/{rowId}",
            pathFields = listOf("containerId", "rowId"),
            bodyFields = listOf("value"),
            intent = ActionIntent.update,
        )
        val record = NativeRecord(
            id = "row-7",
            values = mapOf("value" to "Before"),
        )
        val projection = NativeTableProjection(
            resource = resource,
            records = listOf(record),
            cellsByRecord = mapOf(
                record.id to mapOf(
                    valueField.id to NativeProjectedCell(
                        sourceFieldId = "cells",
                        cellKey = "value",
                        value = "Before",
                        contextValues = emptyMap(),
                        valueShape = NativeCellValueShape.scalar,
                        declaredKind = FieldKind.string,
                    ),
                ),
            ),
        )

        assertNull(nativeCellEditPlan(schema(resource, update), resource, projection, record, valueField))
    }

    @Test
    fun `mail mutations use canonical identity instead of a protocol id`() {
        val messages = resource("messages")
        val markRead = mutation(
            id = "mark-seen",
            resourceId = messages.id,
            method = HttpMethod.PUT,
            path = "/messages/{id}/seen",
            pathFields = listOf("id"),
            bodyFields = listOf("seen"),
            intent = ActionIntent.update,
        )
        val record = NativeRecord(
            id = "8",
            values = mapOf(
                "id" to "9",
                "subject" to "Hello",
                "from" to "A",
                "body" to "B",
                "seen" to "false",
            ),
            bindingContext = mapOf("id" to "8"),
        )

        val request = requireNotNull(
            nativeMailMessageActionPlan(schema(messages, markRead), messages, record)
                .stateActions
                .singleOrNull(),
        ).request()

        assertEquals("8", request.values["id"])
        assertEquals("true", request.values["seen"])
    }

    @Test
    fun `mail mutations reject conflicting canonical request identity`() {
        val messages = resource("messages")
        val markRead = mutation(
            id = "mark-seen",
            resourceId = messages.id,
            method = HttpMethod.PUT,
            path = "/messages/{id}/seen",
            pathFields = listOf("id"),
            bodyFields = listOf("seen"),
            intent = ActionIntent.update,
        )
        val record = NativeRecord(
            id = "8",
            values = mapOf(
                "id" to "protocol-message",
                "subject" to "Hello",
                "from" to "A",
                "body" to "B",
                "seen" to "false",
            ),
            bindingContext = mapOf("id" to "7"),
        )

        assertTrue(nativeMailMessageActionPlan(schema(messages, markRead), messages, record).all.isEmpty())
    }

    private fun schema(resource: ResourceSpec, vararg actions: ActionSpec) = NativeAppSchema(
        schemaVersion = "1",
        app = AppIdentity("synthetic", "Synthetic", "1"),
        confidence = Confidence.verified,
        resources = listOf(resource),
        actions = actions.toList(),
    )

    private fun resource(id: String, fields: List<FieldSpec> = emptyList()) = ResourceSpec(
        id = id,
        name = id,
        confidence = Confidence.verified,
        fields = fields,
    )

    private fun field(id: String, kind: FieldKind) = FieldSpec(
        id = id,
        label = id,
        kind = kind,
        required = false,
        readOnly = false,
    )

    private fun mutation(
        id: String,
        resourceId: String,
        method: HttpMethod,
        path: String,
        pathFields: List<String> = emptyList(),
        bodyFields: List<String>,
        intent: ActionIntent,
    ) = ActionSpec(
        id = id,
        label = id,
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
        intent = intent,
        risk = ActionRisk.mutating,
        requiresConfirmation = true,
        confidence = Confidence.verified,
    )
}
