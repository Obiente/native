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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeFormsSubmissionTest {
    private val forms = ResourceSpec("forms", "Forms", Confidence.high)

    @Test
    fun answersAreSentAsStringArraysKeyedByQuestionIdInOrder() {
        val plan = assertNotNull(plan())
        assertNull(plan.unavailableReason)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), plan.questions.map(NativeFormsQuestion::id))
        val request = assertIs<NativeFormsSubmissionResult.Ready>(plan.submitRequest(mapOf(
            1L to "Synthetic name", 2L to "11,12", 3L to "4", 4L to "2026-05-06", 5L to "21",
        ))).request
        assertEquals("7", request.values["formId"])
        val answers = Json.parseToJsonElement(request.values.getValue("answers")).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("Synthetic name"))), answers["1"])
        assertEquals(JsonArray(listOf(JsonPrimitive("11"), JsonPrimitive("12"))), answers["2"])
        assertEquals(JsonArray(listOf(JsonPrimitive("4"))), answers["3"])
        assertTrue("6" !in answers, "A file question is never answered natively.")
    }

    @Test
    fun validationCoversRequiredOptionsScaleAndDates() {
        val plan = assertNotNull(plan())
        val errors = assertIs<NativeFormsSubmissionResult.Invalid>(plan.submitRequest(mapOf(
            2L to "11,99", 3L to "9", 4L to "06/05/2026", 5L to "404",
        ))).errors
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), errors.keys)
    }

    @Test
    fun closedRepeatedOrUnpermittedFormsExplainWhyInsteadOfOfferingSubmit() {
        assertEquals("This form is closed.", plan(form(state = "1"))?.unavailableReason)
        assertEquals("You cannot respond to this form.", plan(form(permissions = """["results"]"""))?.unavailableReason)
        assertEquals("You have already responded to this form.", plan(form(canSubmit = "false"))?.unavailableReason)
        assertNull(plan(form(canSubmit = "false"))?.submit)
        val required = plan(form(questions = """[{"id":1,"formId":7,"order":1,"type":"file","isRequired":true,"text":"Upload"}]"""))
        assertNull(assertNotNull(required).submit)
    }

    @Test
    fun noPlanForUntrustedContractsOtherVersionsOrMalformedQuestions() {
        assertNull(plan(evidence = EvidenceSource.networkObservation)?.submit)
        assertNull(plan(version = "4.3.0"))
        assertNull(plan(form(questions = "not json")))
        assertNull(plan(form(questions = """[{"id":1,"formId":99,"type":"short","text":"x"}]""")), "Foreign questions.")
        assertNull(plan(form(questions = """[{"id":1,"type":"short"},{"id":1,"type":"long"}]""")), "Duplicate IDs.")
        assertNull(plan(form().copy(actionSafeIdentity = false)))
    }

    private fun plan(
        record: NativeRecord = form(),
        evidence: EvidenceSource = EvidenceSource.verifiedAppPackage,
        version: String = "5.4.1",
    ): NativeFormsSubmissionPlan? {
        val schema = NativeAppSchema("0.1", AppIdentity("forms", "Forms", version), Confidence.high,
            resources = listOf(forms),
            actions = listOf(ActionSpec("api-new-submission", "Submit", "submissions",
                ApiBinding(HttpMethod.POST, "/ocs/v2.php/apps/forms/api/v3/forms/{formId}/submissions", "api-new-submission",
                    listOf("formId"), listOf("formId"), bodyFieldNames = listOf("answers", "shareHash")),
                ActionIntent.create, ActionRisk.mutating, true, Confidence.high,
                evidence = listOf(Evidence(evidence, "synthetic")))))
        return nativeFormsSubmissionPlan(schema, forms, record)
    }

    private fun form(
        state: String = "0",
        canSubmit: String = "true",
        permissions: String = """["submit"]""",
        questions: String = QUESTIONS,
    ) = NativeRecord("7", mapOf("id" to "7", "title" to "Synthetic survey", "state" to state,
        "canSubmit" to canSubmit, "permissions" to permissions, "questions" to questions))

    private companion object {
        val QUESTIONS = """[
            {"id":2,"formId":7,"order":2,"type":"multiple","isRequired":false,"text":"Pick",
             "options":[{"id":11,"text":"A"},{"id":12,"text":"B"}]},
            {"id":1,"formId":7,"order":1,"type":"short","isRequired":true,"text":"Name"},
            {"id":3,"formId":7,"order":3,"type":"linearscale","isRequired":false,"text":"Rate",
             "extraSettings":{"optionsLowest":1,"optionsHighest":5}},
            {"id":4,"formId":7,"order":4,"type":"date","isRequired":false,"text":"When"},
            {"id":5,"formId":7,"order":5,"type":"dropdown","isRequired":false,"text":"Size",
             "options":[{"id":21,"text":"S"}]},
            {"id":6,"formId":7,"order":6,"type":"file","isRequired":false,"text":"Attachment"}
        ]""".trimIndent()
    }
}
