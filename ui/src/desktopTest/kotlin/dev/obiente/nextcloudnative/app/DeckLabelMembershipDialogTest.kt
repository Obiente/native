package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DeckLabelMembershipDialogTest {
    private fun board() = parseDeckBoard(DynamicRuleExecutionFixture.response(200,
        """{"id":7,"title":"Synthetic board","permissions":{"edit":true,"manage":true},"labels":[{"id":3,"title":"Kept"},{"id":5,"title":"New"},{"id":8,"title":"Later"}]}"""))

    private fun controller(f: MembershipExecutionFixture): SemanticBoardMembershipController =
        SemanticBoardMembershipController(f.transport.services, f.transport.session,
            SemanticBoardRoutes(assertNotNull(resolveSemanticBoardContract(f.descriptor, true))),
            DynamicContractWriteGate().also { it.settle(f.descriptor) }) {
            f.transport.services.executeNextcloudApi(f.transport.session, it)
        }

    @Test fun integratedStatusChecksKeepThePickerAndItsUnsavedSelection() {
        for (width in listOf(420, 1200)) MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            var card by mutableStateOf(f.card())
            var busy by mutableStateOf(false)
            var closed by mutableStateOf(false)
            var result: NativeActionExecutionResult? = null
            nativeSceneTest(width, 800, content = {
                if (!closed) SemanticBoardLabelDialog(controller, board(), card, busy, null,
                    onBusyChanged = { busy = it }, onResult = { result = it }, onCardChanged = { card = it },
                    onSaved = { closed = true }, onDismiss = { closed = true },
                    onLegacySubmit = { fail("Managed controls must not fall back") }, onManageLabels = null)
            }) {
                click("New")
                click("Check earlier changes")
                settleUntil(10_000) { result != null && !busy }
                assertIs<NativeActionExecutionResult.Success>(result)
                assertFalse(closed)
                assertEquals(0, f.transport.writes)
                click("Save labels")
                settleUntil(10_000) { closed }
                assertTrue(closed)
                assertEquals(1, f.transport.writes, "The unsaved New selection survived the status refresh.")
            }
        }
    }

    @Test fun integratedNoOpDoesNotCloseThePickerWhileEarlierDeliveryIsUnknown() {
        MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            var card by mutableStateOf(f.card())
            var busy by mutableStateOf(false)
            var closed by mutableStateOf(false)
            var result: NativeActionExecutionResult? = null
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            nativeSceneTest(420, 800, content = {
                if (!closed) SemanticBoardLabelDialog(controller, board(), card, busy, null,
                    onBusyChanged = { busy = it }, onResult = { result = it }, onCardChanged = { card = it },
                    onSaved = { closed = true }, onDismiss = { closed = true },
                    onLegacySubmit = { fail("Managed controls must not fall back") }, onManageLabels = null)
            }) {
                click("New"); click("Save labels")
                settleUntil(10_000) { result != null && !busy }
                assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(result).outcome)
                val pending = runBlocking { assertNotNull(f.transport.entries().single().pendingVerification) }
                result = null
                click("New"); click("Save labels")
                settleUntil(10_000) { result != null && !busy }
                assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(result).outcome)
                assertFalse(closed)
                assertEquals(1, f.transport.writes)
                runBlocking { assertEquals(pending, f.transport.entries().single().pendingVerification) }
            }
        }
    }

    @Test fun integratedPartialSuccessRefreshesBaselineWhileKeepingTheDesiredSelection() {
        for (restore in listOf(false, true)) MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            var card by mutableStateOf(f.card())
            var busy by mutableStateOf(false)
            var closed by mutableStateOf(false)
            var result: NativeActionExecutionResult? = null
            f.transport.onSend = {
                if (f.transport.writes == 2) { f.transport.writeStatus = 403; f.transport.afterWrite = null }
            }
            nativeSceneTest(720, 900, content = {
                if (!closed) SemanticBoardLabelDialog(controller, board(), card, busy, null,
                    onBusyChanged = { busy = it }, onResult = { result = it }, onCardChanged = { card = it },
                    onSaved = { closed = true }, onDismiss = { closed = true },
                    onLegacySubmit = { fail("Managed controls must not fall back") }, onManageLabels = null)
            }) {
                click("New"); click("Later"); click("Save labels")
                settleUntil(10_000) { result != null && !busy }
                assertIs<NativeActionExecutionResult.Failure>(result)
                assertFalse(closed)
                assertEquals(setOf(3L, 5L), card.labels.map { it.id }.toSet())
                assertEquals(2, f.transport.writes)
                f.transport.onSend = {}
                f.transport.writeStatus = 200
                if (restore) { click("New"); click("Later") }
                f.transport.afterWrite = if (restore) MembershipExecutionFixture.body() else MembershipExecutionFixture.body(
                    """[{"id":3,"title":"Kept"},{"id":5,"title":"New"},{"id":8,"title":"Later"}]""")
                click("Save labels")
                settleUntil(10_000) { closed }
                assertTrue(closed)
                assertEquals(3, f.transport.writes)
                val last = f.transport.requests.last { it.method != NextcloudApiMethod.GET }
                assertTrue(last.relativePath.endsWith(if (restore) "/removeLabel" else "/assignLabel"))
                assertEquals(if (restore) setOf(3L) else setOf(3L, 5L, 8L), card.labels.map { it.id }.toSet())
            }
        }
    }

    @Test fun recoveryControlsOnlyRunTheirExplicitActionAndPreserveSelection() {
        for (width in listOf(420, 720, 1200)) checkRecoveryControls(width)
    }

    private fun checkRecoveryControls(width: Int) {
        var checked = 0
        var repeated = 0
        var submitted: List<DeckLabel>? = null
        val card = parseDeckCard(7, 10, DynamicRuleExecutionFixture.response(200, MembershipExecutionFixture.body()))
        nativeSceneTest(width, 800, content = {
            DeckUiLabelPickerDialog(card, listOf(DeckLabel(3, "Kept", null), DeckLabel(5, "New", null)),
                false, "An earlier change still needs verification.", {}, { submitted = it },
                onCheckStatus = { checked++ }, onRepeat = { repeated++ })
        }) {
            click("New")
            click("Check earlier changes")
            assertEquals(1, checked)
            assertEquals(0, repeated)
            assertNull(submitted)
            click("Repeat same change")
            assertEquals(1, repeated)
            assertNull(submitted)
            click("Save labels")
            assertEquals(setOf(3L, 5L), submitted?.map { it.id }?.toSet())
        }
    }

    @Test fun legacyPickerHasNoRecoveryControls() {
        val card = parseDeckCard(7, 10, DynamicRuleExecutionFixture.response(200, MembershipExecutionFixture.body()))
        nativeSceneTest(420, 800, content = {
            DeckUiLabelPickerDialog(card, card.labels, false, null, {}, {})
        }) {
            assertTrue(has("Save labels"))
            assertFalse(has("Check earlier changes"))
            assertFalse(has("Repeat same change"))
        }
    }
}
