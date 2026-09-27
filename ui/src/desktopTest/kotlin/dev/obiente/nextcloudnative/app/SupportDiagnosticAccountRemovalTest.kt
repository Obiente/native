package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SupportDiagnosticAccountRemovalTest {
    private val environment = SupportDiagnosticsEnvironment("test", "test", "Desktop", "test", "amd64")
    private fun event() = SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Warning,
        component = SupportDiagnosticComponent.App,
        operation = "app.synthetic-account-event",
        outcome = "observed",
    )

    @Test
    fun capturingQueuedEventGenerationDoesNotWaitForHistoryPersistence() {
        val root = createTempDirectory("diagnostic-generation-capture").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val diagnostics = JvmSupportDiagnostics(root, environment)
            val writer = workers.submit {
                diagnostics.applyBatch { entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val capture = workers.submit<Long> { diagnostics.accountGeneration("account-a") }
            assertEquals(0L, capture.get(5, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally { release.countDown(); workers.shutdownNow(); root.deleteRecursively() }
    }

    @Test
    fun removalPreservesOtherAccountsAndPersistsAcrossRestart() {
        val root = createTempDirectory("diagnostic-account-removal").toFile()
        try {
            val diagnostics = JvmSupportDiagnostics(root, environment)
            diagnostics.record(event())
            diagnostics.recordForAccountIdentity("account-a", event())
            diagnostics.recordForAccountIdentity("account-b", event())
            diagnostics.removeAccount("account-a")
            diagnostics.setActiveAccountIdentity("account-a")
            assertEquals(1, diagnostics.summary().eventCount)
            diagnostics.setActiveAccountIdentity("account-b")
            assertEquals(2, diagnostics.summary().eventCount)
            val restarted = JvmSupportDiagnostics(root, environment)
            restarted.setActiveAccountIdentity("account-a")
            assertEquals(1, restarted.summary().eventCount)
            restarted.setActiveAccountIdentity("account-b")
            assertEquals(2, restarted.summary().eventCount)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun staleDiagnosticPublicationCannotCrossRetirementAndReactivation() {
        val root = createTempDirectory("diagnostic-account-generation").toFile()
        try {
            val diagnostics = JvmSupportDiagnostics(root, environment)
            val old = diagnostics.accountGeneration("account-a")
            diagnostics.removeAccount("account-a")
            diagnostics.recordForAccountIdentity("account-a", event(), old)
            diagnostics.setActiveAccountIdentity("account-a")
            diagnostics.recordForAccountIdentity("account-a", event(), old)
            assertEquals(0, diagnostics.summary().eventCount)
            diagnostics.recordForAccountIdentity("account-a", event(), diagnostics.accountGeneration("account-a"))
            assertEquals(1, diagnostics.summary().eventCount)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedHistoryRewriteRemainsRetryable() {
        val parent = createTempDirectory("diagnostic-account-retry").toFile()
        val root = File(parent, "history")
        val retained = File(parent, "retained")
        try {
            val diagnostics = JvmSupportDiagnostics(root, environment)
            diagnostics.recordForAccountIdentity("account-a", event())
            Files.move(root.toPath(), retained.toPath())
            root.writeText("synthetic unavailable directory")
            assertFailsWith<IllegalArgumentException> { diagnostics.removeAccount("account-a") }
            assertTrue(root.delete())
            Files.move(retained.toPath(), root.toPath())
            diagnostics.removeAccount("account-a")
            val restarted = JvmSupportDiagnostics(root, environment)
            restarted.setActiveAccountIdentity("account-a")
            assertEquals(0, restarted.summary().eventCount)
        } finally { parent.deleteRecursively() }
    }

    @Test
    fun asyncRemovalFlushesQueuedEventsAndRejectsPostRemovalRecords() = runBlocking {
        val root = createTempDirectory("diagnostic-account-async").toFile()
        try {
            AsyncJvmSupportDiagnostics(root, environment, "synthetic-diagnostic-removal").use { diagnostics ->
                val baseline = diagnostics.loadSummary().eventCount
                diagnostics.setActiveAccountIdentity("account-a")
                repeat(40) { diagnostics.record(event()) }
                diagnostics.removeAccount("account-a")
                diagnostics.recordForAccountIdentity("account-a", event())
                assertEquals(baseline, diagnostics.loadSummary().eventCount)
                diagnostics.setActiveAccountIdentity("account-a")
                diagnostics.loadSummary()
                diagnostics.recordForAccountIdentity("account-a", event())
                assertEquals(baseline + 1, diagnostics.loadSummary().eventCount)
            }
        } finally { root.deleteRecursively() }
    }
}
