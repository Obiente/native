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

/** Questionnaire submission for an app the client has never seen ("surveyor" 3.0.0). */
class NativeQuestionnaireSubmissionTest {
    private val surveys = ResourceSpec("surveys", "Surveys", Confidence.high)

    @Test
    fun aQuestionnaireShapedContractGetsSubmissionWithAnswersKeyedByQuestionInOrder() {
        val plan = assertNotNull(plan())
        assertNull(plan.unavailableReason)
        assertEquals("answers", plan.answersField)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), plan.questions.map(NativeQuestion::id))
        val request = assertIs<NativeQuestionnaireResult.Ready>(plan.submitRequest(mapOf(
            "1" to "Synthetic name", "2" to "11,12", "3" to "4", "4" to "2026-05-06", "5" to "21",
        ))).request
        assertEquals("responses.create", request.action.id)
        assertEquals("7", request.values["surveyId"])
        val answers = Json.parseToJsonElement(request.values.getValue("answers")).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("Synthetic name"))), answers["1"])
        assertEquals(JsonArray(listOf(JsonPrimitive("11"), JsonPrimitive("12"))), answers["2"])
        assertEquals(JsonArray(listOf(JsonPrimitive("4"))), answers["3"])
        assertTrue("6" !in answers, "A file question is never answered natively.")
        assertEquals(NativeQuestionKind.Unsupported, plan.questions.single { it.id == "7" }.kind,
            "A combined date and time answer has no declared format and is not answered.")
    }

    @Test
    fun validationCoversRequiredOptionsScaleAndDates() {
        val plan = assertNotNull(plan())
        val errors = assertIs<NativeQuestionnaireResult.Invalid>(plan.submitRequest(mapOf(
            "2" to "11,99", "3" to "9", "4" to "06/05/2026", "5" to "404",
        ))).errors
        assertEquals(setOf("1", "2", "3", "4", "5"), errors.keys)
        assertEquals("An answer is required.", errors["1"])
    }

    @Test
    fun closedLimitedOrUnpermittedQuestionnairesExplainWhyInsteadOfOfferingSubmit() {
        assertEquals("This form is closed.", plan(survey(extra = mapOf("isClosed" to "true")))?.unavailableReason)
        assertEquals("You cannot respond to this form.", plan(survey(permissions = """["results"]"""))?.unavailableReason)
        assertEquals("This form does not accept a response from you right now.", plan(survey(canSubmit = "false"))?.unavailableReason)
        assertEquals("This form accepts no more responses.",
            plan(survey(extra = mapOf("isMaxSubmissionsReached" to "true")))?.unavailableReason)
        assertNull(plan(survey(canSubmit = "false"))?.submit)
        val required = plan(survey(questions = """[{"id":1,"surveyId":7,"order":1,"type":"file","isRequired":true,"text":"Upload"}]"""))
        assertNull(assertNotNull(required).submit)
    }

    @Test
    fun noPlanWithoutTheAnswerMapContractOrWithForeignOrMalformedQuestions() {
        assertNull(plan(evidence = EvidenceSource.networkObservation))
        assertNull(plan(answersSchema = """{"type":"string"}"""), "Without a declared answer map nothing is submitted.")
        assertNull(plan(survey(questions = "not json")))
        assertNull(plan(survey(questions = """[{"id":1,"surveyId":99,"type":"short","text":"x"}]""")), "Foreign questions.")
        assertNull(plan(survey(questions = """[{"id":1,"type":"short"},{"id":1,"type":"long"}]""")), "Duplicate IDs.")
        assertNull(plan(survey().copy(actionSafeIdentity = false)))
    }

    @Test
    fun theSameShapeUnderAnotherAppIdentityAndVersionIsSubmittable() {
        val plan = assertNotNull(plan(app = AppIdentity("polling", "Polling", "0.1.0")))
        assertNotNull(plan.submit)
    }

    private fun plan(
        record: NativeRecord = survey(),
        evidence: EvidenceSource = EvidenceSource.verifiedAppPackage,
        answersSchema: String = """{"type":"object","additionalProperties":{"type":"array","items":{"type":"string"}}}""",
        app: AppIdentity = AppIdentity("surveyor", "Surveyor", "3.0.0"),
    ): NativeQuestionnairePlan? {
        val item = "/apps/surveyor/api/surveys/{surveyId}"
        val body = Json.parseToJsonElement("""{"type":"object","properties":{"answers":$answersSchema,"shareHash":{"type":"string"}}}""")
        val schema = NativeAppSchema("0.1", app, Confidence.high,
            resources = listOf(surveys),
            actions = listOf(
                ActionSpec("surveys.read", "Survey", "surveys", ApiBinding(HttpMethod.GET, item, "surveys.read",
                    listOf("surveyId"), listOf("surveyId")), ActionIntent.read, ActionRisk.readOnly, false, Confidence.high,
                    evidence = listOf(Evidence(evidence, "synthetic"))),
                ActionSpec("responses.create", "Respond", "responses",
                    ApiBinding(HttpMethod.POST, "$item/responses", "responses.create", listOf("surveyId"), listOf("surveyId"),
                        bodyFieldNames = listOf("answers", "shareHash"), bodySchema = body),
                    ActionIntent.create, ActionRisk.mutating, true, Confidence.high,
                    evidence = listOf(Evidence(evidence, "synthetic"))),
            ))
        return nativeQuestionnairePlan(schema, surveys, record)
    }

    private fun survey(
        canSubmit: String = "true",
        permissions: String = """["submit"]""",
        questions: String = QUESTIONS,
        extra: Map<String, String> = emptyMap(),
    ) = NativeRecord("7", mapOf("id" to "7", "title" to "Synthetic survey", "canSubmit" to canSubmit,
        "permissions" to permissions, "questions" to questions) + extra)

    private companion object {
        val QUESTIONS = """[
            {"id":2,"surveyId":7,"order":2,"type":"multiple","isRequired":false,"text":"Pick",
             "options":[{"id":11,"text":"A"},{"id":12,"text":"B"}]},
            {"id":1,"surveyId":7,"order":1,"type":"short","isRequired":true,"text":"Name"},
            {"id":3,"surveyId":7,"order":3,"type":"linearscale","isRequired":false,"text":"Rate",
             "extraSettings":{"optionsLowest":1,"optionsHighest":5}},
            {"id":4,"surveyId":7,"order":4,"type":"date","isRequired":false,"text":"When"},
            {"id":5,"surveyId":7,"order":5,"type":"dropdown","isRequired":false,"text":"Size",
             "options":[{"id":21,"text":"S"}]},
            {"id":6,"surveyId":7,"order":6,"type":"file","isRequired":false,"text":"Attachment"},
            {"id":7,"surveyId":7,"order":7,"type":"datetime","isRequired":false,"text":"Slot"}
        ]""".trimIndent()
    }
}
