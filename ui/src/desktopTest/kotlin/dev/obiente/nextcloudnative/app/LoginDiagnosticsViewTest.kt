package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginDiagnosticsViewTest {
    @Test
    fun `recovery actions remain reachable in short windows with large text`() {
        for (fontScale in listOf(1f, 2f)) {
            val invoked = mutableListOf<String>()
            nativeSceneTest(640, 240, fontScale = fontScale, content = {
                SessionRecoveryContent(
                    NextcloudSessionLoadState.LegacyMigrationUnavailable,
                    onRetry = { invoked += "retry" },
                    onSignInAgain = { invoked += "sign-in" },
                    onDiagnostics = { invoked += "diagnostics" },
                )
            }) {
                for (label in listOf("Try again", "Sign in again", "Export login diagnostics")) {
                    var attempts = 0
                    while (assertNotNull(node(label)).boundsInRoot.let { it.height == 0f || it.bottom > 240f } && attempts++ < 30) {
                        val scroll = assertNotNull(nodes().firstNotNullOfOrNull {
                            it.config.getOrNull(SemanticsActions.ScrollBy)?.action
                        })
                        assertTrue(scroll.invoke(0f, 60f))
                        settle()
                    }
                    val bounds = assertNotNull(node(label)).boundsInRoot
                    assertTrue(bounds.height > 0f && bounds.top >= 0f && bounds.bottom <= 240f, "Unreachable: $label")
                    click(label)
                }
                assertEquals(listOf("retry", "sign-in", "diagnostics"), invoked)
                capture("login-recovery-$fontScale")
            }
        }
    }

    @Test
    fun `closing diagnostics cancels the screen owned export`() {
        val visible = mutableStateOf(true)
        val drafts = SupportSettingsDraftState()
        var cancelled = false
        val load: suspend () -> SupportDiagnosticsSummary = { summary() }
        val export: suspend (String) -> SupportDiagnosticsExportResult = {
            try { awaitCancellation() } finally { cancelled = true }
        }
        nativeSceneTest(900, 900, content = {
            if (visible.value) Dialog { LoginDiagnosticsView(drafts, load, export, flowOf(0L)) }
        }) {
            click("Save login diagnostics")
            visible.value = false
            settle()
            assertTrue(cancelled)
        }
    }

    @Test
    fun `logged out diagnostics export is the primary action at phone and desktop sizes`() {
        for (width in listOf(400, 900)) {
            val drafts = SupportSettingsDraftState().apply { updateReportDraft("Browser approval succeeded but sign-in failed.") }
            val exports = mutableListOf<String>()
            val destination = if (width == 400) "Android share sheet" else "synthetic-report.zip"
            val load: suspend () -> SupportDiagnosticsSummary = { summary() }
            val export: suspend (String) -> SupportDiagnosticsExportResult = {
                exports += it
                SupportDiagnosticsExportResult.Exported(destination)
            }
            nativeSceneTest(width, 900, content = { Dialog { LoginDiagnosticsView(drafts, load, export, flowOf(0L)) } }) {
                assertTrue(has("Save login diagnostics"))
                assertFalse(has("Sign in before sending a private report."))
                assertFalse(has("Review and send"))
                capture("login-diagnostics-$width")
                click("Save login diagnostics")
                assertEquals(listOf(drafts.reportDraft), exports)
                assertTrue(has("Report prepared: $destination"))
                assertFalse(has("Diagnostics saved: $destination"))
            }
        }
    }

    @Test
    fun `export is single flight and cancellation permits another local attempt`() {
        val pending = CompletableDeferred<SupportDiagnosticsExportResult>()
        val drafts = SupportSettingsDraftState()
        var exports = 0
        val load: suspend () -> SupportDiagnosticsSummary = { summary() }
        val export: suspend (String) -> SupportDiagnosticsExportResult = { exports += 1; pending.await() }
        nativeSceneTest(900, 900, content = { Dialog { LoginDiagnosticsView(drafts, load, export, flowOf(0L)) } }) {
            click("Save login diagnostics")
            val busy = assertNotNull(node("Preparing diagnostics..."))
            assertTrue(busy.config.contains(SemanticsProperties.Disabled))
            click("Preparing diagnostics...")
            assertEquals(1, exports)
            pending.complete(SupportDiagnosticsExportResult.Cancelled)
            settle()
            assertTrue(has("Export cancelled."))
            click("Save login diagnostics")
            assertEquals(2, exports)
        }
    }

    @Test
    fun `summary failure exposes a retry and does not reveal the exception`() {
        var unavailable = true
        val load: suspend () -> SupportDiagnosticsSummary = {
            if (unavailable) error("private synthetic failure context")
            summary()
        }
        val drafts = SupportSettingsDraftState()
        val export: suspend (String) -> SupportDiagnosticsExportResult = { SupportDiagnosticsExportResult.Cancelled }
        nativeSceneTest(900, 900, content = { Dialog { LoginDiagnosticsView(drafts, load, export, flowOf(0L)) } }) {
            assertTrue(has("Diagnostics could not be loaded. Try again."))
            assertFalse(has("private synthetic failure context"))
            unavailable = false
            click("Try again")
            assertFalse(assertNotNull(node("Save login diagnostics")).config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test
    fun `export failure is visible and keeps the draft available for retry`() {
        val drafts = SupportSettingsDraftState().apply { updateReportDraft("Synthetic login failure") }
        val load: suspend () -> SupportDiagnosticsSummary = { summary() }
        val export: suspend (String) -> SupportDiagnosticsExportResult = { error("private failure detail") }
        nativeSceneTest(900, 900, content = { Dialog { LoginDiagnosticsView(drafts, load, export, flowOf(0L)) } }) {
            click("Save login diagnostics")
            assertTrue(has("The diagnostics report could not be saved. Try again."))
            assertFalse(has("private failure detail"))
            assertEquals("Synthetic login failure", drafts.reportDraft)
        }
    }

    @Composable
    private fun Dialog(content: @Composable () -> Unit) {
        AlertDialog(
            onDismissRequest = {}, title = { Text("Login diagnostics") },
            text = { Box(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) { content() } },
            confirmButton = { TextButton(onClick = {}) { Text("Close") } },
        )
    }

    private fun summary() = SupportDiagnosticsSummary(
        available = true, eventCount = 2, warningCount = 0, errorCount = 1,
        oldestEventAtEpochMillis = null, newestEventAtEpochMillis = null,
        components = setOf(SupportDiagnosticComponent.Authentication), storedBytes = 120,
        includedFiles = SUPPORT_BUNDLE_INCLUDED_FILES,
    )
}
