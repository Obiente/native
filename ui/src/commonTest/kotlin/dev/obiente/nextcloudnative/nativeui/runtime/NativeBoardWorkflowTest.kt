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

class NativeBoardWorkflowTest {
    private val cards = ResourceSpec("cards", "Cards", Confidence.verified)

    @Test
    fun `nested board retains empty lanes and exact creation context`() {
        val stacks = listOf(
            lane("10", "To do", "7", listOf(card("42", "10", "Existing card"))),
            lane("11", "Doing", "7", emptyList()),
        )
        val create = action(
            id = "cards.create",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            path = "/api/boards/{boardId}/stacks/{stackId}/cards",
            pathFields = listOf("boardId", "stackId"),
            bodyFields = listOf("title", "description"),
            requiredBodyFields = listOf("title"),
        )
        val schema = schema(create)

        val expanded = requireNotNull(
            expandNestedBoardDataset(
                schema = schema,
                resource = ResourceSpec("stacks", "Stacks", Confidence.verified),
                records = stacks,
            ),
        )

        assertEquals(listOf("To do", "Doing"), expanded.boardLanes?.map(NativeBoardLane::title))
        val emptyLane = requireNotNull(expanded.boardLanes?.last())
        assertTrue(emptyLane.records.isEmpty())
        val plan = requireNotNull(nativeBoardLaneCreatePlan(schema, expanded.resource, emptyLane))
        val request = plan.request("  Ship release  ", "  Validate both clients  ")

        assertEquals(create, request.action)
        assertEquals("7", request.values["boardId"])
        assertEquals("11", request.values["stackId"])
        assertEquals("Ship release", request.values["title"])
        assertEquals("Validate both clients", request.values["description"])
    }

    @Test
    fun `creation is refused when required parent identity is not exact or contracts tie`() {
        val create = action(
            id = "cards.create",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            path = "/api/boards/{boardId}/stacks/{stackId}/cards",
            pathFields = listOf("boardId", "stackId"),
            bodyFields = listOf("title"),
            requiredBodyFields = listOf("title"),
        )
        val laneWithoutBoard = NativeBoardLane(
            key = "11",
            title = "Doing",
            records = emptyList(),
            contextValues = mapOf("stackId" to "11"),
        )

        assertNull(nativeBoardLaneCreatePlan(schema(create), cards, laneWithoutBoard))
        assertNull(
            nativeBoardLaneCreatePlan(
                schema(create, create.copy(id = "cards.create.other")),
                cards,
                laneWithoutBoard.copy(contextValues = mapOf("stackId" to "11", "boardId" to "7")),
            ),
        )
    }

    @Test
    fun `direct lane create wins over an equivalent deeply nested signed route`() {
        val direct = action(
            id = "cards.create.direct",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            path = "/cards",
            bodyFields = listOf("title", "stackId", "description"),
            requiredBodyFields = listOf("title", "stackId"),
        )
        val nested = action(
            id = "cards.create.nested",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            path = "/api/v1.0/boards/{boardId}/stacks/{stackId}/cards",
            pathFields = listOf("boardId", "stackId"),
            bodyFields = listOf("title", "description"),
            requiredBodyFields = listOf("title"),
        )
        val lane = NativeBoardLane(
            key = "11",
            title = "Doing",
            records = emptyList(),
            contextValues = mapOf("stackId" to "11", "boardId" to "7"),
        )

        val plan = requireNotNull(nativeBoardLaneCreatePlan(schema(nested, direct), cards, lane))

        assertEquals(direct, plan.action)
        assertEquals("11", plan.request("New card").values["stackId"])
    }

    @Test
    fun `card state exposes only matching complete archive and confirmed delete actions`() {
        val complete = direct("cards.done", "/cards/{cardId}/done", HttpMethod.PUT, ActionRisk.mutating)
        val reopen = direct("cards.undone", "/cards/{cardId}/undone", HttpMethod.PUT, ActionRisk.mutating)
        val archive = action(
            id = "cards.archive",
            intent = ActionIntent.update,
            method = HttpMethod.PUT,
            path = "/boards/{boardId}/stacks/{stackId}/cards/{cardId}/archive",
            pathFields = listOf("boardId", "stackId", "cardId"),
        )
        val unarchive = direct("cards.unarchive", "/cards/{cardId}/unarchive", HttpMethod.PUT, ActionRisk.mutating)
        val delete = direct(
            "cards.delete",
            "/cards/{cardId}",
            HttpMethod.DELETE,
            ActionRisk.destructive,
            ActionIntent.delete,
        )
        val boardSchema = schema(complete, reopen, archive, unarchive, delete)
        val openCard = NativeRecord(
            id = "42",
            values = mapOf("id" to "42", "stackId" to "10", "done" to null, "archived" to "false"),
            bindingContext = mapOf("boardId" to "7"),
        )
        val lanes = listOf(
            NativeBoardLane(
                "10",
                "To do",
                listOf(openCard),
                contextValues = openCard.actionBindingValues() + ("stackId" to "10"),
            ),
        )

        val openPlan = nativeBoardCardActionPlan(boardSchema, cards, openCard, lanes)
        assertEquals(
            listOf(
                NativeBoardDirectActionKind.Complete,
                NativeBoardDirectActionKind.Archive,
                NativeBoardDirectActionKind.Delete,
            ),
            openPlan.directActions.map(NativeBoardDirectActionPlan::kind),
        )
        assertEquals("42", openPlan.directActions.first().request().values["cardId"])
        assertEquals(
            "7",
            openPlan.directActions.single { it.kind == NativeBoardDirectActionKind.Archive }
                .request().values["boardId"],
        )
        assertTrue(openPlan.directActions.all { it.request().confirmed })

        val closedCard = openCard.copy(
            values = openCard.values + ("done" to "1720000000") + ("archived" to "true"),
        )
        val closedPlan = nativeBoardCardActionPlan(
            boardSchema,
            cards,
            closedCard,
            lanes.map { lane -> lane.copy(records = listOf(closedCard)) },
        )
        assertEquals(
            listOf(
                NativeBoardDirectActionKind.Reopen,
                NativeBoardDirectActionKind.Unarchive,
                NativeBoardDirectActionKind.Delete,
            ),
            closedPlan.directActions.map(NativeBoardDirectActionPlan::kind),
        )
    }

