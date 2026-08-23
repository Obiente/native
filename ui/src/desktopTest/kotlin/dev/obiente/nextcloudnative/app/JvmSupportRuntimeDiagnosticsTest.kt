package dev.obiente.nextcloudnative.app

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JvmSupportRuntimeDiagnosticsTest {
    @Test
    fun reportFreezesRuntimeAndHistorySnapshotsWhenTheUserConfirms() {
        val root = createTempDirectory("support-runtime-snapshot").toFile()
        var collections = 0
        val first = completeSnapshot(heapUsedBytes = 17L * 1_024L * 1_024L * 1_024L)
        val diagnostics = JvmSupportDiagnostics(
            root = root,
            environment = environment(),
            nowEpochMillis = { 1_000L },
            runtimeSnapshotProvider = {
                collections += 1
                first
            },
            randomBytes = { size -> ByteArray(size) { 1 } },
        )
        diagnostics.record(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Warning,
                component = SupportDiagnosticComponent.Sync,
                operation = "sync.queue",
                outcome = "stalled",
            ),
        )

        val context = diagnostics.prepareSubmissionContext("", emptyList())
        val destination = File(root, "report.zip")
        diagnostics.writeBundleForSubmission(destination, context)

        assertEquals(1, collections)
        ZipFile(destination).use { archive ->
            val report = archive.getInputStream(archive.getEntry("report.json"))
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonObject }
            assertEquals(2, report.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertEquals(
                first.heapUsedBytes,
                report.getValue("runtime").jsonObject.getValue("heapUsedBytes").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                first.directBufferUsedBytes,
                report.getValue("runtime").jsonObject.getValue("directBufferUsedBytes").jsonPrimitive.content.toLong(),
            )
            assertEquals(
                first.mappedBufferTotalCapacityBytes,
                report.getValue("runtime").jsonObject
                    .getValue("mappedBufferTotalCapacityBytes").jsonPrimitive.content.toLong(),
            )
            val history = report.getValue("eventHistory").jsonObject
            assertEquals(1, history.getValue("includedEventCount").jsonPrimitive.content.toInt())
            assertEquals(
                MAX_SUPPORT_DIAGNOSTIC_EVENTS,
                history.getValue("maximumStoredEventCount").jsonPrimitive.content.toInt(),
            )
            assertEquals(
                SupportDiagnosticHistoryTruncationStatus.NotObserved.name,
                history.getValue("capacityTruncationStatus").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun invalidAndOversizedRuntimeMetricsAreMarkedInsteadOfLeakingUnboundedValues() {
        val snapshot = boundedJvmSupportRuntimeSnapshot(
            RawJvmSupportRuntimeSnapshot(
                heapUsedBytes = Long.MAX_VALUE,
                heapCommittedBytes = -1L,
                heapMaximumBytes = null,
                nonHeapUsedBytes = 3L,
                nonHeapCommittedBytes = 4L,
                nonHeapMaximumBytes = 5L,
                processUptimeMillis = Long.MAX_VALUE,
                liveThreadCount = MAX_SUPPORT_RUNTIME_THREAD_COUNT.toLong() + 1L,
                peakThreadCount = 8L,
                daemonThreadCount = 3L,
                garbageCollectionCount = Long.MAX_VALUE,
                garbageCollectionTimeMillis = -1L,
                directBufferUsedBytes = Long.MAX_VALUE,
                directBufferTotalCapacityBytes = 12L,
                directBufferCount = MAX_SUPPORT_RUNTIME_BUFFER_COUNT + 1L,
                mappedBufferUsedBytes = -1L,
                mappedBufferTotalCapacityBytes = null,
                mappedBufferCount = 4L,
            ),
        )

        assertEquals(MAX_SUPPORT_RUNTIME_BYTES, snapshot.heapUsedBytes)
        assertEquals(null, snapshot.heapCommittedBytes)
        assertEquals(null, snapshot.heapMaximumBytes)
        assertEquals(MAX_SUPPORT_RUNTIME_THREAD_COUNT, snapshot.liveThreadCount)
        assertTrue(SupportRuntimeMetric.HeapUsedBytes in snapshot.truncatedMetrics)
        assertTrue(SupportRuntimeMetric.LiveThreadCount in snapshot.truncatedMetrics)
        assertTrue(SupportRuntimeMetric.HeapCommittedBytes in snapshot.unavailableMetrics)
        assertTrue(SupportRuntimeMetric.HeapMaximumBytes in snapshot.unavailableMetrics)
        assertTrue(SupportRuntimeMetric.GarbageCollectionTimeMillis in snapshot.unavailableMetrics)
        assertEquals(MAX_SUPPORT_RUNTIME_BYTES, snapshot.directBufferUsedBytes)
        assertEquals(MAX_SUPPORT_RUNTIME_BUFFER_COUNT, snapshot.directBufferCount)
        assertTrue(SupportRuntimeMetric.DirectBufferUsedBytes in snapshot.truncatedMetrics)
        assertTrue(SupportRuntimeMetric.DirectBufferCount in snapshot.truncatedMetrics)
        assertTrue(SupportRuntimeMetric.MappedBufferUsedBytes in snapshot.unavailableMetrics)
        assertTrue(SupportRuntimeMetric.MappedBufferTotalCapacityBytes in snapshot.unavailableMetrics)
        assertTrue(snapshot.unavailableMetrics.none(snapshot.truncatedMetrics::contains))
    }

    private fun completeSnapshot(heapUsedBytes: Long): SupportRuntimeSnapshot = SupportRuntimeSnapshot(
        heapUsedBytes = heapUsedBytes,
        heapCommittedBytes = heapUsedBytes + 1L,
        heapMaximumBytes = heapUsedBytes + 2L,
        nonHeapUsedBytes = 10L,
        nonHeapCommittedBytes = 20L,
        nonHeapMaximumBytes = 30L,
        processUptimeMillis = 40L,
        liveThreadCount = 5,
        peakThreadCount = 6,
        daemonThreadCount = 4,
        garbageCollectionCount = 7L,
        garbageCollectionTimeMillis = 8L,
        unavailableMetrics = emptyList(),
        truncatedMetrics = emptyList(),
        directBufferUsedBytes = 50L,
        directBufferTotalCapacityBytes = 60L,
        directBufferCount = 2L,
        mappedBufferUsedBytes = 70L,
        mappedBufferTotalCapacityBytes = 80L,
        mappedBufferCount = 3L,
    )

    private fun environment(): SupportDiagnosticsEnvironment = SupportDiagnosticsEnvironment(
        appVersion = "test",
        packageVersion = "1",
        platform = "Desktop",
        operatingSystemVersion = "Test OS",
        architecture = "amd64",
    )
}
