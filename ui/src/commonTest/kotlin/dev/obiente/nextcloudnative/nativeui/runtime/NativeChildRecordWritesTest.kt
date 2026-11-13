package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Child record writes for an expense-project-shaped contract of an app the client has never seen
 * ("splitter" 0.0.1). Fields, choices, and requirements come from the synthetic contract and data.
 */
class NativeChildRecordWritesTest {
    @Test
    fun expenseContractGetsCreateWithMemberChoicesFromTheLinkedMembersResource() {
        val plan = assertNotNull(plan())
        assertTrue(plan.expense)
        val create = assertNotNull(plan.create)
        assertNull(create.unavailableReason)
        assertEquals(listOf("what", "amount", "date", "payer", "payedFor", "categoryId", "comment"),
            create.shownFields.map(NativeRecordField::name), "Optional fields without a recognized role are left to the server.")
        val payer = assertIs<NativeRecordFieldInput.Choice>(create.shownFields.single { it.name == "payer" }.input)
        assertEquals(listOf("1", "2", "3"), payer.options.map(NativeFieldOption::id))
        assertEquals(listOf(true, true, false), payer.options.map(NativeFieldOption::active))
        val category = assertIs<NativeRecordFieldInput.Choice>(create.shownFields.single { it.name == "categoryId" }.input)
        assertEquals(listOf(NativeFieldOption("7", "Food")), category.options, "Categories come from the parent record.")

        val draft = plan.newDraft("2026-03-04")
        assertEquals("1,2", draft["payedFor"], "Everyone active is preselected; inactive members are not.")
        assertEquals("2026-03-04", draft["date"])
        val request = assertIs<NativeChildRecordRequestResult.Ready>(plan.createRequest(draft + mapOf(
            "what" to "Groceries", "amount" to "12.5", "payer" to "1", "categoryId" to "7",
        ))).request
        assertEquals("expenses.create", request.action.id)
        assertEquals("p1", request.values["projectId"])
        assertEquals("[1,2]", request.values["payedFor"], "The contract declares an integer array.")
        assertEquals("12.5", request.values["amount"])
        assertTrue("repeat" !in request.values && "comment" !in request.values)
    }

    @Test
    fun aRequiredFieldMissingFromTheDraftBlocksSubmit() {
        val plan = assertNotNull(plan())
        val errors = assertIs<NativeChildRecordRequestResult.Invalid>(
            plan.createRequest(mapOf("amount" to "ten", "payer" to "3", "date" to "03/04/2026")),
        ).errors
        assertEquals(setOf("what", "amount", "payer", "payedFor", "date"), errors.keys)
        assertEquals("What is required.", errors["what"])
    }

    @Test
    fun editReadsTheRecordBackAndPatchSendsOnlyTheChangedField() {
        val plan = assertNotNull(plan())
        val expense = assertNotNull(plan.record(expenseRecord(payer = "3")))
        assertNull(expense.editUnavailableReason)
        assertEquals("3", expense.draft["payer"], "An inactive payer of an existing expense is kept.")
        assertEquals("1,3", expense.draft["payedFor"])
        val request = assertIs<NativeChildRecordRequestResult.Ready>(
            plan.updateRequest(expense, expense.draft + ("amount" to "20")),
        ).request
        assertEquals("expenses.update", request.action.id)
        assertEquals(mapOf("projectId" to "p1", "expenseId" to "44", "amount" to "20"), request.values)
        assertEquals(NativeChildRecordRequestResult.NoChanges, plan.updateRequest(expense, expense.draft))
    }

    @Test
    fun deleteBindsTheParentAndTheRecord() {
        val plan = assertNotNull(plan())
        val expense = assertNotNull(plan.record(expenseRecord()))
        val request = assertIs<NativeChildRecordRequestResult.Ready>(plan.deleteRequest(expense)).request
        assertEquals(mapOf("projectId" to "p1", "expenseId" to "44"), request.values)
    }

    @Test
    fun peoplePackedIntoAStringLeaveTheWritesUnavailableInsteadOfGuessingAnEncoding() {
        val plan = assertNotNull(plan(schema = schema(payedFor = """{"type":"string"}""")))
        val reason = assertNotNull(plan.create?.unavailableReason)
        assertTrue("payed for" in reason)
        assertIs<NativeChildRecordRequestResult.Unavailable>(plan.createRequest(mapOf("what" to "x")))
        assertNotNull(assertNotNull(plan.record(expenseRecord())).editUnavailableReason)
        assertNotNull(plan.delete, "Deleting needs no field encoding.")
    }