    @Test
    fun `ambiguous direct mutation stays hidden`() {
        val first = direct("cards.done.one", "/cards/{cardId}/done", HttpMethod.PUT, ActionRisk.mutating)
        val second = first.copy(id = "cards.done.two")
        val record = NativeRecord(
            id = "42",
            values = mapOf("id" to "42", "stackId" to "10", "done" to "false"),
        )

        assertTrue(
            nativeBoardCardActionPlan(
                schema(first, second),
                cards,
                record,
                listOf(NativeBoardLane("10", "To do", listOf(record))),
            ).directActions.isEmpty(),
        )
    }

    @Test
    fun `card presentation skips transport mime type and uses meaningful metadata`() {
        val resource = ResourceSpec(
            id = "cards",
            name = "Cards",
            confidence = Confidence.verified,
            fields = listOf(
                field("title", FieldKind.string),
                field("type", FieldKind.string),
                field("duedate", FieldKind.dateTime),
            ),
        )
        val record = NativeRecord(
            id = "42",
            values = mapOf(
                "title" to "Ship release",
                "type" to "text/plain; charset=utf-8",
                "duedate" to "2026-08-01T12:00:00Z",
            ),
        )

        val presentation = nativeRecordPresentation(resource, record)

        assertEquals("Ship release", presentation.title)
        assertTrue(presentation.subtitle.orEmpty().contains("2026"))
    }

    private fun schema(vararg actions: ActionSpec) = NativeAppSchema(
        schemaVersion = "0.1",
        app = AppIdentity("workflow", "Workflow", "1"),
        confidence = Confidence.verified,
        resources = listOf(ResourceSpec("stacks", "Stacks", Confidence.verified), cards),
        actions = actions.toList(),
    )

    private fun action(
        id: String,
        intent: ActionIntent,
        method: HttpMethod,
        path: String,
        pathFields: List<String> = emptyList(),
        bodyFields: List<String> = emptyList(),
        requiredBodyFields: List<String> = emptyList(),
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = "cards",
        binding = ApiBinding(
            method = method,
            path = path,
            operationId = id,
            pathParameterNames = pathFields,
            requiredPathParameterNames = pathFields,
            bodyFieldNames = bodyFields,
            requiredBodyFieldNames = requiredBodyFields,
            bodyContentType = if (bodyFields.isEmpty()) null else "application/json",
        ),
        intent = intent,
        risk = if (intent == ActionIntent.delete) ActionRisk.destructive else ActionRisk.mutating,
        requiresConfirmation = intent == ActionIntent.delete,
        confidence = Confidence.verified,
    )

    private fun direct(
        id: String,
        path: String,
        method: HttpMethod,
        risk: ActionRisk,
        intent: ActionIntent = ActionIntent.execute,
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = "cards",
        binding = ApiBinding(
            method = method,
            path = path,
            operationId = id,
            pathParameterNames = listOf("cardId"),
            requiredPathParameterNames = listOf("cardId"),
        ),
        intent = intent,
        risk = risk,
        requiresConfirmation = risk == ActionRisk.destructive,
        confidence = Confidence.verified,
    )

    private fun lane(
        id: String,
        title: String,
        boardId: String,
        cards: List<NativeStructuredValue>,
    ) = NativeRecord(
        id = id,
        values = mapOf("id" to id, "boardId" to boardId, "title" to title),
        structuredValues = mapOf("cards" to NativeStructuredValue.ListValue(cards)),
    )

    private fun card(id: String, stackId: String, title: String) =
        NativeStructuredValue.ObjectValue(
            listOf(
                scalar("id", id, NativeStructuredScalarKind.number),
                scalar("stackId", stackId, NativeStructuredScalarKind.number),
                scalar("title", title),
            ),
        )

    private fun scalar(
        key: String,
        value: String,
        kind: NativeStructuredScalarKind = NativeStructuredScalarKind.string,
    ) = NativeStructuredEntry(key, key, NativeStructuredValue.Scalar(value, kind))

    private fun field(id: String, kind: FieldKind) = FieldSpec(
        id = id,
        label = id,
        kind = kind,
        required = false,
        readOnly = true,
    )
}
