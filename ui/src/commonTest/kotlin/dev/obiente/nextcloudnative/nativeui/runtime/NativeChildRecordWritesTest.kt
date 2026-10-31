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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("Synthetic dinner", request.values["what"], "A replacement carries every field read back.")
        val holdsHiddenValue = assertNotNull(verified.record(expenseRecord().let { it.copy(values = it.values + ("repeat" to "n")) }))
        assertNotNull(holdsHiddenValue.editUnavailableReason, "Replacing would clear a value the editor does not show.")
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
        context: (NativeDatasetContext) -> NativeDatasetContext = { it },
    ): NativeChildRecordWritePlan? = nativeChildRecordWritePlan(schema, "expenses.list", context(NativeDatasetContext(
        parentResourceId = "projects",
        parentRecord = project,
        relatedRecords = mapOf("members" to listOf(
            NativeRecord("1", mapOf("id" to "1", "name" to "Ada", "activated" to "true")),
            NativeRecord("2", mapOf("id" to "2", "name" to "Lin", "activated" to "true")),
            NativeRecord("3", mapOf("id" to "3", "name" to "Sam", "activated" to "false")),
        )),
    )))

    private fun schema(
        evidence: EvidenceSource = EvidenceSource.openApi,
        // The compiler marks exact integer arrays with this format; the request builder requires it.
        payedFor: String = """{"type":"array","format":"$DYNAMIC_INTEGER_ARRAY_FORMAT","items":{"type":"integer"}}""",
        updateMethod: HttpMethod = HttpMethod.PATCH,
        recovery: String? = null,
    ): NativeAppSchema {
        val properties = """"what":{"type":"string"},"amount":{"type":"number"},"date":{"type":"string","format":"date"},""" +
            """"payer":{"type":"integer"},"payedFor":$payedFor,"categoryId":{"type":"integer"},""" +
            """"comment":{"type":"string"},"repeat":{"type":"string"}"""
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

    private fun expenseRecord(payer: String = "1") = NativeRecord("44", mapOf(
        "id" to "44", "what" to "Synthetic dinner", "amount" to "30.0", "date" to "2026-01-05",
        "payer_id" to payer, "payedFor" to "[1,3]", "categoryid" to "7", "comment" to "",
    ))

    private companion object {
        const val LIST = "/apps/splitter/api/projects/{projectId}/expenses"
    }
}
