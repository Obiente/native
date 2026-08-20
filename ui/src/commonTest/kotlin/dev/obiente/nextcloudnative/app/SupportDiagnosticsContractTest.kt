package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SupportDiagnosticsContractTest {
    @Test
    fun `event rejects a code outside the stable token contract`() {
        assertFailsWith<IllegalArgumentException> {
            SupportDiagnosticEvent(
                sequence = 1L,
                occurredAtEpochMillis = 1L,
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.Network,
                operation = "http.request",
                outcome = "failed",
                code = "not a stable code",
            )
        }
    }

    @Test
    fun `frame rejects control characters before report serialization`() {
        assertFailsWith<IllegalArgumentException> {
            SupportDiagnosticFrame(
                declaringClass = "example.Client\nprivate-value",
                methodName = "request",
                fileName = "Client.kt",
                lineNumber = 12,
            )
        }
    }

    @Test
    fun `environment rejects unbounded system text`() {
        assertFailsWith<IllegalArgumentException> {
            SupportDiagnosticsEnvironment(
                appVersion = "nightly",
                packageVersion = "1.0.0",
                platform = "desktop",
                operatingSystemVersion = "x".repeat(161),
                architecture = "amd64",
            )
        }
    }
}
