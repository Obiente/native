package dev.obiente.nextcloudnative.app

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFileSyncDiagnosticsTest {
    @Test
    fun `count and byte diagnostics use stable coarse buckets`() {
        assertEquals("0", desktopFileSyncCountBucket(0))
        assertEquals("10k-99k", desktopFileSyncCountBucket(42_000))
        assertEquals("100k+", desktopFileSyncCountBucket(100_001))
        assertEquals("under-1mib", desktopFileSyncByteBucket(512L * 1_024L))
        assertEquals("10gib-99gib", desktopFileSyncByteBucket(17L * 1_024L * 1_024L * 1_024L))
    }

    @Test
    fun `scan limit diagnostics retain private pair identity`() {
        val event = DesktopFileSyncScanLimitException(
            maximumEntries = 100_000,
            observedEntries = 100_001,
            observedFiles = 80_000,
            observedFileBytes = 17L * 1_024L * 1_024L * 1_024L,
        ).toDesktopFileSyncRunDiagnosticEvent("pair-private", DesktopFileSyncScanStage.Local)
            .toSupportDiagnosticEventDraft()

        val pair = event.fields.single { it.name == "pair" }
        assertEquals("SYNC_SCAN_LIMIT_EXCEEDED", event.code)
        assertEquals("pair-private", pair.value)
        assertEquals(SupportDiagnosticValuePrivacy.Identifier, pair.privacy)
    }

    @Test
    fun `remote failures retain actionable delivery semantics`() {
        assertEquals("authorization", desktopFileSyncFailureKind(DesktopFileSyncHttpStatusException(403, "write")))
        assertEquals("conflict", desktopFileSyncFailureKind(DesktopFileSyncHttpStatusException(412, "write")))
        assertEquals("throttled", desktopFileSyncFailureKind(DesktopFileSyncHttpStatusException(429, "write")))
        assertEquals("server", desktopFileSyncFailureKind(DesktopFileSyncHttpStatusException(503, "write")))
        assertEquals(
            "ambiguous_delivery",
            desktopFileSyncFailureKind(DesktopFileSyncAmbiguousMutationException(IOException("closed"))),
        )
    }

    @Test
    fun `remote byte diagnostics preserve missing file sizes`() {
        val directory = RemoteSyncEntry("Photos", SyncEntryKind.Directory, "directory")
        val known = RemoteSyncEntry("Photos/known.jpg", SyncEntryKind.File, "known", size = 0L)
        val unknown = RemoteSyncEntry("Photos/unknown.jpg", SyncEntryKind.File, "unknown")

        assertEquals(
            "0",
            desktopFileSyncSnapshotDiagnostics(emptyList(), listOf(directory, known)).remoteFileBytesBucket,
        )
        assertEquals(
            "unknown",
            desktopFileSyncSnapshotDiagnostics(emptyList(), listOf(directory, known, unknown)).remoteFileBytesBucket,
        )
    }
}
