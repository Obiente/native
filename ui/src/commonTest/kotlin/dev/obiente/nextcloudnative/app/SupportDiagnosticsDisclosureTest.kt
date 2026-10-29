package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportDiagnosticsDisclosureTest {
    @Test
    fun `retention disclosure keeps reports until the reporter or Obiente Support deletes them`() {
        assertEquals(
            "Your report and diagnostic attachment are kept until you delete them or Obiente Support deletes " +
                "them after your request is resolved.",
            SUPPORT_REPORT_RETENTION_DISCLOSURE,
        )
    }

    @Test
    fun `short retention disclosure keeps the same meaning for each submitted request`() {
        assertEquals("Kept until you or Obiente Support delete it.", SUPPORT_REPORT_RETENTION_SHORT_DISCLOSURE)
    }

    @Test
    fun `send confirmation ends with the retention disclosure`() {
        assertTrue(SUPPORT_SEND_DIAGNOSTICS_DISCLOSURE.endsWith(" " + SUPPORT_REPORT_RETENTION_DISCLOSURE))
    }

    @Test
    fun `support disclosures do not promise a fixed automatic deletion period`() {
        listOf(
            SUPPORT_SEND_DIAGNOSTICS_DISCLOSURE,
            SUPPORT_REPORT_RETENTION_DISCLOSURE,
            SUPPORT_REPORT_RETENTION_SHORT_DISCLOSURE,
        ).forEach { disclosure ->
            assertFalse(Regex("""\b\d+\s*-?\s*days?\b""", RegexOption.IGNORE_CASE).containsMatchIn(disclosure))
            assertFalse(disclosure.contains("Retention is", ignoreCase = true))
            assertFalse(disclosure.contains("automatically", ignoreCase = true))
        }
    }
}
