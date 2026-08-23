package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AsyncJvmSupportDiagnosticsQueueTest {
    @Test
    fun `clear resets queued and restarted truncation provenance`() = runBlocking {
        val root = createTempDirectory("support-diagnostics-queue-truncation").toFile()
        val initializationGate = CountDownLatch(1)
        val diagnostics = AsyncJvmSupportDiagnostics(
            root = root,
            environment = SupportDiagnosticsEnvironment("test", "test", "Linux", "test", "amd64"),
            workerName = "support-diagnostics-queue-truncation-test",
            initializationGate = initializationGate,
        )
        try {
            val event = SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Warning,
                component = SupportDiagnosticComponent.App,
                operation = "app.queue-pressure",
                outcome = "observed",
            )
            repeat(513) { diagnostics.record(event) }
            initializationGate.countDown()

            val context = diagnostics.prepareSubmissionContext("Queue pressure", emptyList())

            assertEquals(
                SupportDiagnosticHistoryTruncationStatus.Observed,
                context.eventHistory?.capacityTruncationStatus,
            )
        } finally {
            initializationGate.countDown()
            diagnostics.close()
        }

        val restarted = AsyncJvmSupportDiagnostics(root, diagnosticsEnvironment(), "support-diagnostics-restart-test")
        try {
            assertEquals(
                SupportDiagnosticHistoryTruncationStatus.UnknownAfterRestart,
                restarted.prepareSubmissionContext("After restart", emptyList())
                    .eventHistory?.capacityTruncationStatus,
            )
            assertTrue(restarted.clear())
            restarted.record(diagnosticEvent())
            assertEquals(
                SupportDiagnosticHistoryTruncationStatus.NotObserved,
                restarted.prepareSubmissionContext("After clear", emptyList())
                    .eventHistory?.capacityTruncationStatus,
            )
        } finally {
            restarted.close()
        }
    }

    private fun diagnosticsEnvironment() =
        SupportDiagnosticsEnvironment("test", "test", "Linux", "test", "amd64")

    private fun diagnosticEvent() = SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Warning,
        component = SupportDiagnosticComponent.App,
        operation = "app.queue-pressure",
        outcome = "observed",
    )
}
