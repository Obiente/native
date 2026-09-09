package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSyncActionDiagnosticsTest {
    @Test
    fun `stopped sync actions remain informational and distinct`() {
        val diagnostic = FileSyncCenterActionResult.Stopped("Sync paused.").toFileSyncActionDiagnosticSummary()

        assertEquals(SupportDiagnosticSeverity.Info, diagnostic.severity)
        assertEquals("stopped", diagnostic.outcome)
        assertEquals("Sync paused.", diagnostic.message)
    }
}