    @Test
    fun replacementEditsNeedAVerifiableReadAndACompleteCopyOfTheRecord() {
        val unverified = assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT)))
        assertNotNull(unverified.update?.unavailableReason)
        val verified = assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT, recovery = "expenses.read")))
        val complete = assertNotNull(verified.record(expenseRecord()))
        val request = assertIs<NativeChildRecordRequestResult.Ready>(
            verified.updateRequest(complete, complete.draft + ("amount" to "20")),
        ).request
        assertEquals("20", request.values["amount"])
        assertTrue("what" !in request.values, "Untouched fields are not re-encoded from the draft.")
        val readBack = assertNotNull(request.readBack, "A replacement is read back after it is sent.")
        assertTrue(readBack.echoUntouched)
        assertEquals(JsonPrimitive("Synthetic dinner"), readBack.untouched["what"], "A replacement echoes every field read back.")
        assertEquals(JsonPrimitive("n"), readBack.untouched["repeat"], "A field the editor does not show travels unchanged.")
        assertEquals(JsonPrimitive(1), readBack.untouched["payer"], "A stored reference alias travels verbatim.")
        assertEquals(JsonPrimitive(30.0), readBack.previous["amount"])
        val missingField = assertNotNull(verified.record(expenseRecord().let { it.copy(wireValues = it.wireValues - "repeat") }))
        assertNotNull(missingField.editUnavailableReason, "Replacing would clear a declared value the read did not return.")
    }

    @Test
    fun replacementEditsAreGuardedByAFreshReadOfTheStoredBodyValues() {
        val plan = assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT, recovery = "expenses.read")))
        val expense = assertNotNull(plan.record(expenseRecord()))
        val request = assertIs<NativeChildRecordRequestResult.Ready>(
            plan.updateRequest(expense, expense.draft + ("amount" to "20")),
        ).request
        val precondition = assertNotNull(request.precondition, "A replacement is never sent from an unchecked copy.")
        assertEquals(mapOf("projectId" to "p1", "expenseId" to "44"), precondition.readValues)
        assertTrue(precondition.holds(listOf(expenseRecord())))
        assertTrue(precondition.holds(listOf(expenseRecord().stored("lastChanged", JsonPrimitive(999)))),
            "A value outside the write body does not block the edit.")
        assertFalse(precondition.holds(listOf(expenseRecord().stored("what", JsonPrimitive("Edited elsewhere")))),
            "A concurrent change to a field this edit did not touch is detected.")
        assertFalse(precondition.holds(listOf(expenseRecord().stored("amount", JsonPrimitive(31.0)))),
            "A concurrent change to the edited field is detected.")
        assertFalse(precondition.holds(listOf(expenseRecord(payer = "2"))))
        assertFalse(precondition.holds(emptyList()))
        assertEquals(NativeEditorWriteRetry.AfterRefresh, plan.updateRetry)
        val patch = assertNotNull(plan())
        val patchExpense = assertNotNull(patch.record(expenseRecord()))
        assertNull(assertIs<NativeChildRecordRequestResult.Ready>(
            patch.updateRequest(patchExpense, patchExpense.draft + ("amount" to "20")),
        ).request.precondition)
        assertEquals(NativeEditorWriteRetry.Idempotent, patch.updateRetry)
    }

    @Test
    fun anUnconfirmedReplacementWaitsForARefreshAndAChangedRecordOffersOne(): Unit = runBlocking {
        val plan = assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT, recovery = "expenses.read")))
        val editing = NativeChildRecordEditing()
        editing.open(plan, NativeChildRecordTarget.Edit(assertNotNull(plan.record(expenseRecord()))), "2026-03-04")
        editing.edit("amount", "20")
        var sent = 0
        editing.submit(plan, {
            sent++
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }) {}
        assertFalse(editing.session.canSubmit, "Stale values are not resent before a fresh read arrives.")
        editing.session.acceptAuthoritativeRefresh()
        assertTrue(editing.session.canSubmit)
        editing.submit(plan, { nativeWritePreconditionFailure(NativeWritePreconditionFailure.Changed) }) {}
        assertIs<NativeEditorWriteStatus.Changed>(editing.session.status)
        assertFalse(editing.session.canSubmit, "A changed record is refreshed and edited again instead.")
        assertEquals(1, sent)
    }

    @Test
    fun aPersonFieldNotNamedAfterAPeopleCollectionNeedsLoadedValuesThatResolveInIt() {
        val withUser = schema(extraProperties = ""","userId":{"type":"string"}""")
        val unproven = assertNotNull(plan(schema = withUser))
        assertNull(unproven.create?.fields?.single { it.name == "userId" }?.input,
            "A members list is not a user picker just because both name people.")
        val proven = assertNotNull(plan(schema = withUser,
            records = listOf(expenseRecord().let { it.copy(values = it.values + ("userId" to "2")) })))
        val user = assertIs<NativeRecordFieldInput.Choice>(proven.create?.fields?.single { it.name == "userId" }?.input)
        assertEquals(listOf("1", "2", "3"), user.options.map(NativeFieldOption::id), "Loaded values resolve in members.")

        assertIs<NativeRecordFieldInput.Choice>(plan()?.create?.fields?.single { it.name == "payer" }?.input,
            "Stored payers resolve in members, so members are the payer choices.")
        assertNull(plan(records = emptyList())?.create?.fields?.single { it.name == "payer" }?.input,
            "Without a stored payer nothing proves which collection holds payers.")
        assertNull(plan(records = listOf(expenseRecord(payer = "99")))?.create?.fields?.single { it.name == "payer" }?.input,
            "A stored payer that is not a member disproves the binding.")
        val own = assertNotNull(plan(records = emptyList(), context = { context ->
            context.copy(relatedRecords = context.relatedRecords + ("payers" to listOf(
                NativeRecord("8", mapOf("id" to "8", "name" to "Synthetic payer")),
            )))
        }))
        val payer = assertIs<NativeRecordFieldInput.Choice>(own.create?.fields?.single { it.name == "payer" }?.input)
        assertEquals(listOf("8"), payer.options.map(NativeFieldOption::id), "A collection named after the field needs no proof.")
    }

    @Test
    fun noPlanWithoutPermissionEvidenceTrustedContractsOrAScopedParent() {
        assertNull(plan(project = project(permissions = null)))
        assertNull(plan(schema = schema(evidence = EvidenceSource.networkObservation)))
        assertNull(plan(context = { it.copy(bindingValues = mapOf("projectId" to "other")) }))
        assertNull(plan(context = { it.copy(parentRecord = it.parentRecord?.copy(actionSafeIdentity = false)) }))
        val readOnly = plan(project = project(permissions = """{"create":false,"update":false,"delete":false}"""))
        assertNull(readOnly)
    }

    @Test
    fun aContractWithoutACreateOperationOffersNoCreate() {
        val schema = schema().let { it.copy(actions = it.actions.filterNot { action -> action.id == "expenses.create" }) }
        val plan = assertNotNull(plan(schema = schema))
        assertNull(plan.create)
        assertIs<NativeChildRecordRequestResult.Unavailable>(plan.createRequest(mapOf("what" to "x")))
    }

    @Test
    fun theSameContractUnderAnotherAppIdentityPlansTheSameWrites() {
        val renamed = schema().copy(app = AppIdentity("ledgerly", "Ledgerly", "12.4.0"))
        assertEquals(plan()?.create?.shownFields, plan(schema = renamed)?.create?.shownFields)
    }

    private fun plan(
        schema: NativeAppSchema = schema(),
        project: NativeRecord = project(),
        records: List<NativeRecord> = listOf(expenseRecord()),
        context: (NativeDatasetContext) -> NativeDatasetContext = { it },
    ): NativeChildRecordWritePlan? = nativeChildRecordWritePlan(schema, "expenses.list", context(NativeDatasetContext(
        parentResourceId = "projects",
        parentRecord = project,
        relatedRecords = mapOf("members" to listOf(
            NativeRecord("1", mapOf("id" to "1", "name" to "Ada", "activated" to "true")),
            NativeRecord("2", mapOf("id" to "2", "name" to "Lin", "activated" to "true")),
            NativeRecord("3", mapOf("id" to "3", "name" to "Sam", "activated" to "false")),
        )),
    )), records)

    private fun schema(
        evidence: EvidenceSource = EvidenceSource.openApi,
        // The compiler marks exact integer arrays with this format; the request builder requires it.
        payedFor: String = """{"type":"array","format":"$DYNAMIC_INTEGER_ARRAY_FORMAT","items":{"type":"integer"}}""",
        updateMethod: HttpMethod = HttpMethod.PATCH,
        recovery: String? = null,
        extraProperties: String = "",
    ): NativeAppSchema {
        val properties = """"what":{"type":"string"},"amount":{"type":"number"},"date":{"type":"string","format":"date"},""" +
            """"payer":{"type":"integer"},"payedFor":$payedFor,"categoryId":{"type":"integer"},""" +
            """"comment":{"type":"string"},"repeat":{"type":"string"}""" + extraProperties
        val create = """{"type":"object","required":["what","amount","payer","payedFor"],"properties":{$properties}}"""
        val update = """{"type":"object","properties":{$properties}}"""
        val item = "$LIST/{expenseId}"
        return NativeAppSchema("0.1", AppIdentity("splitter", "Splitter", "0.0.1"), Confidence.high,
            resources = listOf(ResourceSpec("expenses", "Expenses", Confidence.high)),
            actions = listOf(
                action("expenses.list", HttpMethod.GET, LIST, ActionIntent.list, null, evidence),
                action("expenses.read", HttpMethod.GET, item, ActionIntent.read, null, evidence),
                action("expenses.create", HttpMethod.POST, LIST, ActionIntent.create, create, evidence),
                action("expenses.update", updateMethod, item, ActionIntent.update, update, evidence)
                    .copy(resultRecoveryActionId = recovery),
                action("expenses.delete", HttpMethod.DELETE, item, ActionIntent.delete, null, evidence),
            ))
    }

    private fun action(id: String, method: HttpMethod, path: String, intent: ActionIntent, body: String?, evidence: EvidenceSource): ActionSpec {
        val pathParameters = Regex("\\{([^}]+)}").findAll(path).map { it.groupValues[1] }.toList()
        val bodySchema = body?.let(Json::parseToJsonElement)
        val bodyFields = bodySchema?.let { (Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)["properties"] as kotlinx.serialization.json.JsonObject }
            ?.keys?.toList().orEmpty()
        return ActionSpec(id, id, "expenses", ApiBinding(method, path, id, pathParameters, pathParameters,
            bodyFieldNames = bodyFields, bodySchema = bodySchema), intent,
            when (intent) {
                ActionIntent.list, ActionIntent.read -> ActionRisk.readOnly
                ActionIntent.delete -> ActionRisk.destructive
                else -> ActionRisk.mutating
            }, true, Confidence.high, evidence = listOf(Evidence(evidence, "synthetic")))
    }

    private fun project(permissions: String? = """{"create":true,"update":true,"delete":true}""") = NativeRecord("p1", buildMap {
        put("id", "p1")
        permissions?.let { put("permissions", it) }
        put("categories", """{"7":{"id":7,"name":"Food"}}""")
    })

    @Test
    fun aReplacementEchoesAStoredDateTimeInsteadOfTheDateTheEditorShows() {
        val plan = assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT, recovery = "expenses.read")))
        val stored = expenseRecord().stored("date", JsonPrimitive("2026-01-05T10:30:00+00:00"))
        val expense = assertNotNull(plan.record(stored))
        assertEquals("2026-01-05", expense.draft["date"], "The editor shows the calendar date.")
        val request = assertIs<NativeChildRecordRequestResult.Ready>(plan.updateRequest(expense, expense.draft + ("amount" to "20"))).request
        assertTrue("date" !in request.values)
        assertEquals(JsonPrimitive("2026-01-05T10:30:00+00:00"), request.readBack?.untouched?.get("date"), "The stored value is never shortened.")
    }

    @Test
    fun aNullableFieldStoredAsNullIsEchoedAsNullAndANonNullableNullKeepsTheRecordReadOnly() {
        val nullable = schema(updateMethod = HttpMethod.PUT, recovery = "expenses.read", extraProperties = ",\"note\":{\"type\":\"string\",\"nullable\":true}")
        val plan = assertNotNull(plan(schema = nullable))
        val withNull = assertNotNull(plan.record(expenseRecord().stored("note", JsonNull)))
        assertNull(withNull.editUnavailableReason)
        val request = assertIs<NativeChildRecordRequestResult.Ready>(plan.updateRequest(withNull, withNull.draft + ("amount" to "20"))).request
        assertEquals(JsonNull, request.readBack?.untouched?.get("note"))
        val strict = assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT, recovery = "expenses.read")))
        assertNotNull(assertNotNull(strict.record(expenseRecord().stored("comment", JsonNull))).editUnavailableReason,
            "A null in a field declared non-nullable cannot be echoed exactly.")
    }

    @Test
    fun aPartialUpdateIsReadBackWithoutEchoingUntouchedFields() {
        val patch = assertNotNull(plan())
        val expense = assertNotNull(patch.record(expenseRecord()))
        val request = assertIs<NativeChildRecordRequestResult.Ready>(patch.updateRequest(expense, expense.draft + ("amount" to "20"))).request
        val readBack = assertNotNull(request.readBack)
        assertFalse(readBack.echoUntouched)
        assertTrue(readBack.preReadBaseline, "A merge edit compares the edited fields with a fresh read first.")
        assertEquals(JsonPrimitive("Synthetic dinner"), readBack.untouched["what"], "Untouched fields are compared after the write.")
    }

    @Test
    fun aFormEncodedReplacementStaysReadOnlyInsteadOfFailingAtSubmit() {
        fun withBody(contentType: String): NativeAppSchema = schema(updateMethod = HttpMethod.PUT, recovery = "expenses.read").let { base ->
            base.copy(actions = base.actions.map { action ->
                if (action.id == "expenses.update") action.copy(binding = action.binding.copy(bodyContentType = contentType)) else action
            })
        }
        val form = assertNotNull(plan(schema = withBody("application/x-www-form-urlencoded")))
        val expense = assertNotNull(form.record(expenseRecord()))
        assertTrue(expense.editUnavailableReason.orEmpty().contains("form body"), expense.editUnavailableReason)
        assertIs<NativeChildRecordRequestResult.Unavailable>(form.updateRequest(expense, expense.draft + ("amount" to "20")))
        val update = withBody("application/x-www-form-urlencoded").actions.single { it.id == "expenses.update" }
        val stored = update.binding.bodyFieldNames.associateWith { name -> if (name == "payedFor") JsonArray(emptyList()) else JsonPrimitive(1) } +
            listOf("what", "date", "comment", "repeat").associateWith { JsonPrimitive("synthetic") }
        val planned = assertIs<NativeFullEchoUpdatePlan.ReadOnly>(nativeFullEchoUpdatePlan(update, stored, setOf("amount")))
        assertEquals(NativeFullEchoRefusal.NonJsonBody, planned.reason)

        val json = assertNotNull(plan(schema = withBody("application/json; charset=utf-8")))
        val editable = assertNotNull(json.record(expenseRecord()))
        assertNull(editable.editUnavailableReason)
        assertIs<NativeChildRecordRequestResult.Ready>(json.updateRequest(editable, editable.draft + ("amount" to "20")))
    }

    @Test
    fun anEditedOptionalFieldMissingFromTheReadHasAnExplicitAbsentBaseline() {
        val patch = assertNotNull(plan())
        val original = expenseRecord().let { it.copy(values = it.values - "comment", wireValues = it.wireValues - "comment") }
        val expense = assertNotNull(patch.record(original))
        val request = assertIs<NativeChildRecordRequestResult.Ready>(
            patch.updateRequest(expense, expense.draft + ("comment" to "New comment")),
        ).request
        val readBack = assertNotNull(request.readBack)
        assertEquals(setOf("comment"), readBack.editedFields)
        assertEquals(setOf("comment"), readBack.absentPrevious)
        assertTrue("comment" !in readBack.previous)
    }

    private fun expenseRecord(payer: String = "1") = NativeRecord(
        "44",
        mapOf(
            "id" to "44", "what" to "Synthetic dinner", "amount" to "30.0", "date" to "2026-01-05",
            "payer_id" to payer, "payedFor" to "[1,3]", "categoryid" to "7", "comment" to "", "repeat" to "n",
        ),
        wireValues = mapOf(
            "id" to JsonPrimitive(44), "what" to JsonPrimitive("Synthetic dinner"), "amount" to JsonPrimitive(30.0),
            "date" to JsonPrimitive("2026-01-05"), "payer_id" to JsonPrimitive(payer.toInt()),
            "payedFor" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(3))), "categoryid" to JsonPrimitive(7),
            "comment" to JsonPrimitive(""), "repeat" to JsonPrimitive("n"),
        ),
    )

    /** The record as the server would return it with [name] stored as [value]. */
    private fun NativeRecord.stored(name: String, value: JsonElement) = copy(
        values = values + (name to (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content),
        wireValues = wireValues + (name to value),
    )

    private companion object {
        const val LIST = "/apps/splitter/api/projects/{projectId}/expenses"
    }
}
