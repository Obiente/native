package dev.obiente.nextcloudnative.app

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class JvmSupportDiagnosticsTest {
    @Test
    fun asyncCloseFlushesEveryQueuedDrainBatchBeforeShutdown() {
        val root = createTempDirectory("support-diagnostics-close-flush").toFile()
        val diagnostics = AsyncJvmSupportDiagnostics(
            root = root,
            environment = environment(),
            workerName = "support-diagnostics-close-flush-test",
        )

        repeat(96) { index ->
            diagnostics.record(failureEvent("/srv/fixtures/private-$index.jpg"))
        }
        diagnostics.close()

        assertEquals(97, File(root, "events-v1.jsonl").readLines().size)
    }

    @Test
    fun persistsOnlySanitizedEventsAndLoadsThemAfterRestart() {
        val root = createTempDirectory("support-diagnostics").toFile()
        val first = diagnostics(root)
        first.registerPrivateValue("person@example.test")
        first.record(failureEvent("D:\\Fixtures\\Person\\Photos\\private.jpg"))

        val persisted = File(root, "events-v1.jsonl").readText()
        assertFalse("Person" in persisted)
        assertFalse("private.jpg" in persisted)
        assertFalse("person@example.test" in persisted)
        assertTrue("<local-path:" in persisted)

        val restored = diagnostics(root)
        assertEquals(1, restored.summary().eventCount)
        assertEquals(1, restored.summary().errorCount)
    }

    @Test
    fun malformedHistoryIsDroppedWithoutBlockingNewDiagnostics() {
        val root = createTempDirectory("support-diagnostics-corrupt").toFile()
        val original = diagnostics(root)
        original.record(failureEvent("/srv/fixtures/private.jpg"))
        File(root, "events-v1.jsonl").writeText("not-json\n")

        val recovered = diagnostics(root)
        assertEquals(0, recovered.summary().eventCount)
        recovered.record(failureEvent("/srv/fixtures/second.jpg"))
        assertEquals(1, recovered.summary().eventCount)
    }

    @Test
    fun startupRemovesOnlyOrphanedDiagnosticTemporaryFiles() {
        val root = createTempDirectory("support-diagnostics-orphaned-temporary-files").toFile()
        val historyTemporaryFile = File(root, ".events-v1.jsonl.interrupted.tmp")
        val keyTemporaryFile = File(root, ".redaction-key-v1.interrupted.tmp")
        val unrelatedTemporaryFile = File(root, ".unrelated.interrupted.tmp")
        historyTemporaryFile.writeText("incomplete history")
        keyTemporaryFile.writeText("incomplete key")
        unrelatedTemporaryFile.writeText("keep")

        diagnostics(root)

        assertFalse(historyTemporaryFile.exists())
        assertFalse(keyTemporaryFile.exists())
        assertTrue(unrelatedTemporaryFile.isFile)
        assertEquals("keep", unrelatedTemporaryFile.readText())
    }

    @Test
    fun tornFinalAppendIsDroppedWhileEarlierEventsSurviveRestart() {
        val root = createTempDirectory("support-diagnostics-torn-append").toFile()
        val original = diagnostics(root)
        original.record(failureEvent("/srv/fixtures/retained.jpg"))
        File(root, "events-v1.jsonl").appendText("{\"schemaVersion\":")

        val recovered = diagnostics(root)

        assertEquals(1, recovered.summary().eventCount)
        assertTrue(File(root, "events-v1.jsonl").readText().endsWith("\n"))
    }

    @Test
    fun oversizedHistoryIsDiscardedBeforeItCanBeLoadedOrExported() {
        val root = createTempDirectory("support-diagnostics-oversized").toFile()
        diagnostics(root)
        File(root, "events-v1.jsonl").writeText("x".repeat((MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES * 2L + 1L).toInt()))

        val recovered = diagnostics(root)

        assertEquals(0, recovered.summary().eventCount)
        assertEquals(0L, File(root, "events-v1.jsonl").length())
    }

    @Test
    fun eventsOlderThanTheRetentionWindowAreRemoved() {
        val root = createTempDirectory("support-diagnostics-age").toFile()
        var now = MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS
        val diagnostics = diagnostics(root) { now }
        diagnostics.record(failureEvent("/srv/fixtures/old.jpg"))
        now += MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS + 1L

        diagnostics.record(failureEvent("/srv/fixtures/current.jpg"))

        assertEquals(1, diagnostics.summary().eventCount)
        assertFalse("old.jpg" in File(root, "events-v1.jsonl").readText())
    }

    @Test
    fun summaryPersistsRetentionPruningWithoutANewEvent() {
        val root = createTempDirectory("support-diagnostics-summary-retention").toFile()
        var now = MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS
        val diagnostics = diagnostics(root) { now }
        diagnostics.record(failureEvent("/srv/fixtures/expired.jpg"))
        now += MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS + 1L

        assertEquals(0, diagnostics.summary().eventCount)

        assertEquals("", File(root, "events-v1.jsonl").readText())
        assertEquals(0, diagnostics(root) { now }.summary().eventCount)
    }

    @Test
    fun clockRollbackDoesNotHideExpiredEventsBehindAFutureEvent() {
        val root = createTempDirectory("support-diagnostics-clock-rollback").toFile()
        var now = MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS * 3L
        val diagnostics = diagnostics(root) { now }
        diagnostics.record(failureEvent("/srv/fixtures/future.jpg").copy(operation = "clock.future"))
        now = 0L
        diagnostics.record(failureEvent("/srv/fixtures/expired.jpg").copy(operation = "clock.expired"))
        now = MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS + 1L

        diagnostics.record(failureEvent("/srv/fixtures/current.jpg").copy(operation = "clock.current"))

        val operations = diagnostics.summary().recentEvents.map { it.operation }
        assertEquals(listOf("clock.future", "clock.current"), operations)
    }

    @Test
    fun accountScopedReportsExcludeOtherAccountsWhileRetainingGlobalEvents() {
        val root = createTempDirectory("support-diagnostics-account-scope").toFile()
        val diagnostics = diagnostics(root)
        diagnostics.record(failureEvent("/srv/fixtures/global.jpg").copy(operation = "app.global"))
        diagnostics.setActiveAccountIdentity("first-account")
        diagnostics.record(failureEvent("/srv/fixtures/first.jpg").copy(operation = "sync.first-account"))
        diagnostics.setActiveAccountIdentity("second-account")
        diagnostics.recordForAccountIdentity(
            "first-account",
            failureEvent("/srv/fixtures/abandoned.jpg").copy(operation = "sync.abandoned-first-account"),
        )
        diagnostics.record(failureEvent("/srv/fixtures/second.jpg").copy(operation = "sync.second-account"))

        val summary = diagnostics.summary()
        assertEquals(2, summary.eventCount)
        assertEquals(listOf("app.global", "sync.second-account"), summary.recentEvents.map { it.operation })

        val destination = File(root, "second-account.zip")
        diagnostics.writeBundle(destination, "", emptyList())
        ZipFile(destination).use { zip ->
            val events = zip.getInputStream(assertNotNull(zip.getEntry("events.jsonl")))
                .bufferedReader()
                .use { it.readText() }
            assertTrue("sync.second-account" in events)
            assertTrue("app.global" in events)
            assertFalse("sync.first-account" in events)
            assertFalse("sync.abandoned-first-account" in events)
        }
    }

    @Test
    fun storageFailurePublishesARevisionAndDisablesExportState() {
        val root = createTempDirectory("support-diagnostics-storage-failure").toFile()
        val diagnostics = diagnostics(root)
        diagnostics.record(failureEvent("/srv/fixtures/first.jpg"))
        val history = File(root, "events-v1.jsonl")
        assertTrue(history.delete())
        assertTrue(history.mkdir())
        val revisionBeforeFailure = diagnostics.revisions().value

        diagnostics.record(failureEvent("/srv/fixtures/second.jpg"))

        assertFalse(diagnostics.summary().available)
        assertTrue(diagnostics.revisions().value > revisionBeforeFailure)
    }

    @Test
    fun coldStartCrashMarkerIsRecoveredIntoTheNextReport() {
        val root = createTempDirectory("support-diagnostics-cold-crash").toFile()
        persistJvmSupportDiagnosticsColdCrashMarker(root)
        val diagnostics = AsyncJvmSupportDiagnostics(
            root = root,
            environment = environment(),
            workerName = "support-diagnostics-test",
        )
        val destination = File(root, "cold-crash-report.zip")

        runBlocking { diagnostics.writeBundle(destination, "", emptyList()) }
        diagnostics.close()

        ZipFile(destination).use { zip ->
            val events = zip.getInputStream(assertNotNull(zip.getEntry("events.jsonl")))
                .bufferedReader()
                .use { it.readText() }
            assertTrue("app.previous-cold-start-crash" in events)
        }
        assertFalse(File(root, SUPPORT_DIAGNOSTICS_COLD_CRASH_MARKER_FILE).exists())
    }

    @Test
    fun coldStartCrashKeepsAFallbackMarkerWhenHistoryPersistenceFails() {
        val root = createTempDirectory("support-diagnostics-cold-crash-failure").toFile()
        assertTrue(File(root, "events-v1.jsonl").mkdir())
        val diagnostics = AsyncJvmSupportDiagnostics(
            root = root,
            environment = environment(),
            workerName = "support-diagnostics-test",
        )

        diagnostics.recordBeforeProcessExit(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.App,
                operation = "app.uncaught-exception",
                outcome = "failed",
            ),
        )
        diagnostics.close()

        assertEquals("pending\n", File(root, "pending-cold-start-crash-v1").readText())
    }

    @Test
    fun previousColdStartCrashMarkerSurvivesFailedRecoveryPersistence() {
        val root = createTempDirectory("support-diagnostics-cold-crash-recovery-failure").toFile()
        val marker = File(root, "pending-cold-start-crash-v1")
        marker.writeText("pending\n")
        assertTrue(File(root, "events-v1.jsonl").mkdir())
        val diagnostics = AsyncJvmSupportDiagnostics(
            root = root,
            environment = environment(),
            workerName = "support-diagnostics-test",
        )

        runBlocking { diagnostics.loadSummary() }
        diagnostics.close()

        assertEquals("pending\n", marker.readText())
    }

    @Test
    fun oneEvictionDoesNotTriggerAFullHistoryRewrite() {
        assertFalse(
            shouldCompactSupportDiagnosticHistory(
                discardedBytes = 1L,
                physicalBytes = MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES,
                appendedBytes = 1L,
            ),
        )
        assertTrue(
            shouldCompactSupportDiagnosticHistory(
                discardedBytes = MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES,
                physicalBytes = 0L,
                appendedBytes = 1L,
            ),
        )
    }

    @Test
    fun concurrentWritersProduceACompleteBoundedHistory() {
        val root = createTempDirectory("support-diagnostics-concurrent").toFile()
        val diagnostics = diagnostics(root)
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        repeat(8) { worker ->
            workers.execute {
                ready.countDown()
                start.await()
                repeat(20) { index ->
                    diagnostics.record(
                        SupportDiagnosticEventDraft(
                            severity = SupportDiagnosticSeverity.Warning,
                            component = SupportDiagnosticComponent.Sync,
                            operation = "sync.retry",
                            outcome = "scheduled",
                            attempt = index + 1,
                            fields = listOf(
                                SupportDiagnosticFieldDraft(
                                    name = "pair",
                                    value = "pair-$worker",
                                    privacy = SupportDiagnosticValuePrivacy.Identifier,
                                ),
                            ),
                        ),
                    )
                }
            }
        }
        assertTrue(ready.await(10L, TimeUnit.SECONDS))
        start.countDown()
        workers.shutdown()
        assertTrue(workers.awaitTermination(30L, TimeUnit.SECONDS))

        assertEquals(160, diagnostics.summary().eventCount)
        assertEquals(160, diagnostics(root).summary().eventCount)
        assertTrue(File(root, "events-v1.jsonl").length() <= MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES)
    }

    @Test
    fun settingsPreviewIsBoundedAndDoesNotExposeMessagesOrFields() {
        val root = createTempDirectory("support-diagnostics-preview").toFile()
        val diagnostics = diagnostics(root)
        repeat(25) { index ->
            diagnostics.record(
                failureEvent("/srv/fixtures/private-$index.jpg").copy(attempt = index + 1),
            )
        }

        val preview = diagnostics.summary().recentEvents

        assertEquals(20, preview.size)
        assertTrue(preview.all { it.operation == "cloud-files.placeholder-create" })
    }

    @Test
    fun transportFailureCodeIsVisibleAndSafeContextIsExported() {
        val root = createTempDirectory("support-diagnostics-network-failure").toFile()
        val diagnostics = diagnostics(root)
        val privateHost = "private-cloud.example.test"
        diagnostics.record(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.Network,
                operation = "http.request",
                outcome = "failed",
                code = "NETWORK_DNS_UNRESOLVED",
                attempt = 1,
                fields = listOf(
                    SupportDiagnosticFieldDraft("failure_phase", "dns"),
                    SupportDiagnosticFieldDraft("retryable", "true"),
                    SupportDiagnosticFieldDraft("url", "https://$privateHost/remote.php", SupportDiagnosticValuePrivacy.Url),
                ),
                exception = java.net.UnknownHostException(privateHost).toSupportDiagnosticExceptionDraft(),
            ),
        )

        assertEquals("NETWORK_DNS_UNRESOLVED", diagnostics.summary().recentEvents.single().code)
        val destination = File(root, "network-report.zip")
        diagnostics.writeBundle(destination, "", emptyList())
        ZipFile(destination).use { zip ->
            val events = zip.getInputStream(assertNotNull(zip.getEntry("events.jsonl")))
                .bufferedReader()
                .use { it.readText() }
            assertTrue("NETWORK_DNS_UNRESOLVED" in events)
            assertTrue("failure_phase" in events)
            assertTrue("dns" in events)
            assertFalse(privateHost in events)
        }
    }

    @Test
    fun exportedArchiveContainsOnlyDeclaredAnonymizedFilesAndValidDigests() {
        val root = createTempDirectory("support-diagnostics-export").toFile()
        val diagnostics = diagnostics(root)
        diagnostics.registerPrivateValue("https://cloud.example.test")
        diagnostics.record(failureEvent("D:\\Fixtures\\Person\\Nextcloud Native\\private.jpg"))
        val destination = File(root, "support-report.zip")

        diagnostics.writeBundle(
            destination = destination,
            reproductionSteps = "Opened https://cloud.example.test as person@example.test",
            featureState = listOf(
                SupportDiagnosticFieldDraft("virtual_files", "enabled"),
                SupportDiagnosticFieldDraft(
                    "sync_root",
                    "D:\\Fixtures\\Person\\Nextcloud Native",
                    SupportDiagnosticValuePrivacy.LocalPath,
                ),
            ),
        )

        assertTrue(destination.isFile)
        ZipFile(destination).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertEquals(SUPPORT_BUNDLE_INCLUDED_FILES.toSet(), names)
            val combined = names.joinToString("\n") { name ->
                zip.getInputStream(assertNotNull(zip.getEntry(name))).bufferedReader().use { it.readText() }
            }
            listOf(
                "cloud.example.test",
                "person@example.test",
                "Person",
                "private.jpg",
                File(root, "redaction-key-v1").readText().trim(),
            ).forEach { privateValue -> assertFalse(privateValue in combined, privateValue) }
            assertTrue("Reports are never uploaded automatically" in combined)
            assertTrue("\"eventCount\":1" in combined)
            assertTrue("<local-path:" in combined)
            val manifest = JSONObject(
                zip.getInputStream(assertNotNull(zip.getEntry("manifest.json"))).bufferedReader().use { it.readText() },
            )
            val entries = manifest.getJSONArray("entries")
            assertEquals(3, entries.length())
            repeat(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val name = entry.getString("name")
                val bytes = zip.getInputStream(assertNotNull(zip.getEntry(name))).use { it.readBytes() }
                assertEquals(bytes.size.toLong(), entry.getLong("bytes"))
                assertEquals(
                    MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
                    entry.getString("sha256"),
                )
            }
        }
    }

    @Test
    fun clearRemovesEventsButKeepsThePrivateRedactionIdentity() {
        val root = createTempDirectory("support-diagnostics-clear").toFile()
        val diagnostics = diagnostics(root)
        diagnostics.record(failureEvent("/srv/fixtures/private.jpg"))
        val keyBefore = File(root, "redaction-key-v1").readBytes()

        assertTrue(diagnostics.clear())

        assertEquals(0, diagnostics.summary().eventCount)
        assertEquals(keyBefore.toList(), File(root, "redaction-key-v1").readBytes().toList())
        assertEquals("", File(root, "events-v1.jsonl").readText())
    }

    private fun diagnostics(
        root: File,
        nowEpochMillis: () -> Long = { 1_000_000L },
    ): JvmSupportDiagnostics = JvmSupportDiagnostics(
        root = root.absoluteFile,
        environment = environment(),
        nowEpochMillis = nowEpochMillis,
        randomBytes = { size -> ByteArray(size) { index -> (index + 1).toByte() } },
    )

    private fun environment(): SupportDiagnosticsEnvironment = SupportDiagnosticsEnvironment(
        appVersion = "nightly-test",
        packageVersion = "1.0.0",
        platform = "Windows",
        operatingSystemVersion = "11",
        architecture = "amd64",
    )

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun failureEvent(path: String): SupportDiagnosticEventDraft = SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Error,
        component = SupportDiagnosticComponent.VirtualFiles,
        operation = "cloud-files.placeholder-create",
        outcome = "failed",
        code = "HRESULT:0x800700b7",
        message = "Could not create $path for person@example.test",
        fields = listOf(
            SupportDiagnosticFieldDraft(
                name = "path",
                value = path,
                privacy = SupportDiagnosticValuePrivacy.LocalPath,
            ),
        ),
    )
}
