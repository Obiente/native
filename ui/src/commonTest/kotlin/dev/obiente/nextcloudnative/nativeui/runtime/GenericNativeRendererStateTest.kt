package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.CompositeDataGridSpec
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericNativeRendererStateTest {
    @Test
    fun nativeMailKeepsSearchInsideItsWorkspace() {
        val ready = NativeScreenState.Ready(
            listOf(
                NativeRecord(id = "1", values = emptyMap()),
                NativeRecord(id = "2", values = emptyMap()),
            ),
        )

        assertTrue(
            genericCollectionSearchAvailable(
                state = ready,
                recordCount = 2,
                surface = GenericNativeSurface.Mailbox,
                nativeMailWorkspaceEligible = false,
            ),
        )
        assertFalse(
            genericCollectionSearchAvailable(
                state = ready,
                recordCount = 2,
                surface = GenericNativeSurface.Mailbox,
                nativeMailWorkspaceEligible = true,
            ),
        )
    }

    @Test
    fun dedicatedCollectionUsesTheSameFilteredRecordsAsItsSearchBar() {
        val first = NativeRecord(id = "1", values = mapOf("name" to "Kitchen"))
        val second = NativeRecord(id = "2", values = mapOf("name" to "Garden"))

        val filtered = assertIs<NativeScreenState.Ready>(
            nativeDedicatedCollectionState(
                state = NativeScreenState.Ready(listOf(first, second)),
                presentedRecords = listOf(first, second),
                visiblePresentedRecords = listOf(second),
                searchableCollection = true,
            ),
        )
        val unfiltered = assertIs<NativeScreenState.Ready>(
            nativeDedicatedCollectionState(
                state = NativeScreenState.Ready(listOf(first, second)),
                presentedRecords = listOf(first, second),
                visiblePresentedRecords = listOf(second),
                searchableCollection = false,
            ),
        )

        assertEquals(listOf(second), filtered.records)
        assertEquals(listOf(first, second), unfiltered.records)
    }

    @Test
    fun enumSearchMatchesTheLabelsShownToUsers() {
        val field = FieldSpec(
            id = "repeat",
            label = "Repeat",
            kind = FieldKind.enumeration,
            required = true,
            readOnly = false,
            enumValues = listOf("d:1", "w:1", "m:1"),
            enumLabels = mapOf(
                "d:1" to "Every day",
                "w:1" to "Every week",
                "m:1" to "Every month",
            ),
        )

        assertEquals(listOf("w:1"), nativeEnumOptionsMatchingQuery(field, "week"))
        assertEquals(listOf("d:1", "w:1", "m:1"), nativeEnumOptionsMatchingQuery(field, "every"))
        assertEquals(listOf("w:1"), nativeEnumOptionsMatchingQuery(field, "w:1"))
        assertEquals("Every week", nativeEnumOptionLabel(field, "w:1"))
        assertEquals("Custom Value", nativeEnumOptionLabel(field, "custom-value"))
    }

    @Test
    fun collectionSearchMatchesMeaningfulRecordContentAndIgnoresTechnicalFields() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.integer),
                field("title", FieldKind.string),
                field("description", FieldKind.string),
                field("categoryId", FieldKind.integer),
                field("sortOrder", FieldKind.integer),
            ),
        )
        val record = NativeRecord(
            id = "server-record-91",
            values = mapOf(
                "id" to "91",
                "title" to "Weekend groceries",
                "description" to "Fresh fruit and bread",
                "categoryId" to "742",
                "sortOrder" to "1200",
            ),
            displayValues = mapOf("description" to "Fresh fruit and bread"),
        )

        assertTrue(nativeRecordMatchesCollectionQuery(resource, record, "weekend bread"))
        assertTrue(nativeRecordMatchesCollectionQuery(resource, record, "FRUIT"))
        assertFalse(nativeRecordMatchesCollectionQuery(resource, record, "742"))
        assertFalse(nativeRecordMatchesCollectionQuery(resource, record, "1200"))
        assertFalse(nativeRecordMatchesCollectionQuery(resource, record, "server-record-91"))
    }

    @Test
    fun formHidesOptionalServerOrderingButKeepsRequiredOrderingInputs() {
        val visible = nativeFormDisplayFields(
            listOf(
                field("title", FieldKind.string, required = true),
                field("sortOrder", FieldKind.integer),
                field("position", FieldKind.integer),
                field("displayIndex", FieldKind.integer),
                field("rank", FieldKind.integer, required = true),
                field("quantity", FieldKind.integer),
            ),
        )

        assertEquals(listOf("title", "rank", "quantity"), visible.map(FieldSpec::id))
    }

    @Test
    fun formColorOptionsProduceOrdinaryOpaqueArgbValues() {
        val colorField = FieldSpec(
            id = "color",
            label = "Color",
            kind = FieldKind.enumeration,
            required = false,
            readOnly = false,
            enumValues = listOf("f97316"),
        )

        assertEquals(0xFFF97316.toInt(), "f97316".nativeFormColorArgbOrNull(colorField))
        assertEquals(0xFFF97316.toInt(), "#F97316".nativeFormColorArgbOrNull(colorField))
        assertNull("not-a-color".nativeFormColorArgbOrNull(colorField))
        assertNull(
            "f97316".nativeFormColorArgbOrNull(colorField.copy(id = "status")),
        )
    }

    @Test
    fun datasetInsightsDefaultToTransactionsOnPhoneSizedViewports() {
        assertFalse(datasetInsightsDefaultExpanded(widthDp = 412f, heightDp = 915f))
        assertFalse(datasetInsightsDefaultExpanded(widthDp = 915f, heightDp = 412f))
        assertTrue(datasetInsightsDefaultExpanded(widthDp = 1280f, heightDp = 800f))
    }

    @Test
    fun `mail html becomes inert readable text`() {
        val html = """
            <html><head><style>.hidden { color: red; }</style></head>
            <body><h2>Hello &amp; welcome</h2><p>First&nbsp;line<br>Second line</p>
            <script>stealCredentials()</script></body></html>
        """.trimIndent()

        val plain = emailBodyToPlainText(html)

        assertTrue(plain.contains("Hello & welcome"))
        assertTrue(plain.contains("First line\nSecond line"))
        assertFalse(plain.contains("color: red"))
        assertFalse(plain.contains("stealCredentials"))
    }

    @Test
    fun `ISO recipe durations are presented as readable native durations`() {
        val field = field("prepTime", FieldKind.string).copy(label = "Preparation time")

        assertEquals("1 hr 5 min", formatNativeField(field, "PT1H5M0S").displayValue)
        assertEquals("50 min", formatNativeField(field, "PT0H50M0S").displayValue)
        assertEquals("2 days 3 hr", "P2DT3H".formatIsoDuration())
        assertNull("not-a-duration".formatIsoDuration())
    }

    @Test
    fun buildsSectionedDetailFromPrimitiveMetadataListsAndNestedGroups() {
        val resource = ResourceSpec(
            id = "guides",
            name = "Guides",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.string),
                field("title", FieldKind.string),
                field("supplies", FieldKind.objectValue),
                field("instructions", FieldKind.objectValue),
                field("facts", FieldKind.objectValue),
            ),
        )
        val record = NativeRecord(
            id = "guide-7",
            values = mapOf("id" to "guide-7", "title" to "Replace a wheel"),
            structuredValues = mapOf(
                "supplies" to NativeStructuredValue.ListValue(
                    listOf(structuredScalar("Jack"), structuredScalar("Wrench")),
                ),
                "instructions" to NativeStructuredValue.ListValue(
                    listOf(structuredScalar("Secure the vehicle"), structuredScalar("Raise the vehicle")),
                ),
                "facts" to NativeStructuredValue.ObjectValue(
                    listOf(
                        NativeStructuredEntry("duration", "Duration", structuredScalar("25 min")),
                        NativeStructuredEntry("difficulty", "Difficulty", structuredScalar("Medium")),
                    ),
                ),
            ),
        )

        val detail = nativeStructuredDetail(resource, record)

        assertEquals(listOf("id", "title"), detail.fields.map(NativeDetailFieldPresentation::fieldId))
        assertEquals(listOf("supplies", "instructions", "facts"), detail.sections.map { it.fieldId })
        assertFalse(detail.sections.single { it.fieldId == "supplies" }.ordered)
        assertTrue(detail.sections.single { it.fieldId == "instructions" }.ordered)
        assertFalse(detail.sections.single { it.fieldId == "facts" }.ordered)
    }

    @Test
    fun `account overview hides credentials and connection internals but keeps identity`() {
        val resource = ResourceSpec(
            id = "accounts",
            name = "Accounts",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.integer),
                field("name", FieldKind.string),
                field("email", FieldKind.string),
                field("imapHost", FieldKind.string),
                field("imapPort", FieldKind.integer),
                field("smtpServer", FieldKind.string),
                field("password", FieldKind.string),
                field("oauthAccessToken", FieldKind.string),
                field("signature", FieldKind.string),
            ),
        )
        val record = NativeRecord(
            id = "7",
            values = resource.fields.associate { field -> field.id to "server-value" },
        )

        val visible = nativeStructuredDetail(resource, record).fields.map(NativeDetailFieldPresentation::fieldId)

        assertEquals(listOf("id", "name", "email", "signature"), visible)
    }

    @Test
    fun `generic ingredient and direction pairs receive reusable recipe detail semantics`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.high,
            fields = listOf(
                field("title", FieldKind.string),
                field("imageUrl", FieldKind.string),
                field("ingredients", FieldKind.objectValue),
                field("directions", FieldKind.objectValue),
                field("equipment", FieldKind.objectValue),
            ),
        )
        val record = NativeRecord(
            id = "entry-7",
            values = mapOf(
                "title" to "Shape-based recipe",
                "imageUrl" to "/apps/example/api/image/7",
            ),
            structuredValues = mapOf(
                "ingredients" to NativeStructuredValue.ListValue(
                    listOf(structuredScalar("One ingredient")),
                ),
                "directions" to NativeStructuredValue.ListValue(
                    listOf(structuredScalar("One direction")),
                ),
                "equipment" to NativeStructuredValue.ListValue(
                    listOf(structuredScalar("One tool")),
                ),
            ),
        )

        val detail = nativeStructuredDetail(resource, record)

        assertTrue(detail.fields.none { field -> field.fieldId == "imageUrl" })
        assertEquals(
            listOf("Ingredients", "Instructions", "Tools"),
            detail.sections.map(NativeStructuredDetailSection::label),
        )
        assertFalse(detail.sections[0].ordered)
        assertTrue(detail.sections[1].ordered)
    }

    @Test
    fun mapsSchemaComponentsToTypedGenericSurfaces() {
        assertEquals(GenericNativeSurface.List, view(NativeComponent.collectionList).genericSurface())
        assertEquals(GenericNativeSurface.Grid, view(NativeComponent.mediaGrid).genericSurface())
        assertEquals(GenericNativeSurface.Board, view(NativeComponent.board).genericSurface())
        assertEquals(GenericNativeSurface.Insights, view(NativeComponent.dashboard).genericSurface())
        assertEquals(GenericNativeSurface.Table, view(NativeComponent.dataTable).genericSurface())
        assertEquals(GenericNativeSurface.Detail, view(NativeComponent.detail).genericSurface())
        assertEquals(GenericNativeSurface.Form, view(NativeComponent.form).genericSurface())
    }

    @Test
    fun `verified budget category lists keep their category presentation when numeric fields are observed`() {
        val shapeOnly = view(NativeComponent.collectionList)
        val budgetCategories = ResourceSpec(
            id = "categories",
            name = "Categories",
            confidence = Confidence.high,
            fields = listOf(
                field("budgetAmount", FieldKind.currency, format = "EUR"),
                field("budgetPeriod", FieldKind.enumeration),
            ),
        )
        val financeRecords = listOf(
            NativeRecord("one", mapOf("budgetAmount" to "50", "budgetPeriod" to "monthly")),
            NativeRecord("two", mapOf("budgetAmount" to "25", "budgetPeriod" to "weekly")),
        )

        assertEquals(GenericNativeSurface.List, shapeOnly.genericSurface(budgetCategories, financeRecords))
        assertEquals(
            GenericNativeSurface.List,
            shapeOnly.genericSurface(
                budgetCategories.copy(id = "inventory", name = "Inventory"),
                financeRecords,
            ),
        )
    }

    @Test
    fun `transaction collections keep the ledger list instead of generic insights`() {
        val bills = ResourceSpec(
            id = "bills",
            name = "Bills",
            confidence = Confidence.high,
            fields = listOf(
                field("what", FieldKind.string),
                field("amount", FieldKind.currency),
                field("payer_id", FieldKind.integer),
            ),
        )
        val records = listOf(
            NativeRecord(
                "11",
                mapOf("what" to "Shared groceries", "amount" to "42.50", "payer_id" to "7"),
            ),
        )

        assertEquals(
            GenericNativeSurface.List,
            view(NativeComponent.collectionList).genericSurface(bills, records),
        )
        assertEquals(
            GenericNativeSurface.List,
            view(NativeComponent.dashboard).genericSurface(bills, records),
        )
    }

    @Test
    fun `only structured object wrappers auto open as details`() {
        val structuredWrapper = NativeRecord(
            id = "record",
            values = emptyMap(),
            actionSafeIdentity = false,
            structuredValues = mapOf(
                "stats" to NativeStructuredValue.ListValue(
                    items = listOf(
                        NativeStructuredValue.Scalar("42", NativeStructuredScalarKind.number),
                    ),
                ),
            ),
        )
        val realSingleton = NativeRecord(
            id = "11",
            values = mapOf("what" to "Only bill"),
            actionSafeIdentity = true,
        )

        assertTrue(shouldAutoOpenSyntheticRecord(listOf(structuredWrapper)))
        assertFalse(shouldAutoOpenSyntheticRecord(listOf(realSingleton)))
        assertFalse(shouldAutoOpenSyntheticRecord(listOf(structuredWrapper, realSingleton)))
    }

    @Test
    fun boardLanesAreInferredAndCardsKeepServerLaneOrderWhileRespectingPositions() {
        val resource = ResourceSpec(
            id = "work-items",
            name = "Work items",
            confidence = Confidence.high,
            fields = listOf(
                field("title", FieldKind.string),
                field("stage", FieldKind.enumeration),
                field("position", FieldKind.integer),
            ),
        )
        val records = listOf(
            NativeRecord("doing-later", mapOf("title" to "Second", "stage" to "doing", "position" to "20")),
            NativeRecord("todo", mapOf("title" to "First", "stage" to "todo", "position" to "10")),
            NativeRecord("doing-first", mapOf("title" to "Urgent", "stage" to "doing", "position" to "5")),
        )

        val lanes = nativeBoardLanes(resource, records)

        assertEquals(listOf("Doing", "Todo"), lanes.map { it.title })
        assertEquals(listOf("doing-first", "doing-later"), lanes.first().records.map { it.id })
    }

    @Test
    fun `nested lane records become reusable cards with navigation context`() {
        fun scalar(key: String, value: String, kind: NativeStructuredScalarKind = NativeStructuredScalarKind.string) =
            NativeStructuredEntry(key, key, NativeStructuredValue.Scalar(value, kind))
        val stacks = listOf(
            NativeRecord(
                id = "10",
                values = mapOf("id" to "10", "boardId" to "7", "title" to "To do"),
                structuredValues = mapOf(
                    "cards" to NativeStructuredValue.ListValue(
                        listOf(
                            NativeStructuredValue.ObjectValue(
                                listOf(
                                    scalar("id", "42", NativeStructuredScalarKind.number),
                                    scalar("stackId", "10", NativeStructuredScalarKind.number),
                                    scalar("title", "Ship adaptive boards"),
                                    scalar("order", "2", NativeStructuredScalarKind.number),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val expanded = requireNotNull(
            expandNestedBoardDataset(
                ResourceSpec("stacks", "Stacks", Confidence.high),
                stacks,
            ),
        )
        val card = expanded.records.single()

        assertEquals("cards", expanded.resource.id)
        assertEquals("42", card.id)
        assertEquals("cards", card.effectiveNativeResourceId("stacks"))
        assertEquals("7", card.values["boardId"])
        assertEquals("10", card.values["stackId"])
        assertEquals("To do", card.displayValues["stackId"])
        assertEquals(listOf("To do"), nativeBoardLanes(expanded.resource, expanded.records).map { it.title })
        assertFalse(card.actionSafeIdentity)

        val declaredCards = ResourceSpec(
            id = "card",
            name = "Cards",
            confidence = Confidence.verified,
            fields = listOf(
                field("id", FieldKind.integer, readOnly = true),
                field("title", FieldKind.string),
                field("stackId", FieldKind.integer),
                field("order", FieldKind.integer),
            ),
        )
        val schemaAware = requireNotNull(
            expandNestedBoardDataset(
                schema = NativeAppSchema(
                    "0.1",
                    AppIdentity("workflow", "Workflow", "1"),
                    Confidence.verified,
                    resources = listOf(ResourceSpec("stacks", "Stacks", Confidence.high), declaredCards),
                ),
                resource = ResourceSpec("stacks", "Stacks", Confidence.high),
                records = stacks,
            ),
        )
        assertEquals("card", schemaAware.resource.id)
        assertTrue(schemaAware.records.single().actionSafeIdentity)
    }

    @Test
    fun `sparse signed card routes make nested board cards safely editable and movable`() {
        fun scalar(key: String, value: String, kind: NativeStructuredScalarKind = NativeStructuredScalarKind.string) =
            NativeStructuredEntry(key, key, NativeStructuredValue.Scalar(value, kind))
        fun card(id: String, stackId: String, title: String, order: String) =
            NativeStructuredValue.ObjectValue(
                listOf(
                    scalar("id", id, NativeStructuredScalarKind.number),
                    scalar("stackId", stackId, NativeStructuredScalarKind.number),
                    scalar("title", title),
                    scalar("order", order, NativeStructuredScalarKind.number),
                ),
            )
        val stacks = listOf(
            NativeRecord(
                id = "10",
                values = mapOf("id" to "10", "boardId" to "7", "title" to "To do"),
                structuredValues = mapOf("cards" to NativeStructuredValue.ListValue(listOf(card("42", "10", "Ship", "2")))),
            ),
            NativeRecord(
                id = "11",
                values = mapOf("id" to "11", "boardId" to "7", "title" to "Doing"),
                structuredValues = mapOf("cards" to NativeStructuredValue.ListValue(listOf(card("43", "11", "Test", "1")))),
            ),
        )
        val cards = ResourceSpec("cards", "Cards", Confidence.verified)
        val rename = ActionSpec(
            id = "route-card-rename",
            label = "Rename",
            resourceId = "cards",
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/apps/workflow/cards/{cardId}/rename",
                operationId = "route.card.rename",
                pathParameterNames = listOf("cardId"),
                requiredPathParameterNames = listOf("cardId"),
                bodyFieldNames = listOf("title"),
                requiredBodyFieldNames = listOf("title"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = true,
            confidence = Confidence.verified,
        )
        val reorder = ActionSpec(
            id = "route-card-reorder",
            label = "Reorder",
            resourceId = "cards",
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/apps/workflow/cards/{cardId}/reorder",
                operationId = "route.card.reorder",
                pathParameterNames = listOf("cardId"),
                requiredPathParameterNames = listOf("cardId"),
                bodyFieldNames = listOf("stackId", "order"),
                requiredBodyFieldNames = listOf("stackId", "order"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = true,
            confidence = Confidence.verified,
        )
        val schema = NativeAppSchema(
            "0.1",
            AppIdentity("workflow", "Workflow", "1"),
            Confidence.verified,
            resources = listOf(ResourceSpec("stacks", "Stacks", Confidence.verified), cards),
            actions = listOf(rename, reorder),
        )

        val expanded = requireNotNull(
            expandNestedBoardDataset(
                schema = schema,
                resource = ResourceSpec("stacks", "Stacks", Confidence.verified),
                records = stacks,
            ),
        )
        val lanes = nativeBoardLanes(expanded.resource, expanded.records)
        val record = expanded.records.first { it.id == "42" }
        val plan = nativeBoardCardActionPlan(schema, expanded.resource, record, lanes)

        assertTrue(record.actionSafeIdentity)
        assertFalse(expanded.resource.fields.single { it.id == "title" }.readOnly)
        assertEquals(rename, requireNotNull(plan.edit).action)
        assertEquals(reorder, requireNotNull(plan.move).action)
        assertEquals(
            mapOf("id" to "42", "title" to "Shipped", "stackId" to "10", "order" to "2", "boardId" to "7",
                NATIVE_SYNTHETIC_RESOURCE_FIELD to "cards"),
            plan.edit.request(mapOf("title" to "Shipped")).values,
        )
        assertEquals("11", plan.move.request("11").values["stackId"])
        assertEquals("2", plan.move.request("11").values["order"])
    }

    @Test
    fun `board card actions bind declared edit and move contracts`() {
        val resource = ResourceSpec(
            id = "cards",
            name = "Cards",
            confidence = Confidence.high,
            fields = listOf(
                field("title", FieldKind.string),
                field("description", FieldKind.longText),
                field("stackId", FieldKind.integer),
                field("order", FieldKind.integer),
                field("boardId", FieldKind.integer, readOnly = true),
            ),
        )
        val edit = ActionSpec(
            id = "cards.update",
            label = "Edit card",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/api/boards/{boardId}/stacks/{stackId}/cards/{cardId}",
                operationId = "updateCard",
                pathParameterNames = listOf("boardId", "stackId", "cardId"),
                requiredPathParameterNames = listOf("boardId", "stackId", "cardId"),
                bodyFieldNames = listOf("title", "description"),
                requiredBodyFieldNames = listOf("title"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val move = ActionSpec(
            id = "cards.reorder",
            label = "Move card",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/api/cards/{cardId}/reorder",
                operationId = "reorderCard",
                pathParameterNames = listOf("cardId"),
                requiredPathParameterNames = listOf("cardId"),
                bodyFieldNames = listOf("stackId", "order", "boardId"),
                requiredBodyFieldNames = listOf("stackId", "order"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("workflow", "Workflow", "1"),
            confidence = Confidence.verified,
            resources = listOf(resource),
            actions = listOf(edit, move),
        )
        val record = NativeRecord(
            id = "42",
            values = mapOf(
                "title" to "Ship board",
                "description" to "Finish adaptive workflow",
                "stackId" to "10",
                "order" to "3",
                "boardId" to "7",
            ),
        )
        val lanes = listOf(
            NativeBoardLane("10", "To do", listOf(record)),
            NativeBoardLane("11", "Doing", emptyList()),
        )

        val plan = nativeBoardCardActionPlan(schema, resource, record, lanes)
        val editRequest = requireNotNull(plan.edit).request(
            mapOf("title" to "Ship native board", "description" to "Ready"),
        )
        val moveRequest = requireNotNull(plan.move).request("11")

        assertEquals(edit, editRequest.action)
        assertEquals("42", editRequest.values["id"])
        assertEquals("Ship native board", editRequest.values["title"])
        assertEquals("Ready", editRequest.values["description"])
        assertEquals(move, moveRequest.action)
        assertEquals("11", moveRequest.values["stackId"])
        assertEquals("3", moveRequest.values["order"])
        assertEquals("7", moveRequest.values["boardId"])
        assertTrue(moveRequest.confirmed)
    }

    @Test
    fun `board mutations stay hidden for response-only identity or ambiguous move contracts`() {
        val resource = ResourceSpec(
            "cards",
            "Cards",
            Confidence.high,
            fields = listOf(field("title", FieldKind.string), field("stackId", FieldKind.integer)),
        )
        fun move(id: String) = ActionSpec(
            id = id,
            label = "Move card",
            resourceId = "cards",
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/api/cards/{cardId}/reorder",
                operationId = id,
                pathParameterNames = listOf("cardId"),
                requiredPathParameterNames = listOf("cardId"),
                bodyFieldNames = listOf("stackId"),
                requiredBodyFieldNames = listOf("stackId"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.high,
        )
        val record = NativeRecord("42", mapOf("title" to "Card", "stackId" to "1"))
        val lanes = listOf(
            NativeBoardLane("1", "One", listOf(record)),
            NativeBoardLane("2", "Two", emptyList()),
        )
        val schema = NativeAppSchema(
            "0.1",
            AppIdentity("workflow", "Workflow", "1"),
            Confidence.high,
            resources = listOf(resource),
            actions = listOf(move("move.one"), move("move.two")),
        )

        assertNull(nativeBoardCardActionPlan(schema, resource, record, lanes).move)
        assertEquals(
            NativeBoardCardActionPlan(null, null),
            nativeBoardCardActionPlan(schema, resource, record.copy(actionSafeIdentity = false), lanes),
        )
    }

    @Test
    fun `board move remains unconfirmed when refresh returns unchanged lanes`() {
        val card = NativeRecord("42", mapOf("title" to "Card", "stackId" to "1"))
        val before = listOf(
            NativeBoardLane("1", "One", listOf(card)),
            NativeBoardLane("2", "Two", emptyList()),
        )
        val fingerprint = nativeBoardFingerprint(before)

        assertEquals(
            NativeBoardMoveVerification.WaitingForRefresh,
            verifyNativeBoardMove(before, "42", "2", fingerprint, refreshCompleted = false),
        )
        assertEquals(
            NativeBoardMoveVerification.NotMoved,
            verifyNativeBoardMove(before, "42", "2", fingerprint, refreshCompleted = true),
        )
        assertEquals(
            NativeBoardMoveVerification.Confirmed,
            verifyNativeBoardMove(
                lanes = listOf(
                    NativeBoardLane("1", "One", emptyList()),
                    NativeBoardLane("2", "Two", listOf(card.copy(values = card.values + ("stackId" to "2")))),
                ),
                recordId = "42",
                targetLaneKey = "2",
                beforeFingerprint = fingerprint,
                refreshCompleted = true,
            ),
        )
    }

    @Test
    fun `board move reconciliation survives loading until refreshed lanes are verified`() {
        val reconciliation = NativeBoardMoveReconciliation()
        reconciliation.begin(
            recordId = "42",
            targetLaneKey = "2",
            targetLaneTitle = "Done",
            beforeFingerprint = "before",
        )

        val pendingDuringLoading = assertNotNull(reconciliation.pendingMove)
        assertEquals("42", pendingDuringLoading.recordId)
        assertEquals("2", pendingDuringLoading.targetLaneKey)

        reconciliation.clear(pendingDuringLoading)
        assertNull(reconciliation.pendingMove)
    }

    @Test
    fun datasetInsightsAggregateDeclaredMeasuresAcrossSemanticDimensions() {
        val resource = ResourceSpec(
            id = "transactions",
            name = "Transactions",
            confidence = Confidence.high,
            fields = listOf(
                field("description", FieldKind.string),
                field("amount", FieldKind.currency, format = "EUR"),
                field("category", FieldKind.enumeration),
                field("accountId", FieldKind.integer),
            ),
        )
        val records = listOf(
            NativeRecord("one", mapOf("description" to "Lunch", "amount" to "12.5", "category" to "food", "accountId" to "99")),
            NativeRecord("two", mapOf("description" to "Train", "amount" to "20", "category" to "travel", "accountId" to "99")),
            NativeRecord("three", mapOf("description" to "Dinner", "amount" to "7.5", "category" to "food", "accountId" to "99")),
        )

        val insights = requireNotNull(nativeDatasetInsights(resource, records))

        assertEquals("amount", insights.measure.id)
        assertEquals("category", insights.dimension?.id)
        assertEquals(40.0, insights.total)
        assertEquals(listOf("Food" to 20.0, "Travel" to 20.0), insights.points.map { it.label to it.value })
        assertEquals("EUR 40", formatNativeMetric(insights.measure, insights.total))
    }

    @Test
    fun `response observed financial scalars participate in renderer local insights`() {
        val sparseContract = ResourceSpec(
            id = "expenses",
            name = "Expenses",
            confidence = Confidence.high,
            fields = emptyList(),
        )
        val observedAmount = field("amount", FieldKind.decimal).copy(readOnly = true)
        val observedCategory = field("category", FieldKind.string).copy(readOnly = true)
        val records = listOf(
            NativeRecord(
                "one",
                emptyMap(),
                displayValues = mapOf("amount" to "12.5", "category" to "food"),
                ephemeralFields = listOf(observedAmount, observedCategory),
            ),
            NativeRecord(
                "two",
                emptyMap(),
                displayValues = mapOf("amount" to "7.5", "category" to "travel"),
                ephemeralFields = listOf(observedAmount, observedCategory),
            ),
        )
        val presented = sparseContract.withEphemeralDisplayFields(records)

        val insights = requireNotNull(nativeDatasetInsights(presented, records))

        assertEquals("amount", insights.measure.id)
        assertEquals(20.0, insights.total)
        assertEquals(listOf("Food" to 12.5, "Travel" to 7.5), insights.points.map { it.label to it.value })
        assertTrue(records.all { record -> record.values.isEmpty() })
    }

    @Test
    fun datasetInsightsRejectIdentifiersAndOpaqueNumericLookingStrings() {
        val resource = ResourceSpec(
            id = "items",
            name = "Items",
            confidence = Confidence.high,
            fields = listOf(field("projectId", FieldKind.integer), field("name", FieldKind.string)),
        )

        assertNull(nativeDatasetInsights(resource, listOf(NativeRecord("one", mapOf("projectId" to "42", "name" to "A")))))
    }

    @Test
    fun `categorical summaries count declared status values in schema order`() {
        val resource = ResourceSpec(
            id = "cards",
            name = "Cards",
            confidence = Confidence.verified,
            fields = listOf(
                field("title", FieldKind.string),
                field("status", FieldKind.enumeration).copy(
                    enumValues = listOf("planned", "in-progress", "complete"),
                ),
            ),
        )
        val records = listOf(
            NativeRecord("one", mapOf("title" to "First", "status" to "complete")),
            NativeRecord("two", mapOf("title" to "Second", "status" to "planned")),
            NativeRecord("three", mapOf("title" to "Third", "status" to "complete")),
            NativeRecord("four", mapOf("title" to "Fourth", "status" to "in-progress")),
        )

        val summary = requireNotNull(nativeCategoricalSummary(resource, records))

        assertEquals("status", summary.dimension.id)
        assertEquals(4, summary.recordCount)
        assertEquals(
            listOf("Planned" to 1.0, "In progress" to 1.0, "Complete" to 2.0),
            summary.points.map { point -> point.label to point.value },
        )
    }

    @Test
    fun `categorical summaries reject arbitrary strings and high cardinality fields`() {
        val resource = ResourceSpec(
            id = "notes",
            name = "Notes",
            confidence = Confidence.high,
            fields = listOf(
                field("title", FieldKind.string),
                field("status", FieldKind.string),
            ),
        )
        val records = (1..7).map { index ->
            NativeRecord(index.toString(), mapOf("title" to "Note $index", "status" to "state-$index"))
        }

        assertNull(nativeCategoricalSummary(resource, records))
    }

    @Test
    fun `categorical summaries retain missing values visibly`() {
        val resource = ResourceSpec(
            id = "tasks",
            name = "Tasks",
            confidence = Confidence.high,
            fields = listOf(field("completed", FieldKind.boolean)),
        )
        val records = listOf(
            NativeRecord("one", mapOf("completed" to "true")),
            NativeRecord("two", mapOf("completed" to "false")),
            NativeRecord("three", emptyMap()),
        )

        val summary = requireNotNull(nativeCategoricalSummary(resource, records))

        assertEquals(
            listOf("False" to 1.0, "Not set" to 1.0, "True" to 1.0),
            summary.points.map { point -> point.label to point.value },
        )
    }

    @Test
    fun `dataset insights reject unrecognized numeric metadata and timestamps`() {
        val resource = ResourceSpec(
            id = "houses",
            name = "Houses",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.integer),
                field("houseId", FieldKind.integer),
                field("createdAt", FieldKind.integer),
                field("updatedAt", FieldKind.integer),
                field("revision", FieldKind.integer),
            ),
        )
        val record = NativeRecord(
            "one",
            mapOf(
                "id" to "1",
                "houseId" to "1",
                "createdAt" to "1785263751",
                "updatedAt" to "1785263751",
                "revision" to "4",
            ),
        )

        assertNull(nativeDatasetInsights(resource, listOf(record)))
    }

    @Test
    fun `dataset insights retain explicitly supported integer measures`() {
        val resource = ResourceSpec(
            id = "stock",
            name = "Stock",
            confidence = Confidence.high,
            fields = listOf(
                field("quantity", FieldKind.integer),
                field("count", FieldKind.integer),
            ),
        )
        val records = listOf(
            NativeRecord("one", mapOf("quantity" to "2", "count" to "1")),
            NativeRecord("two", mapOf("quantity" to "3", "count" to "1")),
        )

        val insights = requireNotNull(nativeDatasetInsights(resource, records))

        assertEquals("quantity", insights.measure.id)
        assertEquals(5.0, insights.total)
    }

    @Test
    fun `budget category amounts become chart measures without promoting technical counters`() {
        val resource = ResourceSpec(
            id = "categories",
            name = "Categories",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.integer),
                field("budgetAmount", FieldKind.currency, format = "EUR"),
                field("budgetPeriod", FieldKind.enumeration),
                field("sortOrder", FieldKind.integer),
                field("updatedAt", FieldKind.dateTime),
            ),
        )
        val records = listOf(
            NativeRecord("one", mapOf("budgetAmount" to "80", "budgetPeriod" to "monthly", "sortOrder" to "1")),
            NativeRecord("two", mapOf("budgetAmount" to "35", "budgetPeriod" to "weekly", "sortOrder" to "2")),
            NativeRecord("three", mapOf("budgetAmount" to "20", "budgetPeriod" to "monthly", "sortOrder" to "3")),
        )

        val insights = requireNotNull(nativeDatasetInsights(resource, records))

        assertEquals("budgetAmount", insights.measure.id)
        assertEquals("budgetPeriod", insights.dimension?.id)
        assertEquals(135.0, insights.total)
        assertEquals(listOf("Monthly" to 100.0, "Weekly" to 35.0), insights.points.map { it.label to it.value })
    }

    @Test
    fun `project spending is a measure while timestamps and access levels stay inert`() {
        val resource = ResourceSpec(
            id = "projects",
            name = "Projects",
            confidence = Confidence.high,
            fields = listOf(
                field("total_spent", FieldKind.currency, format = "EUR"),
                field("lastchanged", FieldKind.integer),
                field("myaccesslevel", FieldKind.integer),
            ),
        )
        val insights = requireNotNull(
            nativeDatasetInsights(
                resource,
                listOf(NativeRecord("one", mapOf("total_spent" to "12.5", "lastchanged" to "999", "myaccesslevel" to "4"))),
            ),
        )

        assertEquals("total_spent", insights.measure.id)
        assertEquals(12.5, insights.total)
        assertNull(insights.dimension)
    }

    @Test
    fun relatedResourceRecordsHydrateDeckLikeLaneIdsWithoutChangingRequestValues() {
        val stacks = ResourceSpec(
            id = "stacks",
            name = "Stacks",
            confidence = Confidence.high,
            fields = listOf(field("id", FieldKind.integer), field("title", FieldKind.string)),
        )
        val cards = ResourceSpec(
            id = "cards",
            name = "Cards",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.integer),
                field("title", FieldKind.string),
                field("stackId", FieldKind.integer),
                field("order", FieldKind.integer),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("workflow", "Workflow", "1"),
            confidence = Confidence.high,
            resources = listOf(stacks, cards),
            relationships = listOf(
                ResourceRelationshipSpec("stacks", "cards", "id", "stackId", Confidence.high),
            ),
        )
        val records = listOf(
            NativeRecord("card-1", mapOf("id" to "1", "title" to "Review", "stackId" to "7", "order" to "2")),
            NativeRecord("card-2", mapOf("id" to "2", "title" to "Draft", "stackId" to "3", "order" to "1")),
        )
        val context = NativeDatasetContext(
            relatedRecords = mapOf(
                "stacks" to listOf(
                    NativeRecord("3", mapOf("id" to "3", "title" to "To do")),
                    NativeRecord("7", mapOf("id" to "7", "title" to "In review")),
                ),
            ),
        )

        val hydrated = hydrateNativeDataset(schema, cards, records, context)
        val lanes = nativeBoardLanes(hydrated.resource, hydrated.records)

        assertEquals("7", hydrated.records.first().values["stackId"])
        assertEquals("In review", hydrated.records.first().displayValues["stackId"])
        assertEquals(listOf("In review", "To do"), lanes.map { it.title })
    }

    @Test
    fun schemaRelationshipOffersHumanReadableParentChoicesForWritableFields() {
        val collections = ResourceSpec(
            id = "collections",
            name = "Collections",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("id", "ID", FieldKind.string, required = true, readOnly = true),
                FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val entries = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("collectionId", "Collection", FieldKind.string, required = true, readOnly = false),
                FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("synthetic", "Synthetic", "test"),
            confidence = Confidence.verified,
            resources = listOf(collections, entries),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = collections.id,
                    childResourceId = entries.id,
                    parentFieldId = "id",
                    childFieldId = "collectionId",
                    confidence = Confidence.verified,
                ),
            ),
        )
        val options = nativeRelationOptions(
            field = entries.fields.first(),
            formResource = entries,
            schema = schema,
            context = NativeDatasetContext(
                relatedRecords = mapOf(
                    collections.id to listOf(
                        NativeRecord("collection-9", mapOf("id" to "collection-9", "title" to "Later")),
                        NativeRecord("collection-4", mapOf("id" to "collection-4", "title" to "Earlier")),
                        NativeRecord(
                            id = "collection-unsafe",
                            values = mapOf("id" to "collection-unsafe", "title" to "Unsafe"),
                            actionBindingProvenanceValid = false,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                NativeRelationOption("collection-4", "Earlier", "Collections"),
                NativeRelationOption("collection-9", "Later", "Collections"),
            ),
            options,
        )
    }

    @Test
    fun contextualTypedChoicesRenderHiddenUserIdsWithoutADeclaredTopLevelResource() {
        val chores = ResourceSpec(
            id = "chores",
            name = "Chores",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec(
                    id = "assignee",
                    label = "Assignee",
                    kind = FieldKind.userReference,
                    required = false,
                    readOnly = false,
                ),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("synthetic", "Synthetic", "test"),
            confidence = Confidence.verified,
            resources = listOf(chores),
        )
        val context = NativeDatasetContext(
            fieldChoices = mapOf(
                "assignee" to listOf(
                    NativeFieldChoice("sam", "Sam", "Team member"),
                    NativeFieldChoice("alex", "Alex", "Team member"),
                ),
            ),
        )

        assertTrue(nativeRelationFieldRequiresChoice(chores.fields.single(), chores, schema, context))
        assertTrue(nativeRelationChoicesLoaded(chores.fields.single(), chores, schema, context))
        assertTrue(nativeRelationChoiceSourceHasRecords(chores.fields.single(), chores, schema, context))
        assertNull(nativeRelationChoiceUnavailableReason(chores.fields.single(), chores, schema, context))
        assertEquals(
            listOf(
                NativeRelationOption("alex", "Alex", "Team member"),
                NativeRelationOption("sam", "Sam", "Team member"),
            ),
            nativeRelationOptions(chores.fields.single(), chores, schema, context),
        )
        assertEquals(
            NativeRelationChoiceUnavailableReason.duplicateValue,
            nativeRelationChoiceUnavailableReason(
                chores.fields.single(),
                chores,
                schema,
                context.copy(
                    fieldChoices = mapOf(
                        "assignee" to listOf(
                            NativeFieldChoice("alex", "Alex"),
                            NativeFieldChoice("alex", "Alex duplicate"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun relationshipChoiceFallsBackToRecordIdentityWithoutADeclaredLabelField() {
        val collections = ResourceSpec(
            id = "collections",
            name = "Collections",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("id", "ID", FieldKind.string, required = true, readOnly = true),
                FieldSpec("description", "Description", FieldKind.string, required = false, readOnly = false),
            ),
        )
        val entries = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("collectionId", "Collection", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("synthetic", "Synthetic", "test"),
            confidence = Confidence.verified,
            resources = listOf(collections, entries),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = collections.id,
                    childResourceId = entries.id,
                    parentFieldId = "id",
                    childFieldId = "collectionId",
                    confidence = Confidence.verified,
                ),
            ),
        )

        assertEquals(
            listOf(NativeRelationOption("collection-4", "collection-4", "Collections")),
            nativeRelationOptions(
                field = entries.fields.single(),
                formResource = entries,
                schema = schema,
                context = NativeDatasetContext(
                    relatedRecords = mapOf(
                        collections.id to listOf(
                            NativeRecord(
                                "collection-4",
                                mapOf("id" to "collection-4", "description" to "Must not become the label"),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun destinationFieldReusesDeclaredRelationshipAndExactSharedScope() {
        val containers = ResourceSpec(
            id = "containers",
            name = "Containers",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("id", "ID", FieldKind.string, required = true, readOnly = true),
                FieldSpec("accountId", "Account", FieldKind.string, required = true, readOnly = true),
                FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val documents = ResourceSpec(
            id = "documents",
            name = "Documents",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("containerId", "Container", FieldKind.string, required = true, readOnly = true),
                FieldSpec(
                    "targetContainerId",
                    "Target container",
                    FieldKind.string,
                    required = true,
                    readOnly = false,
                ),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("synthetic", "Synthetic", "test"),
            confidence = Confidence.verified,
            resources = listOf(containers, documents),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = containers.id,
                    childResourceId = documents.id,
                    parentFieldId = "id",
                    childFieldId = "containerId",
                    confidence = Confidence.verified,
                ),
            ),
        )
        val options = nativeRelationOptions(
            field = documents.fields.single { field -> field.id == "targetContainerId" },
            formResource = documents,
            schema = schema,
            context = NativeDatasetContext(
                parentRecord = NativeRecord(
                    id = "document-2",
                    values = mapOf("id" to "document-2", "accountId" to "account-a"),
                ),
                relatedRecords = mapOf(
                    containers.id to listOf(
                        NativeRecord(
                            "container-1",
                            mapOf("id" to "container-1", "accountId" to "account-a", "name" to "Primary"),
                        ),
                        NativeRecord(
                            "container-2",
                            mapOf("id" to "container-2", "accountId" to "account-b", "name" to "Other scope"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(NativeRelationOption("container-1", "Primary", "Containers")), options)
    }

    @Test
    fun ambiguousRelationshipEvidenceOffersNoMutationChoice() {
        val parents = listOf(
            ResourceSpec("collections", "Collections", Confidence.verified),
            ResourceSpec("archives", "Archives", Confidence.verified),
        )
        val entries = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("parentId", "Parent", FieldKind.string, required = true, readOnly = false),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("synthetic", "Synthetic", "test"),
            confidence = Confidence.verified,
            resources = parents + entries,
            relationships = parents.map { parent ->
                ResourceRelationshipSpec(
                    parentResourceId = parent.id,
                    childResourceId = entries.id,
                    parentFieldId = "id",
                    childFieldId = "parentId",
                    confidence = Confidence.verified,
                )
            },
        )

        assertTrue(
            nativeRelationOptions(
                field = entries.fields.single(),
                formResource = entries,
                schema = schema,
                context = NativeDatasetContext(
                    relatedRecords = parents.associate { parent ->
                        parent.id to listOf(NativeRecord("${parent.id}-1", mapOf("id" to "${parent.id}-1")))
                    },
                ),
            ).isEmpty(),
        )
        assertTrue(
            nativeRelationFieldRequiresChoice(
                field = entries.fields.single(),
                formResource = entries,
                schema = schema,
            ),
        )
    }

    @Test
    fun `opaque writable identifiers require choices instead of raw manual input`() {
        val entries = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec(
                    "categoryId",
                    "Category",
                    FieldKind.integer,
                    required = false,
                    readOnly = false,
                ),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("synthetic", "Synthetic", "test"),
            confidence = Confidence.verified,
            resources = listOf(entries),
        )

        assertTrue(nativeRelationFieldRequiresChoice(entries.fields.single(), entries, schema))
        assertTrue(nativeRelationOptions(entries.fields.single(), entries, schema, NativeDatasetContext()).isEmpty())
    }

    @Test
    fun parentCurrencyContextFormatsChildFinancialMeasuresConsistently() {
        val projects = ResourceSpec(
            id = "projects",
            name = "Projects",
            confidence = Confidence.high,
            fields = listOf(field("id", FieldKind.string), field("currencyname", FieldKind.string)),
        )
        val bills = ResourceSpec(
            id = "bills",
            name = "Bills",
            confidence = Confidence.high,
            fields = listOf(field("what", FieldKind.string), field("amount", FieldKind.decimal)),
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("shared-expenses", "Shared expenses", "1"),
            confidence = Confidence.high,
            resources = listOf(projects, bills),
        )
        val context = NativeDatasetContext(
            parentResourceId = "projects",
            parentRecord = NativeRecord("summer", mapOf("id" to "summer", "currencyname" to "EUR")),
        )

        val hydrated = hydrateNativeDataset(
            schema,
            bills,
            listOf(NativeRecord("1", mapOf("what" to "Lunch", "amount" to "12.5"))),
            context,
        )
        val amount = hydrated.resource.fields.single { it.id == "amount" }
        val insights = requireNotNull(nativeDatasetInsights(hydrated.resource, hydrated.records))

        assertEquals(FieldKind.currency, amount.kind)
        assertEquals("EUR", amount.format)
        assertEquals("EUR 12.5", formatNativeField(amount, "12.5").displayValue)
        assertEquals("EUR 12.5", formatNativeMetric(insights.measure, insights.total))
    }

    @Test
    fun tablePresentationPrefersAReadableIdentityAndOnlyPopulatedTypedColumns() {
        val resource = ResourceSpec(
            id = "rows",
            name = "Rows",
            confidence = Confidence.high,
            fields = listOf(
                field("count", FieldKind.integer),
                field("title", FieldKind.string),
                field("payload", FieldKind.objectValue),
                field("status", FieldKind.enumeration),
                field("unused", FieldKind.string),
            ),
        )
        val records = listOf(
            NativeRecord("one", mapOf("title" to "Milk", "count" to "2", "status" to "needed")),
            NativeRecord("two", mapOf("title" to "Bread", "count" to "1", "status" to "bought")),
        )

        assertEquals(
            listOf("title", "count", "status"),
            nativeTableFields(resource, records).map { it.id },
        )
        assertTrue(nativeTableFields(resource, records, maximumColumns = 0).isEmpty())
    }

    @Test
    fun `ephemeral response fields enrich only a renderer-local resource copy`() {
        val declared = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.high,
            fields = emptyList(),
        )
        val observed = field("displayName", FieldKind.string).copy(readOnly = true)
        val records = listOf(
            NativeRecord("one", mapOf("displayName" to "One"), ephemeralFields = listOf(observed)),
        )

        val presented = declared.withEphemeralDisplayFields(records)

        assertTrue(declared.fields.isEmpty())
        assertEquals(listOf("displayName"), presented.fields.map(FieldSpec::id))
        assertTrue(presented.fields.single().readOnly)
    }

    @Test
    fun tabularCellMapsBecomeTypedNativeColumnsWithoutAppSpecificKnowledge() {
        val resource = ResourceSpec(
            id = "rows",
            name = "Rows",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.integer),
                field("dataByAlias", FieldKind.objectValue),
                field("lastEditAt", FieldKind.dateTime),
            ),
        )
        val records = listOf(
            NativeRecord(
                "one",
                mapOf(
                    "id" to "one",
                    "dataByAlias" to """{"item":{"label":"Item","value":"Milk"},"quantity":{"label":"Quantity","value":2}}""",
                ),
            ),
            NativeRecord(
                "two",
                mapOf(
                    "id" to "two",
                    "dataByAlias" to """{"item":{"label":"Item","value":"Bread"},"quantity":{"label":"Quantity","value":1}}""",
                ),
            ),
        )

        val projection = nativeTableProjection(resource, records)

        assertEquals(listOf("Item", "Quantity", "Id", "LastEditAt"), projection.resource.fields.map { it.label })
        assertEquals(FieldKind.integer, projection.resource.fields[1].kind)
        assertEquals("Milk", projection.records.first().values["dataByAlias.item"])
        assertEquals("2", projection.records.first().values["dataByAlias.quantity"])
        assertEquals(
            listOf("dataByAlias.item", "dataByAlias.quantity"),
            nativeTableFields(projection.resource, projection.records).map { it.id },
        )
    }

    @Test
    fun tabularCellArraysJoinToDeclaredColumnsByStableCellIdentity() {
        val rows = ResourceSpec(
            id = "records",
            name = "Records",
            confidence = Confidence.high,
            fields = listOf(field("id", FieldKind.integer), field("data", FieldKind.objectValue)),
        )
        val columns = ResourceSpec(
            id = "properties",
            name = "Properties",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.integer),
                field("title", FieldKind.string),
                field("type", FieldKind.string),
                field("orderWeight", FieldKind.integer),
            ),
        )
        val composite = CompositeDataGridSpec(
            parentResourceId = "collections",
            columnResourceId = columns.id,
            rowResourceId = rows.id,
            columnSourceActionId = "list-properties",
            rowSourceActionId = "list-records",
            columnIdentityFieldId = "id",
            columnAliasFieldId = null,
            columnTitleFieldId = "title",
            columnTypeFieldId = "type",
            columnOrderFieldId = "orderWeight",
            rowCellMapFieldId = "data",
        )
        val row = NativeRecord(
            "7",
            mapOf(
                "id" to "7",
                "data" to """[{"columnId":12,"value":"Milk"},{"columnId":13,"value":2}]""",
            ),
        )
        val columnRecords = listOf(
            NativeRecord("12", mapOf("id" to "12", "title" to "Item", "type" to "text", "orderWeight" to "10")),
            NativeRecord("13", mapOf("id" to "13", "title" to "Quantity", "type" to "number", "orderWeight" to "20")),
        )

        val projection = nativeTableProjection(rows, listOf(row), columns, columnRecords, composite)

        assertEquals(listOf("Item", "Quantity"), projection.resource.fields.take(2).map(FieldSpec::label))
        assertEquals("Milk", projection.records.single().values["data.12"])
        assertEquals("2", projection.records.single().values["data.13"])
        assertEquals("12", projection.cellsByRecord.getValue("7").getValue("data.12").contextValues["columnId"])
    }

    @Test
    fun projectedCellsOnlyBecomeEditableForOneSafelyBindableDeclaredUpdate() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.high,
            fields = listOf(field("id", FieldKind.integer), field("dataByAlias", FieldKind.objectValue)),
        )
        val record = NativeRecord(
            "row-7",
            mapOf(
                "id" to "7",
                "etag" to "revision-4",
                "dataByAlias" to
                    """{"cost":{"label":"Cost","columnId":42,"value":12.5}}""",
            ),
        )
        val projection = nativeTableProjection(resource, listOf(record))
        val update = ActionSpec(
            id = "update-entry-cell",
            label = "Update entry",
            resourceId = "entries",
            binding = ApiBinding(
                method = HttpMethod.PATCH,
                path = "/api/entries/{rowId}",
                operationId = "update-entry-cell",
                pathParameterNames = listOf("rowId"),
                requiredPathParameterNames = listOf("rowId"),
                bodyFieldNames = listOf("columnId", "value", "etag"),
                requiredBodyFieldNames = listOf("columnId", "value", "etag"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = true,
            confidence = Confidence.high,
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("records", "Records", "1"),
            confidence = Confidence.high,
            resources = listOf(resource),
            actions = listOf(update),
        )
        val projectedField = projection.resource.fields.single { it.id == "dataByAlias.cost" }

        val plan = requireNotNull(nativeCellEditPlan(schema, resource, projection, record, projectedField))
        val request = plan.request("15.75")

        assertEquals("row-7", plan.recordId)
        assertEquals("42", request.values["columnId"])
        assertEquals("revision-4", request.values["etag"])
        assertEquals("15.75", request.values["value"])
        assertEquals("row-7", request.values["id"])
        assertEquals(record.values["dataByAlias"], request.values["dataByAlias"])
        assertTrue(request.confirmed)
    }

    @Test
    fun projectedCellUsesGenericDataBodyOnlyForAColumnScopedWrite() {
        val resource = ResourceSpec(
            id = "rows",
            name = "Rows",
            confidence = Confidence.high,
            fields = listOf(field("id", FieldKind.integer), field("dataByAlias", FieldKind.objectValue)),
        )
        val record = NativeRecord(
            "7",
            mapOf(
                "id" to "7",
                "dataByAlias" to """{"cost":{"columnId":42,"value":12.5}}""",
            ),
        )
        val projection = nativeTableProjection(resource, listOf(record))
        val columnScoped = ActionSpec(
            id = "row-update-cell",
            label = "Update row cell",
            resourceId = "row",
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/api/row/{id}/column/{columnId}",
                operationId = "row-update-cell",
                pathParameterNames = listOf("id", "columnId"),
                requiredPathParameterNames = listOf("id", "columnId"),
                bodyFieldNames = listOf("data"),
                requiredBodyFieldNames = listOf("data"),
                bodyContentType = "application/x-www-form-urlencoded",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = true,
            confidence = Confidence.high,
        )
        val schema = baseSchema(view(NativeComponent.dataTable), columnScoped).copy(resources = listOf(resource))
        val field = projection.resource.fields.single { it.id == "dataByAlias.cost" }

        val request = requireNotNull(nativeCellEditPlan(schema, resource, projection, record, field))
            .request("15.75")

        assertEquals("42", request.values["columnId"])
        assertEquals("15.75", request.values["data"])

        val wholeRow = columnScoped.copy(
            id = "row-update",
            binding = columnScoped.binding.copy(
                path = "/api/row/{id}",
                operationId = "row-update",
                pathParameterNames = listOf("id"),
                requiredPathParameterNames = listOf("id"),
            ),
        )
        assertNull(nativeCellEditPlan(schema.copy(actions = listOf(wholeRow)), resource, projection, record, field))
    }

    @Test
    fun rowScopedObjectWriteReconstructsOneCellAndPreservesTheCompleteObservedRow() {
        val resource = ResourceSpec(
            id = "rows",
            name = "Rows",
            confidence = Confidence.high,
            fields = listOf(field("id", FieldKind.integer), field("data", FieldKind.objectValue)),
        )
        val originalData = """
            {
              "42":{"columnId":42,"value":12.5,"format":"money","futureMetadata":{"color":"purple"}},
              "99":{"columnId":99,"value":"Tea","unknown":true},
              "100":{"columnId":100,"value":{"nested":true}},
              "101":{"columnId":101,"value":[1,2]},
              "102":{"columnId":102,"value":null}
            }
        """.trimIndent()
        val record = NativeRecord("7", mapOf("id" to "7", "data" to originalData))
        val projection = nativeTableProjection(resource, listOf(record))
        val bodySchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("data", buildJsonObject {
                    put("oneOf", buildJsonArray {
                        add(buildJsonObject { put("type", "string") })
                        add(buildJsonObject { put("type", "object") })
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("data")) })
        }
        val update = ActionSpec(
            id = "rows.update",
            label = "Update row",
            resourceId = "row",
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/api/rows/{rowId}",
                operationId = "rows.update",
                pathParameterNames = listOf("rowId"),
                requiredPathParameterNames = listOf("rowId"),
                queryParameterNames = listOf("viewId"),
                bodyFieldNames = listOf("data"),
                requiredBodyFieldNames = listOf("data"),
                bodyContentType = "application/json",
                bodySchema = bodySchema,
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = true,
            confidence = Confidence.verified,
        )
        val schema = baseSchema(view(NativeComponent.dataTable), update).copy(resources = listOf(resource))
        val price = projection.resource.fields.single { it.id == "data.42" }
        val nested = projection.resource.fields.single { it.id == "data.100" }
        val list = projection.resource.fields.single { it.id == "data.101" }
        val untypedNull = projection.resource.fields.single { it.id == "data.102" }

        val request = requireNotNull(nativeCellEditPlan(schema, resource, projection, record, price))
            .request("15.75")
        val reconstructed = Json.parseToJsonElement(request.values.getValue("data")) as JsonObject
        val original = Json.parseToJsonElement(originalData) as JsonObject
        val changed = reconstructed.getValue("42") as JsonObject

        assertEquals(JsonPrimitive(15.75), changed["value"])
        assertEquals((original.getValue("42") as JsonObject)["format"], changed["format"])
        assertEquals((original.getValue("42") as JsonObject)["futureMetadata"], changed["futureMetadata"])
        assertEquals(original["99"], reconstructed["99"])
        assertEquals("7", request.values["id"])
        assertTrue(request.confirmed)
        assertEquals(originalData, record.values["data"], "Planning and editing must not mutate the observed record.")
        assertNull(nativeCellEditPlan(schema, resource, projection, record, nested))
        assertNull(nativeCellEditPlan(schema, resource, projection, record, list))
        assertNull(nativeCellEditPlan(schema, resource, projection, record, untypedNull))

        val typedNullProjection = projection.copy(
            cellsByRecord = projection.cellsByRecord + (
                record.id to projection.cellsByRecord.getValue(record.id).mapValues { (fieldId, cell) ->
                    if (fieldId == untypedNull.id) cell.copy(declaredKind = FieldKind.decimal) else cell
                }
            ),
        )
        val typedNullField = untypedNull.copy(kind = FieldKind.decimal)
        assertEquals(
            JsonPrimitive(4.5),
            ((Json.parseToJsonElement(
                requireNotNull(nativeCellEditPlan(schema, resource, typedNullProjection, record, typedNullField))
                    .request("4.5")
                    .values
                    .getValue("data"),
            ) as JsonObject).getValue("102") as JsonObject).getValue("value"),
        )

        val scalarOnly = update.copy(
            id = "rows.update.scalar",
            binding = update.binding.copy(
                operationId = "rows.update.scalar",
                bodySchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("data", buildJsonObject { put("type", "string") })
                    })
                },
            ),
        )
        assertNull(nativeCellEditPlan(schema.copy(actions = listOf(scalarOnly)), resource, projection, record, price))

        val arrayData =
            """[{"columnId":42,"value":12.5,"format":"money"},{"columnId":99,"value":"Tea","unknown":true}]"""
        val arrayRecord = NativeRecord("8", mapOf("id" to "8", "data" to arrayData))
        val arrayProjection = nativeTableProjection(resource, listOf(arrayRecord))
        val arrayPrice = arrayProjection.resource.fields.single { it.id == "data.42" }
        val arrayRequest = requireNotNull(
            nativeCellEditPlan(schema, resource, arrayProjection, arrayRecord, arrayPrice),
        ).request("18.25")
        val normalized = Json.parseToJsonElement(arrayRequest.values.getValue("data")) as JsonObject

        assertEquals(JsonPrimitive(18.25), (normalized.getValue("42") as JsonObject)["value"])
        assertEquals(JsonPrimitive("Tea"), (normalized.getValue("99") as JsonObject)["value"])
        assertEquals(JsonPrimitive(true), (normalized.getValue("99") as JsonObject)["unknown"])
    }

    @Test
    fun projectedCellEditingStaysReadOnlyWhenRequiredContextIsMissingOrActionsAreAmbiguous() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.high,
            fields = listOf(field("id", FieldKind.integer), field("cells", FieldKind.objectValue)),
        )
        val record = NativeRecord("1", mapOf("id" to "1", "cells" to """{"cost":{"value":12}}"""))
        val projection = nativeTableProjection(resource, listOf(record))
        val projectedField = projection.resource.fields.single { it.id == "cells.cost" }
        val incomplete = action(
            id = "update-cell",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            method = HttpMethod.PATCH,
        ).copy(
            resourceId = "entries",
            binding = ApiBinding(
                HttpMethod.PATCH,
                "/api/entries/{rowId}",
                "update-cell",
                pathParameterNames = listOf("rowId"),
                requiredPathParameterNames = listOf("rowId"),
                bodyFieldNames = listOf("columnId", "value"),
                requiredBodyFieldNames = listOf("columnId", "value"),
                bodyContentType = "application/json",
            ),
        )
        val schema = baseSchema(view(NativeComponent.dataTable), incomplete).copy(resources = listOf(resource))

        assertNull(nativeCellEditPlan(schema, resource, projection, record, projectedField))
        val direct = incomplete.copy(
            id = "direct-cost",
            binding = incomplete.binding.copy(
                operationId = "direct-cost",
                bodyFieldNames = listOf("cost"),
                requiredBodyFieldNames = listOf("cost"),
            ),
        )
        val ambiguous = schema.copy(actions = listOf(direct, direct.copy(id = "direct-cost-2")))
        assertNull(nativeCellEditPlan(ambiguous, resource, projection, record, projectedField))
    }

    @Test
    fun onlyHandsUnambiguousHttpLinksToTheHost() {
        assertEquals("https://cloud.example.test/item/1", safeNativeLink("https://cloud.example.test/item/1"))
        assertNull(safeNativeLink("javascript:alert(1)"))
        assertNull(safeNativeLink("https://user:secret@cloud.example.test/item"))
        assertNull(safeNativeLink("https://cloud.example.test/a path"))
        assertNull(safeNativeLink("https:\\cloud.example.test\\item"))
    }

    @Test
    fun acceptsOnlyBoundedSameOriginAssetPaths() {
        assertEquals(
            "/apps/music/api/albums/42/cover",
            safeNativeAssetPath("/apps/music/api/albums/42/cover"),
        )
        assertEquals(
            "/apps/cookbook/recipe/image?size=thumb",
            safeNativeAssetPath("/apps/cookbook/recipe/image?size=thumb"),
        )
        assertNull(safeNativeAssetPath("https://cloud.example.test/cover.jpg"))
        assertNull(safeNativeAssetPath("//other.example/cover.jpg"))
        assertNull(safeNativeAssetPath("/apps/music/../files/secret"))
        assertNull(safeNativeAssetPath("/apps/music/cover#fragment"))
    }

    @Test
    fun formatsTypedFieldsWithoutChangingUnderlyingValues() {
        assertEquals("Yes", formatNativeField(field("paid", FieldKind.boolean), "true").displayValue)
        assertEquals("EUR 42.30", formatNativeField(field("amount", FieldKind.currency, format = "EUR"), "42.30").displayValue)
        assertEquals("2026-07-22 14:30", formatNativeField(field("when", FieldKind.dateTime), "2026-07-22T14:30Z").displayValue)
        assertEquals(
            "2025-08-29 05:30",
            formatNativeField(field("published", FieldKind.dateTime), "2025-08-29 05:30:00+00:00").displayValue,
        )
        assertEquals("@alex", formatNativeField(field("owner", FieldKind.userReference), "alex").displayValue)
        assertEquals(
            "2 members",
            formatNativeField(field("members", FieldKind.objectValue), "[{\"id\":1},{\"id\":2}]").displayValue,
        )
        assertEquals(
            "https://cloud.example.test/file/1",
            formatNativeField(field("site", FieldKind.string, format = "url"), "https://cloud.example.test/file/1").safeLink,
        )
    }

    @Test
    fun buildsCompactCollectionAndGroupedDetailPresentationFromDeclaredFields() {
        val resource = ResourceSpec(
            id = "projects",
            name = "Projects",
            confidence = Confidence.high,
            fields = listOf(
                field("title", FieldKind.string),
                field("enabled", FieldKind.boolean),
                field("website", FieldKind.string, format = "url"),
                field("note", FieldKind.longText),
            ),
        )
        val record = NativeRecord(
            id = "project-1",
            values = mapOf(
                "title" to "Summer project",
                "enabled" to "true",
                "website" to "https://cloud.example.test/project/1",
                "note" to "",
                "undeclared" to "Never render this",
            ),
        )

        assertEquals(
            NativeRecordPresentation("Summer project", "https://cloud.example.test/project/1"),
            nativeRecordPresentation(resource, record),
        )
        val details = nativeDetailFields(resource, record)
        assertEquals(listOf("title", "enabled", "website"), details.map { it.fieldId })
        assertEquals("Yes", details[1].formatted.displayValue)
        assertEquals("https://cloud.example.test/project/1", details[2].formatted.safeLink)
    }

    @Test
    fun `declared record icons render visually while descriptions remain text`() {
        val resource = ResourceSpec(
            id = "collections",
            name = "Collections",
            confidence = Confidence.verified,
            fields = listOf(
                field("name", FieldKind.string),
                field("icon", FieldKind.enumeration),
                field("color", FieldKind.enumeration),
                field("description", FieldKind.longText),
            ),
        )
        val record = NativeRecord(
            id = "collection-1",
            values = mapOf(
                "name" to "Weekly groceries",
                "icon" to "clipboard-check",
                "color" to "#f97316",
                "description" to "Shared household list",
            ),
        )

        assertEquals(
            NativeRecordPresentation(
                title = "Weekly groceries",
                subtitle = "Shared household list",
                iconKey = "clipboard-check",
                colorArgb = 0xFFF97316.toInt(),
            ),
            nativeRecordPresentation(resource, record),
        )
        assertEquals(
            listOf("name", "description"),
            nativeTableFields(resource, listOf(record)).map(FieldSpec::id),
        )
        assertEquals(
            listOf("name", "description"),
            nativeDetailFields(resource, record).map(NativeDetailFieldPresentation::fieldId),
        )
    }

    @Test
    fun `record icon tokens reject unsafe or ambiguous values and retain safe unknowns`() {
        val resource = ResourceSpec(
            id = "collections",
            name = "Collections",
            confidence = Confidence.verified,
            fields = listOf(
                field("icon", FieldKind.enumeration),
                field("symbol", FieldKind.string),
            ),
        )

        assertEquals(
            NativeRecordPresentation("collection-1", null, null),
            nativeRecordPresentation(
                resource,
                NativeRecord(
                    "collection-1",
                    mapOf("icon" to "https://invalid.example/icon", "symbol" to "clipboard-check"),
                ),
            ),
        )
        assertEquals(
            NativeRecordPresentation("collection-2", null, null),
            nativeRecordPresentation(
                resource,
                NativeRecord(
                    "collection-2",
                    mapOf("icon" to "clipboard-check", "symbol" to "heart"),
                ),
            ),
        )
        assertEquals(
            NativeRecordPresentation("collection-3", null, "heart"),
            nativeRecordPresentation(
                resource,
                NativeRecord(
                    "collection-3",
                    mapOf("icon" to "heart", "symbol" to "heart"),
                ),
            ),
        )
        assertEquals(
            NativeRecordPresentation("collection-4", null, "server-specific"),
            nativeRecordPresentation(
                resource,
                NativeRecord(
                    "collection-4",
                    mapOf("icon" to "server_specific"),
                ),
            ),
        )
    }

    @Test
    fun `technical integer metadata does not replace an empty description`() {
        val resource = ResourceSpec(
            id = "collections",
            name = "Collections",
            confidence = Confidence.verified,
            fields = listOf(
                field("name", FieldKind.string),
                field("description", FieldKind.longText),
                field("createdAt", FieldKind.integer),
                field("sortOrder", FieldKind.integer),
            ),
        )

        assertEquals(
            NativeRecordPresentation("Groceries", null),
            nativeRecordPresentation(
                resource,
                NativeRecord(
                    "collection-1",
                    mapOf(
                        "name" to "Groceries",
                        "description" to "",
                        "createdAt" to "1785424226",
                        "sortOrder" to "1",
                    ),
                ),
            ),
        )
    }

    @Test
    fun collectionPresentationPrefersSemanticContextOverTechnicalFlags() {
        val resource = ResourceSpec(
            id = "projects",
            name = "Projects",
            confidence = Confidence.high,
            fields = listOf(
                field("id", FieldKind.string),
                field("active", FieldKind.boolean),
                field("members", FieldKind.objectValue),
                field("name", FieldKind.string),
            ),
        )
        val record = NativeRecord(
            id = "project-1",
            values = mapOf(
                "id" to "project-1",
                "active" to "true",
                "members" to "[{\"id\":1},{\"id\":2}]",
                "name" to "Summer trip",
            ),
        )

        assertEquals(
            NativeRecordPresentation("Summer trip", "2 members"),
            nativeRecordPresentation(resource, record),
        )
    }

    @Test
    fun collectionPresentationDoesNotExposeRecipeImageEndpointsAsSubtitles() {
        val resource = ResourceSpec(
            id = "recipes",
            name = "Recipes",
            confidence = Confidence.high,
            fields = listOf(
                field("name", FieldKind.string),
                field("imageUrl", FieldKind.string, format = "url"),
                field("category", FieldKind.string),
            ),
        )
        val record = NativeRecord(
            id = "recipe-1",
            values = mapOf(
                "name" to "Apple pie",
                "imageUrl" to "https://cloud.example.test/apps/cookbook/api/v1/recipes/recipe-1/image",
                "category" to "Dessert",
            ),
        )

        assertEquals(
            NativeRecordPresentation("Apple pie", "Dessert"),
            nativeRecordPresentation(resource, record),
        )
        assertEquals("cookbook", nativeResourceIconAppId(resource))
    }

    @Test
    fun resourceIconsUseWholeSemanticWordsBeforeFieldShapeFallbacks() {
        fun resource(id: String, name: String = id) = ResourceSpec(id, name, Confidence.high, emptyList())

        assertEquals("mail", nativeResourceIconAppId(resource("messages")))
        assertEquals("music", nativeResourceIconAppId(resource("albumTracks", "Album tracks")))
        assertEquals("tables", nativeResourceIconAppId(resource("table_rows", "Rows")))
        assertEquals("deck", nativeResourceIconAppId(resource("cards")))
        assertEquals("cospend", nativeResourceIconAppId(resource("transactions")))
        assertNull(nativeResourceIconAppId(resource("discardedItems")))
    }

    @Test
    fun observedConfigurationWritesUseClearNativeSettingsLabels() {
        val resource = ResourceSpec("config", "Config", Confidence.high, emptyList())
        val action = formSchema(ActionRisk.mutating).actions.single().copy(
            label = "Config",
            resourceId = resource.id,
            binding = formSchema(ActionRisk.mutating).actions.single().binding.copy(
                allowsObservedBodyFields = true,
            ),
        )
        val view = formSchema(ActionRisk.mutating).views.single().copy(
            title = "Config",
            resourceId = resource.id,
        )

        assertEquals("Settings", nativeFormTitle(view, resource, action))
        assertEquals("Save settings", nativeFormSubmitLabel(resource, action))
    }

    @Test
    fun settingsFormsSelectASafeReadSurfaceForCurrentValuePrefill() {
        val resource = ResourceSpec("settings", "Settings", Confidence.high, emptyList())
        val read = action(
            id = "settings.read",
            intent = ActionIntent.read,
            risk = ActionRisk.readOnly,
            method = HttpMethod.GET,
        ).copy(resourceId = resource.id)
        val write = action(
            id = "settings.save",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            method = HttpMethod.POST,
        ).copy(
            resourceId = resource.id,
            binding = action(
                id = "settings.save",
                intent = ActionIntent.update,
                risk = ActionRisk.mutating,
                method = HttpMethod.POST,
            ).binding.copy(allowsObservedBodyFields = true),
        )
        val readView = ViewSpec(
            "settings.detail",
            "Settings",
            resource.id,
            NativeComponent.detail,
            read.id,
            Confidence.high,
        )
        val form = ViewSpec(
            "settings.form",
            "Edit settings",
            resource.id,
            NativeComponent.form,
            write.id,
            Confidence.high,
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("dynamic-test", "Dynamic Test", "1.0"),
            confidence = Confidence.high,
            resources = listOf(resource),
            views = listOf(form, readView),
            actions = listOf(write, read),
        )

        assertEquals(readView, schema.settingsFormPrefillView(form))
        assertNull(schema.copy(actions = listOf(write, read.copy(risk = ActionRisk.mutating)))
            .settingsFormPrefillView(form))
    }

    @Test
    fun ordinaryCreateFormsNeverPrefillFromTheFirstCollectionRecord() {
        val schema = formSchema(ActionRisk.mutating)
        val form = schema.views.single()
        val listAction = action(
            id = "items.list",
            intent = ActionIntent.list,
            risk = ActionRisk.readOnly,
            method = HttpMethod.GET,
        )
        val withList = schema.copy(
            views = schema.views + view(NativeComponent.collectionList, sourceActionId = listAction.id),
            actions = schema.actions + listAction,
        )

        assertNull(withList.settingsFormPrefillView(form))
    }

    @Test
    fun validationCoversDeclaredTypedInputsAndDropsUndeclaredValues() {
        val schema = formSchema(ActionRisk.mutating)
        val action = schema.actions.single()
        val resource = schema.resources.single()
        val invalid = validateNativeForm(
            resource,
            action,
            mapOf("title" to "", "count" to "four", "date" to "2026-02-30", "enabled" to "maybe"),
        )
        assertEquals(setOf("title", "count", "date", "enabled"), invalid.errors.keys)

        val built = buildNativeSubmitRequest(
            schema,
            schema.views.single(),
            values = validValues() + ("injected" to "must-not-leave-the-renderer"),
            confirmed = false,
        )
        val request = assertIs<NativeActionRequest.Submit>(assertIs<NativeRequestBuildResult.Ready>(built).request)
        assertEquals(validValues(), request.values)
        assertTrue("injected" !in request.values)
        assertEquals("false", initialNativeFormDraft(resource, action).values["enabled"])
    }

    @Test
    fun `generic create submit binds one structurally proven normalized parent id`() {
        val resource = ResourceSpec(
            id = "lists",
            name = "Lists",
            confidence = Confidence.high,
            fields = listOf(field(id = "name", kind = FieldKind.string, required = true)),
        )
        val create = action(
            id = "lists.create",
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            method = HttpMethod.POST,
        ).copy(
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/api/houses/{houseId}/lists",
                operationId = "lists.create",
                pathParameterNames = listOf("houseId"),
                requiredPathParameterNames = listOf("houseId"),
                bodyFieldNames = listOf("name"),
                requiredBodyFieldNames = listOf("name"),
                bodyContentType = "application/json",
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("dynamic-test", "Dynamic Test", "1.0"),
            confidence = Confidence.high,
            resources = listOf(resource),
            views = listOf(
                ViewSpec(
                    id = "lists.create.form",
                    title = "Create list",
                    resourceId = resource.id,
                    component = NativeComponent.form,
                    sourceActionId = create.id,
                    confidence = Confidence.high,
                ),
            ),
            actions = listOf(create),
        )

        val ready = assertIs<NativeRequestBuildResult.Ready>(
            buildNativeSubmitRequest(
                schema = schema,
                view = schema.views.single(),
                values = mapOf("id" to "house-7", "name" to "Shopping"),
                confirmed = false,
            ),
        )
        assertEquals(
            mapOf("houseId" to "house-7", "name" to "Shopping"),
            assertIs<NativeActionRequest.Submit>(ready.request).values,
        )

        val unrelated = schema.copy(
            actions = listOf(
                create.copy(binding = create.binding.copy(path = "/api/accounts/{houseId}/lists")),
            ),
        )
        assertIs<NativeRequestBuildResult.Invalid>(
            buildNativeSubmitRequest(
                schema = unrelated,
                view = unrelated.views.single(),
                values = mapOf("id" to "house-7", "name" to "Shopping"),
                confirmed = false,
            ),
        )
    }

    @Test
    fun `exact integer arrays are editable validated and safely prefilled as bounded JSON`() {
        val bodySchema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "ids":{
                  "type":"array",
                  "items":{"type":"integer"},
                  "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
                }
              },
              "required":["ids"]
            }""",
        )
        val action = action(
            id = "assignments.submit",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            method = HttpMethod.PUT,
        ).copy(
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/assignments",
                operationId = "assignments.submit",
                bodyFieldNames = listOf("ids"),
                requiredBodyFieldNames = listOf("ids"),
                bodyContentType = "application/json",
                bodySchema = bodySchema,
            ),
            inputSchema = buildJsonObject {
                put("properties", buildJsonObject { put("ids", buildJsonObject {}) })
                put("required", buildJsonArray { add(JsonPrimitive("ids")) })
            },
        )
        val resource = ResourceSpec(
            id = "items",
            name = "Items",
            confidence = Confidence.high,
            fields = listOf(
                field(
                    id = "ids",
                    kind = FieldKind.integer,
                    required = true,
                    format = DYNAMIC_INTEGER_ARRAY_FORMAT,
                ),
            ),
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("dynamic-test", "Dynamic Test", "1.0"),
            confidence = Confidence.high,
            resources = listOf(resource),
            views = listOf(view(NativeComponent.form, sourceActionId = action.id)),
            actions = listOf(action),
        )

        assertEquals(listOf("ids"), editableNativeFields(resource, action).map(FieldSpec::id))
        assertTrue(validateNativeForm(resource, action, mapOf("ids" to "[1,-2,1]")).isValid)
        assertIs<NativeRequestBuildResult.Ready>(
            buildNativeSubmitRequest(
                schema = schema,
                view = schema.views.single(),
                values = mapOf("ids" to "[1,-2,1]"),
                confirmed = false,
            ),
        )

        val tooMany = (0..256).joinToString(prefix = "[", postfix = "]")
        listOf(
            "1,2",
            """[1,"2"]""",
            "[1.5]",
            "[null]",
            """[{"id":1}]""",
            "[9223372036854775808]",
            tooMany,
        ).forEach { value ->
            assertEquals(
                setOf("ids"),
                validateNativeForm(resource, action, mapOf("ids" to value)).errors.keys,
                value,
            )
        }

        val record = NativeRecord(
            id = "assignment",
            values = emptyMap(),
            structuredValues = mapOf(
                "ids" to NativeStructuredValue.ListValue(
                    listOf(
                        NativeStructuredValue.Scalar("4", NativeStructuredScalarKind.number),
                        NativeStructuredValue.Scalar("-2", NativeStructuredScalarKind.number),
                    ),
                ),
            ),
        )
        assertEquals("[4,-2]", initialNativeFormDraft(resource, action, record).values["ids"])
        val truncated = record.copy(
            structuredValues = mapOf(
                "ids" to NativeStructuredValue.ListValue(
                    items = listOf(
                        NativeStructuredValue.Scalar("4", NativeStructuredScalarKind.number),
                    ),
                    omittedItems = 1,
                ),
            ),
        )
        assertEquals("", initialNativeFormDraft(resource, action, truncated).values["ids"])
    }

    @Test
    fun `integer array forms enforce supported constraints and reject unsupported constraint schemas`() {
        val constrainedSchema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "ids":{
                  "type":"array",
                  "items":{
                    "type":"integer",
                    "format":"int64",
                    "minimum":2,
                    "maximum":10,
                    "multipleOf":2
                  },
                  "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT",
                  "minItems":2,
                  "maxItems":3,
                  "uniqueItems":true
                }
              },
              "required":["ids"]
            }""",
        )
        val base = action(
            id = "assignments.submit",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            method = HttpMethod.PUT,
        )
        val action = base.copy(
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/assignments",
                operationId = base.id,
                bodyFieldNames = listOf("ids"),
                requiredBodyFieldNames = listOf("ids"),
                bodyContentType = "application/json",
                bodySchema = constrainedSchema,
            ),
            inputSchema = buildJsonObject {
                put("properties", buildJsonObject { put("ids", buildJsonObject {}) })
                put("required", buildJsonArray { add(JsonPrimitive("ids")) })
            },
        )
        val resource = ResourceSpec(
            id = "items",
            name = "Items",
            confidence = Confidence.high,
            fields = listOf(field("ids", FieldKind.integer, format = DYNAMIC_INTEGER_ARRAY_FORMAT)),
        )

        assertEquals(listOf("ids"), editableNativeFields(resource, action).map(FieldSpec::id))
        assertTrue(validateNativeForm(resource, action, mapOf("ids" to "[2,4]")).isValid)
        listOf(
            "[]",
            "[2]",
            "[2,4,6,8]",
            "[2,2]",
            "[0,2]",
            "[2,12]",
            "[2,3]",
        ).forEach { value ->
            assertEquals(
                setOf("ids"),
                validateNativeForm(resource, action, mapOf("ids" to value)).errors.keys,
                value,
            )
        }

        val unsupported = action.copy(
            binding = action.binding.copy(
                bodySchema = Json.parseToJsonElement(
                    """{
                      "type":"object",
                      "properties":{
                        "ids":{
                          "type":"array",
                          "items":{"type":"integer"},
                          "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT",
                          "contains":{"const":1}
                        }
                      }
                    }""",
                ),
            ),
        )
        assertTrue(editableNativeFields(resource, unsupported).isEmpty())
        assertEquals(
            setOf("ids"),
            uneditableNativeBodyFieldIds(unsupported, editableNativeFields(resource, unsupported), emptyMap()),
        )

        val unsupportedItemFormat = action.copy(
            binding = action.binding.copy(
                bodySchema = Json.parseToJsonElement(
                    """{
                      "type":"object",
                      "properties":{
                        "ids":{
                          "type":"array",
                          "items":{"type":"integer","format":"uint64"},
                          "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
                        }
                      }
                    }""",
                ),
            ),
        )
        assertTrue(editableNativeFields(resource, unsupportedItemFormat).isEmpty())
        assertEquals(
            setOf("ids"),
            uneditableNativeBodyFieldIds(
                unsupportedItemFormat,
                editableNativeFields(resource, unsupportedItemFormat),
                emptyMap(),
            ),
        )
    }

    @Test
    fun `unsupported or mismatched array schemas remain uneditable and block submission`() {
        val base = action(
            id = "assignments.submit",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            method = HttpMethod.PUT,
        )
        val resource = ResourceSpec(
            id = "items",
            name = "Items",
            confidence = Confidence.high,
            fields = listOf(
                field("ids", FieldKind.integer, format = DYNAMIC_INTEGER_ARRAY_FORMAT),
                field("weights", FieldKind.decimal),
            ),
        )
        val action = base.copy(
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/assignments",
                operationId = base.id,
                bodyFieldNames = listOf("ids", "weights"),
                bodyContentType = "application/json",
                bodySchema = Json.parseToJsonElement(
                    """{
                      "type":"object",
                      "properties":{
                        "ids":{
                          "type":"array",
                          "items":{"type":"string"},
                          "format":"$DYNAMIC_INTEGER_ARRAY_FORMAT"
                        },
                        "weights":{"type":"array","items":{"type":"number"}}
                      }
                    }""",
                ),
            ),
            inputSchema = buildJsonObject {
                put("properties", buildJsonObject {
                    put("ids", buildJsonObject {})
                    put("weights", buildJsonObject {})
                })
            },
        )
        val schema = NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("dynamic-test", "Dynamic Test", "1.0"),
            confidence = Confidence.high,
            resources = listOf(resource),
            views = listOf(view(NativeComponent.form, sourceActionId = action.id)),
            actions = listOf(action),
        )

        assertTrue(editableNativeFields(resource, action).isEmpty())
        assertEquals(
            setOf("ids", "weights"),
            uneditableNativeBodyFieldIds(action, editableNativeFields(resource, action), emptyMap()),
        )
        assertIs<NativeRequestBuildResult.Invalid>(
            buildNativeSubmitRequest(
                schema = schema,
                view = schema.views.single(),
                values = mapOf("ids" to "[1,2]", "weights" to "[1.5]"),
                confirmed = false,
            ),
        )
    }

    @Test
    fun `ordering fields are omitted only with exact request schema evidence`() {
        val bodySchema = Json.parseToJsonElement(
            """{
              "type":"object",
              "properties":{
                "position":{"type":"integer"},
                "rank":{"type":"integer","readOnly":true},
                "sortOrder":{"type":"integer","x-nextcloud-native-server-managed":true}
              }
            }""",
        )
        val base = action(
            id = "items.update",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            method = HttpMethod.PUT,
        )
        val action = base.copy(
            binding = ApiBinding(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/items",
                operationId = base.id,
                bodyFieldNames = listOf("position", "rank", "sortOrder"),
                bodyContentType = "application/json",
                bodySchema = bodySchema,
            ),
            inputSchema = buildJsonObject {
                put("properties", buildJsonObject { put("position", buildJsonObject {}) })
            },
        )
        val resource = ResourceSpec(
            id = "items",
            name = "Items",
            confidence = Confidence.high,
            fields = listOf(
                field("position", FieldKind.integer, readOnly = true),
                field("rank", FieldKind.integer, readOnly = true),
                field("sortOrder", FieldKind.integer, readOnly = true),
            ),
        )

        val editable = editableNativeFields(resource, action)
        assertEquals(listOf("position"), editable.map(FieldSpec::id))
        assertTrue(uneditableNativeBodyFieldIds(action, editable, emptyMap()).isEmpty())

        val unsupportedAssumption = action.copy(
            binding = action.binding.copy(
                bodySchema = Json.parseToJsonElement(
                    """{
                      "type":"object",
                      "properties":{
                        "position":{"type":"integer"},
                        "rank":{"type":"integer"},
                        "sortOrder":{"type":"integer"}
                      }
                    }""",
                ),
            ),
        )
        assertEquals(
            setOf("rank", "sortOrder"),
            uneditableNativeBodyFieldIds(
                unsupportedAssumption,
                editableNativeFields(resource, unsupportedAssumption),
                emptyMap(),
            ),
        )

        val requiredServerManaged = action.copy(
            binding = action.binding.copy(requiredBodyFieldNames = listOf("rank")),
        )
        assertEquals(
            setOf("rank"),
            uneditableNativeBodyFieldIds(
                requiredServerManaged,
                editableNativeFields(resource, requiredServerManaged),
                emptyMap(),
            ),
        )
    }

    @Test
    fun formDraftChangeTrackingIgnoresTouchOnlyAndDetectsValueChanges() {
        val initial = NativeFormDraft(values = mapOf("enabled" to "true"))

        assertFalse(initial.copy(touchedFields = setOf("enabled")).hasChangesFrom(initial))
        assertTrue(initial.update("enabled", "false").hasChangesFrom(initial))
    }

    @Test
    fun `observed boolean map becomes a native switch group and is prefilled`() {
        val action = formSchema(ActionRisk.mutating).actions.single().copy(
            binding = formSchema(ActionRisk.mutating).actions.single().binding.copy(
                allowsObservedBodyFields = true,
            ),
        )
        val resource = ResourceSpec(
            id = action.resourceId,
            name = "Config",
            confidence = Confidence.high,
            fields = listOf(
                FieldSpec(
                    id = "visibleInfoBlocks",
                    label = "Visible info blocks",
                    kind = FieldKind.objectValue,
                    required = false,
                    readOnly = true,
                ),
            ),
        )
        val record = NativeRecord(
            id = "config",
            values = emptyMap(),
            structuredValues = mapOf(
                "visibleInfoBlocks" to NativeStructuredValue.ObjectValue(
                    entries = listOf(
                        NativeStructuredEntry(
                            "preparation-time",
                            "Preparation time",
                            NativeStructuredValue.Scalar("true", NativeStructuredScalarKind.boolean),
                        ),
                        NativeStructuredEntry(
                            "tools",
                            "Tools",
                            NativeStructuredValue.Scalar("false", NativeStructuredScalarKind.boolean),
                        ),
                    ),
                ),
            ),
        )

        val typedResource = resource.withObservedSettingsFormTypes(action, record)
        val field = typedResource.fields.single()
        val draft = initialNativeFormDraft(typedResource, action, record)

        assertEquals(SETTINGS_BOOLEAN_MAP_FORMAT, field.format)
        assertEquals(mapOf("preparation-time" to true, "tools" to false),
            parseNativeBooleanMap(draft.values.getValue(field.id)))
        assertEquals(
            mapOf("preparation-time" to true, "tools" to true),
            parseNativeBooleanMap(updateNativeBooleanMap(draft.values.getValue(field.id), "tools", true)),
        )
    }

    @Test
    fun `observed settings action retains scalar json types without declaring object fields`() {
        val action = formSchema(ActionRisk.mutating).actions.single().copy(
            inputSchema = null,
            binding = formSchema(ActionRisk.mutating).actions.single().binding.copy(
                allowsObservedBodyFields = true,
            ),
        )
        val resource = ResourceSpec(
            id = action.resourceId,
            name = "Settings",
            confidence = Confidence.high,
            fields = listOf(
                field("folder", FieldKind.string),
                field("update_interval", FieldKind.integer),
                field("print_image", FieldKind.boolean),
                field("visibleInfoBlocks", FieldKind.objectValue),
            ),
        )

        val typed = action.withObservedSettingsInputTypes(resource)
        val properties = (typed.inputSchema as JsonObject)["properties"] as JsonObject

        assertEquals(JsonPrimitive("string"), (properties.getValue("folder") as JsonObject)["type"])
        assertEquals(JsonPrimitive("integer"), (properties.getValue("update_interval") as JsonObject)["type"])
        assertEquals(JsonPrimitive("boolean"), (properties.getValue("print_image") as JsonObject)["type"])
        assertTrue("visibleInfoBlocks" !in properties)
    }

    @Test
    fun `observed settings never render credential-like response fields as editable`() {
        val action = formSchema(ActionRisk.mutating).actions.single().copy(
            inputSchema = null,
            binding = formSchema(ActionRisk.mutating).actions.single().binding.copy(
                allowsObservedBodyFields = true,
            ),
        )
        val resource = ResourceSpec(
            id = action.resourceId,
            name = "Settings",
            confidence = Confidence.high,
            fields = listOf(
                field("folder", FieldKind.string),
                field("api_key", FieldKind.string),
                field("refreshToken", FieldKind.string),
                field("private_key_path", FieldKind.string),
                field("credentialFile", FieldKind.file),
            ),
        )

        assertEquals(
            listOf("folder"),
            editableNativeFields(resource, action).map(FieldSpec::id),
        )
    }

    @Test
    fun `declared credential fields remain available to explicit typed forms`() {
        val schema = formSchema(ActionRisk.mutating)
        val declaredAction = schema.actions.single()
        val resource = schema.resources.single().copy(
            fields = schema.resources.single().fields + field("password", FieldKind.string),
        )
        val properties = (declaredAction.inputSchema as JsonObject)["properties"] as JsonObject
        val action = declaredAction.copy(
            inputSchema = buildJsonObject {
                put("properties", buildJsonObject {
                    properties.forEach { (name, value) -> put(name, value) }
                    put("password", buildJsonObject { put("type", "string") })
                })
            },
        )

        assertTrue("password" in editableNativeFields(resource, action).map(FieldSpec::id))
    }

    @Test
    fun `declared body fields without a safe editor block partial mutation forms`() {
        val schema = formSchema(ActionRisk.mutating)
        val base = schema.actions.single()
        val action = base.copy(
            binding = base.binding.copy(
                bodyFieldNames = listOf("title", "roleIds", "shares"),
            ),
        )
        val editable = editableNativeFields(schema.resources.single(), action)

        assertEquals(
            setOf("roleIds", "shares"),
            uneditableNativeBodyFieldIds(
                action = action,
                editableFields = editable,
                autoBoundValues = emptyMap(),
            ),
        )
        assertEquals(
            setOf("shares"),
            uneditableNativeBodyFieldIds(
                action = action,
                editableFields = editable,
                autoBoundValues = mapOf("roleIds" to "[1,2]"),
            ),
        )
        val unsafeSchema = schema.copy(actions = listOf(action))
        assertIs<NativeRequestBuildResult.Invalid>(
            buildNativeSubmitRequest(
                schema = unsafeSchema,
                view = unsafeSchema.views.single(),
                values = validValues(),
                confirmed = false,
            ),
        )
    }

    @Test
    fun formNeverExecutesAnActionDeclaredReadOnly() {
        val schema = formSchema(ActionRisk.mutating)
        val unsafe = schema.copy(
            actions = listOf(schema.actions.single().copy(risk = ActionRisk.readOnly)),
        )
        assertIs<NativeRequestBuildResult.Invalid>(
            buildNativeSubmitRequest(unsafe, unsafe.views.single(), validValues(), confirmed = false),
        )
    }

    @Test
    fun destructiveActionWaitsForExplicitConfirmationBeforeCallingExecutor() = runBlocking {
        val schema = formSchema(ActionRisk.destructive, requiresConfirmation = false)
        val requests = mutableListOf<NativeActionRequest>()
        val coordinator = NativeActionCoordinator(schema, schema.views.single()) { request ->
            requests += request
            NativeActionExecutionResult.Success("Deleted")
        }

        coordinator.submit(validValues())
        val pending = assertIs<NativeActionExecutionState.AwaitingConfirmation>(coordinator.state)
        assertEquals(false, pending.request.confirmed)
        assertTrue(requests.isEmpty())

        coordinator.confirm()
        assertEquals(1, requests.size)
        assertEquals(true, assertIs<NativeActionRequest.Submit>(requests.single()).confirmed)
        assertEquals("Deleted", assertIs<NativeActionExecutionState.Succeeded>(coordinator.state).message)
    }

    @Test
    fun validationFailureAndExecutorFailureHaveExplicitStates() = runBlocking {
        val schema = formSchema(ActionRisk.mutating)
        var calls = 0
        val coordinator = NativeActionCoordinator(schema, schema.views.single()) {
            calls += 1
            NativeActionExecutionResult.Failure(
                message = "Server rejected the request",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        }

        coordinator.submit(emptyMap())
        assertIs<NativeActionExecutionState.ValidationFailed>(coordinator.state)
        assertEquals(0, calls)

        coordinator.submit(validValues())
        assertEquals(1, calls)
        assertEquals("Server rejected the request", assertIs<NativeActionExecutionState.Failed>(coordinator.state).message)
    }

    @Test
    fun unknownFormOutcomeBlocksRetryUntilANewerAuthoritativeRefresh() = runBlocking {
        val schema = formSchema(ActionRisk.mutating)
        var calls = 0
        val coordinator = NativeActionCoordinator(schema, schema.views.single()) {
            calls += 1
            NativeActionExecutionResult.Failure(
                message = "Response ended before the result arrived",
                outcome = NativeActionFailureOutcome.Unknown,
            )
        }

        coordinator.submit(validValues(), reconciliationGeneration = 7)

        val awaiting = assertIs<NativeActionExecutionState.AwaitingReconciliation>(coordinator.state)
        assertEquals("Response ended before the result arrived", awaiting.message)
        assertEquals(7, awaiting.reconciliationGeneration)

        coordinator.clearStatus()
        coordinator.submit(validValues(), reconciliationGeneration = 7)
        assertEquals(1, calls)
        assertIs<NativeActionExecutionState.AwaitingReconciliation>(coordinator.state)

        coordinator.reconcileAuthoritativeRefresh(reconciliationGeneration = 7)
        assertIs<NativeActionExecutionState.AwaitingReconciliation>(coordinator.state)

        coordinator.reconcileAuthoritativeRefresh(reconciliationGeneration = 8)
        assertIs<NativeActionExecutionState.Idle>(coordinator.state)

        coordinator.submit(validValues(), reconciliationGeneration = 8)
        assertEquals(2, calls)
        Unit
    }

    @Test
    fun rejectedFormOutcomeRemainsImmediatelyRetryable() = runBlocking {
        val schema = formSchema(ActionRisk.mutating)
        var calls = 0
        val coordinator = NativeActionCoordinator(schema, schema.views.single()) {
            calls += 1
            NativeActionExecutionResult.Failure(
                message = "Validation rejected",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        }

        coordinator.submit(validValues(), reconciliationGeneration = 3)
        assertIs<NativeActionExecutionState.Failed>(coordinator.state)

        coordinator.submit(validValues(), reconciliationGeneration = 3)
        assertEquals(2, calls)
        assertIs<NativeActionExecutionState.Failed>(coordinator.state)
        Unit
    }

    @Test
    fun loadRequestsMustResolveToDeclaredReadOnlyActions() {
        val loadAction = action(
            id = "items.list",
            intent = ActionIntent.list,
            risk = ActionRisk.readOnly,
            method = HttpMethod.GET,
        )
        val schema = baseSchema(
            view = view(NativeComponent.collectionList, sourceActionId = loadAction.id),
            action = loadAction,
        )
        val request = assertIs<NativeRequestBuildResult.Ready>(buildNativeLoadRequest(schema, schema.views.single())).request
        assertIs<NativeActionRequest.Load>(request)

        val unsafe = schema.copy(actions = listOf(loadAction.copy(risk = ActionRisk.mutating)))
        assertIs<NativeRequestBuildResult.Invalid>(buildNativeLoadRequest(unsafe, unsafe.views.single()))
    }

    private fun formSchema(
        risk: ActionRisk,
        requiresConfirmation: Boolean = false,
    ): NativeAppSchema {
        val action = action(
            id = "items.submit",
            intent = if (risk == ActionRisk.destructive) ActionIntent.delete else ActionIntent.create,
            risk = risk,
            method = if (risk == ActionRisk.destructive) HttpMethod.DELETE else HttpMethod.POST,
            requiresConfirmation = requiresConfirmation,
        ).copy(
            inputSchema = buildJsonObject {
                put("properties", buildJsonObject {
                    validValues().keys.forEach { put(it, buildJsonObject {}) }
                })
                put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("title")) })
            },
        )
        val resource = ResourceSpec(
            id = "items",
            name = "Items",
            confidence = Confidence.high,
            fields = listOf(
                field("title", FieldKind.string, required = true),
                field("count", FieldKind.integer),
                field("date", FieldKind.date),
                field("enabled", FieldKind.boolean),
                field("kind", FieldKind.enumeration, enumValues = listOf("personal", "shared")),
                field("file", FieldKind.file),
                field("serverOnly", FieldKind.string, readOnly = true),
            ),
        )
        return NativeAppSchema(
            schemaVersion = "0.1",
            app = AppIdentity("dynamic-test", "Dynamic Test", "1.0"),
            confidence = Confidence.high,
            resources = listOf(resource),
            views = listOf(view(NativeComponent.form, sourceActionId = action.id)),
            actions = listOf(action),
        )
    }

    private fun baseSchema(view: ViewSpec, action: ActionSpec): NativeAppSchema = NativeAppSchema(
        schemaVersion = "0.1",
        app = AppIdentity("dynamic-test", "Dynamic Test", "1.0"),
        confidence = Confidence.high,
        resources = listOf(ResourceSpec("items", "Items", Confidence.high)),
        views = listOf(view),
        actions = listOf(action),
    )

    private fun view(
        component: NativeComponent,
        sourceActionId: String = "items.list",
    ) = ViewSpec("items.view", "Items", "items", component, sourceActionId, Confidence.high)

    private fun action(
        id: String,
        intent: ActionIntent,
        risk: ActionRisk,
        method: HttpMethod,
        requiresConfirmation: Boolean = false,
    ) = ActionSpec(
        id = id,
        label = if (risk == ActionRisk.destructive) "Delete item" else "Save item",
        resourceId = "items",
        binding = ApiBinding(method, "/ocs/v2.php/apps/example/api/items", id),
        intent = intent,
        risk = risk,
        requiresConfirmation = requiresConfirmation,
        confidence = Confidence.high,
    )

    private fun field(
        id: String,
        kind: FieldKind,
        required: Boolean = false,
        readOnly: Boolean = false,
        format: String? = null,
        enumValues: List<String>? = null,
    ) = FieldSpec(id, id.replaceFirstChar { it.uppercase() }, kind, required, readOnly, format, enumValues)

    private fun structuredScalar(value: String) = NativeStructuredValue.Scalar(
        value,
        NativeStructuredScalarKind.string,
    )

    private fun validValues() = mapOf(
        "title" to "Example",
        "count" to "4",
        "date" to "2026-07-22",
        "enabled" to "true",
        "kind" to "personal",
        "file" to "content://selected/item",
    )
}
