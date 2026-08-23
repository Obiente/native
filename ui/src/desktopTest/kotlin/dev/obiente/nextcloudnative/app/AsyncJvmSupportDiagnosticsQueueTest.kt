package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AsyncJvmSupportDiagnosticsQueueTest {
    @Test
    fun `dropped queued records mark the submitted history as truncated`() = runBlocking {
        val initializationGate = CountDownLatch(1)
        val diagnostics = AsyncJvmSupportDiagnostics(
            root = createTempDirectory("support-diagnostics-queue-truncation").toFile(),
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
    }
}
