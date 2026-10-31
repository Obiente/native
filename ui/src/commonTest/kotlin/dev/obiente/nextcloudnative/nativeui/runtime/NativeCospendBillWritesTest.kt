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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeCospendBillWritesTest {
    private val bills = ResourceSpec("bills", "Bills", Confidence.high)

    @Test
    fun newBillDefaultsToEveryActiveMemberAndSendsReviewedFields() {
        val plan = assertNotNull(plan())
        val draft = plan.newDraft("2026-03-04")
        assertEquals(setOf("1", "2"), draft.owerIds, "Inactive members are not preselected.")
        val request = assertIs<NativeCospendBillRequestResult.Ready>(
            plan.createRequest(draft.copy(what = "Groceries", amount = "12.5", payerId = "1", categoryId = "7")),
        ).request
        assertEquals("p1", request.values["projectId"])
        assertEquals("1,2", request.values["payedFor"])
        assertEquals("n", request.values["repeat"])
        assertEquals("2026-03-04", request.values["date"])
        assertEquals("7", request.values["categoryId"])
        assertEquals("0", request.values["paymentModeId"], "No payment mode is sent as Cospend's none value.")
    }

    @Test
    fun validationRequiresDescriptionAmountDatePayerAndOwers() {
        val plan = assertNotNull(plan())
        val errors = assertIs<NativeCospendBillRequestResult.Invalid>(
            plan.createRequest(NativeCospendBillDraft(payerId = "3", date = "03/04/2026")),
        ).errors
        assertEquals(NativeCospendBillField.entries.toSet(), errors.keys)
    }

    @Test
    fun existingBillKeepsItsDeactivatedPayerAndUpdatesAllFieldsWithIdentities() {
        val plan = assertNotNull(plan())
        val bill = assertNotNull(plan.bill(billRecord(payer = "3", owers = "3, 1")))
        assertEquals(setOf("3", "1"), bill.draft.owerIds)
        val request = assertIs<NativeCospendBillRequestResult.Ready>(
            plan.updateRequest(bill, bill.draft.copy(amount = "20")),
        ).request
        assertEquals("api-edit-bill", request.action.id)
        assertEquals("p1", request.values["projectId"])
        assertEquals("44", request.values["billId"])
        assertEquals("1,3", request.values["payedFor"])
        assertTrue("repeat" !in request.values, "Editing never changes the repeat rule.")
    }

    @Test
    fun deleteMovesToTrashAndBindsProjectAndBill() {
        val plan = assertNotNull(plan())
        val bill = assertNotNull(plan.bill(billRecord()))
        val request = assertIs<NativeCospendBillRequestResult.Ready>(plan.deleteRequest(bill)).request
        assertEquals(mapOf("projectId" to "p1", "billId" to "44", "moveToTrash" to "true"), request.values)
    }

    @Test
    fun billsNamingUnknownMembersOrMalformedValuesAreNotEditable() {
        val plan = assertNotNull(plan())
        assertNull(plan.bill(billRecord(payer = "99")))
        assertNull(plan.bill(billRecord(owers = "1, 99")))
        assertNull(plan.bill(billRecord(owers = "")))
        assertNull(plan.bill(billRecord().copy(actionSafeIdentity = false)))
    }

    @Test
    fun noPlanForViewersUntrustedContractsOtherVersionsOrUnscopedProjects() {
        assertNull(plan(project = project(accessLevel = "1")))
        assertNull(plan(project = project(accessLevel = null)))
        assertNull(plan(evidence = EvidenceSource.networkObservation))
        assertNull(plan(version = "5.0.0"))
        assertNull(plan(project = project(members = "not json")))
        assertNull(plan(project = project(members = """[{"id":1,"name":"Ada","activated":false}]""")))
        assertNull(plan(context = { it.copy(bindingValues = mapOf("projectId" to "other")) }))
        assertNull(plan(context = { it.copy(parentResourceId = "bills") }))
    }

    private fun plan(
        project: NativeRecord = project(),
        evidence: EvidenceSource = EvidenceSource.verifiedAppPackage,
        version: String = "4.3.0",
        context: (NativeDatasetContext) -> NativeDatasetContext = { it },
    ): NativeCospendBillPlan? {
        val base = "/ocs/v2.php/apps/cospend/api/v1/projects/{projectId}/bills"
        val fields = listOf("what", "amount", "date", "payer", "payedFor", "repeat", "categoryId", "paymentModeId", "comment")
        val schema = NativeAppSchema("0.1", AppIdentity("cospend", "Cospend", version), Confidence.high,
            resources = listOf(bills),
            actions = listOf(
                action("api-create-bill", HttpMethod.POST, base, listOf("projectId"), fields, evidence),
                action("api-edit-bill", HttpMethod.PUT, "$base/{billId}", listOf("projectId", "billId"), fields - "repeat", evidence),
                action("api-delete-bill", HttpMethod.DELETE, "$base/{billId}", listOf("projectId", "billId"), listOf("moveToTrash"), evidence),
            ))
        return nativeCospendBillPlan(schema, bills, context(NativeDatasetContext(parentResourceId = "projects", parentRecord = project)))
    }

    private fun action(
        id: String, method: HttpMethod, path: String, pathParameters: List<String>, body: List<String>, evidence: EvidenceSource,
    ) = ActionSpec(id, id, "bills", ApiBinding(method, path, id, pathParameters, pathParameters, bodyFieldNames = body),
        if (method == HttpMethod.DELETE) ActionIntent.delete else ActionIntent.create,
        if (method == HttpMethod.DELETE) ActionRisk.destructive else ActionRisk.mutating, true, Confidence.high,
        evidence = listOf(Evidence(evidence, "synthetic")))

    private fun project(
        accessLevel: String? = "2",
        members: String = """[{"id":1,"name":"Ada","activated":true},{"id":2,"name":"Lin","activated":true},""" +
            """{"id":3,"name":"Sam","activated":false}]""",
    ) = NativeRecord("p1", buildMap {
        put("id", "p1")
        accessLevel?.let { put("myaccesslevel", it) }
        put("members", members)
        put("categories", """{"7":{"id":7,"name":"Food"}}""")
        put("paymentmodes", "[]")
    })

    private fun billRecord(payer: String = "1", owers: String = "1, 2") = NativeRecord("44", mapOf(
        "id" to "44", "what" to "Synthetic dinner", "amount" to "30.0", "date" to "2026-01-05",
        "payer_id" to payer, "owerIds" to owers, "categoryid" to "7", "paymentmodeid" to "0", "comment" to "",
    ))
}
