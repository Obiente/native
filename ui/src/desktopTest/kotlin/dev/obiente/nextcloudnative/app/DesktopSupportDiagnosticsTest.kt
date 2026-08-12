package dev.obiente.nextcloudnative.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopSupportDiagnosticsTest {
    @Test
    fun invalidDestinationIsReturnedAsAnExportFailure() = runBlocking {
        val root = createTempDirectory("desktop-support-export").toFile()
        val diagnostics = AsyncJvmSupportDiagnostics(
            root = File(root, "diagnostics"),
            environment = environment(),
            workerName = "desktop-support-export-test",
        )
        try {
            val tooLong = File(root, "x".repeat(181))
            val result = DesktopSupportBundleExporter(
                diagnostics = diagnostics,
                chooseDestination = { tooLong },
            ).export("Steps", emptyList())

            val failed = assertIs<SupportDiagnosticsExportResult.Failed>(result)
            assertTrue("too long" in failed.message)
            assertTrue(root.listFiles().orEmpty().none { it.extension == "zip" })
        } finally {
            diagnostics.close()
        }
    }

    private fun environment() = SupportDiagnosticsEnvironment(
        appVersion = "nightly-test",
        packageVersion = "1.0.0",
        platform = "Linux",
        operatingSystemVersion = "test",
        architecture = "amd64",
    )
}
