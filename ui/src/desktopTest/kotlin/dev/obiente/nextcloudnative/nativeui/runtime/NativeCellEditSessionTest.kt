package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeCellEditSessionTest {
    @Test
    fun invalidDraftIsRejectedLocallyWithoutSubmitting() = runBlocking {
        val session = NativeCellEditSession()
        var submissions = 0
        session.begin(plan(originalValue = "12"))
        session.updateDraft("twelve")

        val result = session.save(NativeActionExecutor { submissions += 1; NativeActionExecutionResult.Success() })

        assertNull(result)
        assertEquals(0, submissions)
        assertEquals("Enter a whole number.", session.error)
        assertEquals("12", session.activePlan?.originalValue)
    }

    @Test
    fun acceptedEditSubmitsTrimmedValueAndKeepsItForTheCell() = runBlocking {
        val session = NativeCellEditSession()
        val plan = plan(originalValue = "12")
        var submitted: NativeActionRequest? = null
        session.begin(plan)
        session.updateDraft(" 15 ")

        val result = session.save(NativeActionExecutor { request ->
            submitted = request
            NativeActionExecutionResult.Success()
        })

        assertSame(plan.action, result)
        assertEquals("15", (submitted as NativeActionRequest.Submit).values["value"])
        assertEquals("15", session.savedValue("row-1", "amount"))
        assertNull(session.activePlan)
        assertFalse(session.saving)

        session.begin(plan)
        assertEquals("15", session.draft)
        assertEquals("15", session.activePlan?.originalValue)
    }

    @Test
    fun rejectedEditKeepsTheDialogOpenWithTheServerMessage() = runBlocking {
        val session = NativeCellEditSession()
        session.begin(plan(originalValue = "12"))
        session.updateDraft("13")

        val result = session.save(NativeActionExecutor { NativeActionExecutionResult.Failure("The row changed on the server.") })

        assertNull(result)
        assertEquals("The row changed on the server.", session.error)
        assertEquals("12", session.activePlan?.originalValue)
        assertNull(session.savedValue("row-1", "amount"))
        assertFalse(session.saving)
    }

    @Test
    fun dismissIsIgnoredAndSecondSaveIsRefusedWhileSubmitting() = runBlocking {
        val session = NativeCellEditSession()
        val release = CompletableDeferred<NativeActionExecutionResult>()
        var submissions = 0
        session.begin(plan(originalValue = "12"))
        session.updateDraft("13")

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            session.save(NativeActionExecutor { submissions += 1; release.await() })
        }
        assertTrue(session.saving)
        session.dismiss()
        assertEquals("12", session.activePlan?.originalValue)
        assertNull(session.save(NativeActionExecutor { submissions += 1; NativeActionExecutionResult.Success() }))

        release.complete(NativeActionExecutionResult.Success())
        first.await()
        assertEquals(1, submissions)
        assertEquals("13", session.savedValue("row-1", "amount"))
    }

    private fun plan(originalValue: String) = NativeCellEditPlan(
        action = ActionSpec(
            id = "update-cell",
            label = "Update cell",
            resourceId = "rows",
            binding = ApiBinding(method = HttpMethod.PUT, path = "/rows/{id}", operationId = "update-cell"),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        ),
        field = FieldSpec(id = "amount", label = "Amount", kind = FieldKind.integer, required = true, readOnly = false),
        recordId = "row-1",
        originalValue = originalValue,
        valueFieldName = "value",
        preservedValues = mapOf("id" to "row-1"),
    )
}
