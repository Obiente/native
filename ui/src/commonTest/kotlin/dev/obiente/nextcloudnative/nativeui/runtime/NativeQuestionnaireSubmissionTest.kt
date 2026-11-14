package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun aNumericStateIsClosedOnlyWhenTheContractLabelsThatValueClosed() {
        val labelled = surveys.copy(fields = listOf(FieldSpec("state", "State", FieldKind.integer, false, true,
            enumValues = listOf("0", "1", "2"), enumLabels = mapOf("0" to "Active", "1" to "Closed", "2" to "Archived"))))
        assertEquals("This form is closed.", plan(survey(extra = mapOf("state" to "1")), resource = labelled)?.unavailableReason)
        assertEquals("This form is closed.", plan(survey(extra = mapOf("state" to "2")), resource = labelled)?.unavailableReason)
        assertNull(plan(survey(extra = mapOf("state" to "0")), resource = labelled)?.unavailableReason)
        val unlabelled = surveys.copy(fields = listOf(FieldSpec("state", "State", FieldKind.integer, false, true)))
        assertNull(plan(survey(extra = mapOf("state" to "1")), resource = unlabelled)?.unavailableReason,
            "Without contract labels no number is assumed to mean closed; the server still decides.")
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

    @Test
    fun anUnconfirmedResponseIsRecordedBeforeSendingAndNeedsADecisionEvenAfterARestart() = runBlocking<Unit> {
        val plan = assertNotNull(plan())
        val store = MemoryAttempts()
        var calls = 0
        var recordedWhenSent: NativeSendAttempt? = null
        val lost = NativeActionExecutor {
            calls++
            recordedWhenSent = store.value
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        val draft = NativeQuestionnaireDraft(store, clock = { 1L }).apply { restore(); start(); answer("1", "Synthetic name") }
        draft.send(plan, lost) {}
        assertEquals(NativeSendAttemptState.Prepared, recordedWhenSent?.state, "The attempt is on record before the request leaves.")
        assertEquals(NativeSendAttemptState.MaybeSent, store.value?.state)
        assertNotNull(draft.unconfirmed)

        // A new screen, or the app after a restart, finds the same record.
        val later = NativeQuestionnaireDraft(store, clock = { 2L })
        assertFalse(later.canStart, "Nothing is offered until the stored record has been read.")
        later.restore()
        assertNotNull(later.unconfirmed)
        later.start()
        assertFalse(later.open, "Another response needs an explicit decision.")
        later.answer("1", "Synthetic name")
        later.send(plan, lost) {}
        assertEquals(1, calls)

        later.start(confirmedResend = true)
        assertTrue(later.open)
        later.send(plan, { calls++; NativeActionExecutionResult.Failure("Closed.", NativeActionFailureOutcome.Rejected) }) {}
        assertEquals(2, calls)
        assertNotNull(later.unconfirmed, "A refused second response says nothing about the first.")
        assertNotNull(store.value)

        later.dismissUnconfirmed()
        assertNull(store.value)
        later.start()
        later.send(plan, { calls++; NativeActionExecutionResult.Success() }) {}
        assertTrue(later.sent)
        assertNull(store.value)
    }

    @Test
    fun aResponseIsNotSentWhenItsAttemptCannotBeRecordedOrAnEarlierRecordCannotBeRead() = runBlocking<Unit> {
        val plan = assertNotNull(plan())
        var calls = 0
        val executor = NativeActionExecutor { calls++; NativeActionExecutionResult.Success() }
        val unwritable = NativeQuestionnaireDraft(MemoryAttempts(failSave = true)).apply { restore(); start(); answer("1", "Synthetic name") }
        unwritable.send(plan, executor) {}
        assertNotNull(unwritable.storageProblem)
        val unreadable = NativeQuestionnaireDraft(MemoryAttempts(failLoad = true)).apply { restore(); start(); answer("1", "Synthetic name") }
        assertFalse(unreadable.open)
        unreadable.send(plan, executor) {}
        assertEquals(0, calls)
    }

    private class MemoryAttempts(private val failSave: Boolean = false, private val failLoad: Boolean = false) : NativeSendAttemptStore {
        var value: NativeSendAttempt? = null

        override suspend fun load(): NativeSendAttempt? {
            check(!failLoad) { "Synthetic storage is unreadable." }
            return value
        }

        override suspend fun save(attempt: NativeSendAttempt) {
            check(!failSave) { "Synthetic storage is full." }
            value = attempt
        }

        override suspend fun clear() {
            value = null
        }
    }

    private fun plan(
        record: NativeRecord = survey(),
        evidence: EvidenceSource = EvidenceSource.verifiedAppPackage,
        answersSchema: String = """{"type":"object","additionalProperties":{"type":"array","items":{"type":"string"}}}""",
        app: AppIdentity = AppIdentity("surveyor", "Surveyor", "3.0.0"),
        resource: ResourceSpec = surveys,
    ): NativeQuestionnairePlan? {
        val item = "/apps/surveyor/api/surveys/{surveyId}"
        val body = Json.parseToJsonElement("""{"type":"object","properties":{"answers":$answersSchema,"shareHash":{"type":"string"}}}""")
        val schema = NativeAppSchema("0.1", app, Confidence.high,
            resources = listOf(resource),
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
        return nativeQuestionnairePlan(schema, resource, record)
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
