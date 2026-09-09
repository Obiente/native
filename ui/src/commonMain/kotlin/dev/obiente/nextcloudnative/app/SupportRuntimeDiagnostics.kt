package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

@Serializable
internal enum class SupportRuntimeMetric {
    HeapUsedBytes,
    HeapCommittedBytes,
    HeapMaximumBytes,
    NonHeapUsedBytes,
    NonHeapCommittedBytes,
    NonHeapMaximumBytes,
    ProcessUptimeMillis,
    LiveThreadCount,
    PeakThreadCount,
    DaemonThreadCount,
    GarbageCollectionCount,
    GarbageCollectionTimeMillis,
    DirectBufferUsedBytes,
    DirectBufferTotalCapacityBytes,
    DirectBufferCount,
    MappedBufferUsedBytes,
    MappedBufferTotalCapacityBytes,
    MappedBufferCount,
}

@Serializable
internal data class SupportRuntimeSnapshot(
    val schemaVersion: Int = SUPPORT_RUNTIME_SNAPSHOT_SCHEMA_VERSION,
    val heapUsedBytes: Long?,
    val heapCommittedBytes: Long?,
    val heapMaximumBytes: Long?,
    val nonHeapUsedBytes: Long?,
    val nonHeapCommittedBytes: Long?,
    val nonHeapMaximumBytes: Long?,
    val processUptimeMillis: Long?,
    val liveThreadCount: Int?,
    val peakThreadCount: Int?,
    val daemonThreadCount: Int?,
    val garbageCollectionCount: Long?,
    val garbageCollectionTimeMillis: Long?,
    val unavailableMetrics: List<SupportRuntimeMetric>,
    val truncatedMetrics: List<SupportRuntimeMetric>,
    val directBufferUsedBytes: Long? = null,
    val directBufferTotalCapacityBytes: Long? = null,
    val directBufferCount: Long? = null,
    val mappedBufferUsedBytes: Long? = null,
    val mappedBufferTotalCapacityBytes: Long? = null,
    val mappedBufferCount: Long? = null,
) {
    init {
        require(schemaVersion == SUPPORT_RUNTIME_SNAPSHOT_SCHEMA_VERSION)
        require(unavailableMetrics.distinct().size == unavailableMetrics.size)
        require(truncatedMetrics.distinct().size == truncatedMetrics.size)
        require(unavailableMetrics.none(truncatedMetrics::contains))
        require(heapUsedBytes == null || heapUsedBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(heapCommittedBytes == null || heapCommittedBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(heapMaximumBytes == null || heapMaximumBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(nonHeapUsedBytes == null || nonHeapUsedBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(nonHeapCommittedBytes == null || nonHeapCommittedBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(nonHeapMaximumBytes == null || nonHeapMaximumBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(processUptimeMillis == null || processUptimeMillis in 0L..MAX_SUPPORT_RUNTIME_DURATION_MILLIS)
        require(liveThreadCount == null || liveThreadCount in 0..MAX_SUPPORT_RUNTIME_THREAD_COUNT)
        require(peakThreadCount == null || peakThreadCount in 0..MAX_SUPPORT_RUNTIME_THREAD_COUNT)
        require(daemonThreadCount == null || daemonThreadCount in 0..MAX_SUPPORT_RUNTIME_THREAD_COUNT)
        require(garbageCollectionCount == null || garbageCollectionCount in 0L..MAX_SUPPORT_RUNTIME_GC_COUNT)
        require(
            garbageCollectionTimeMillis == null ||
                garbageCollectionTimeMillis in 0L..MAX_SUPPORT_RUNTIME_DURATION_MILLIS,
        )
        require(directBufferUsedBytes == null || directBufferUsedBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(
            directBufferTotalCapacityBytes == null ||
                directBufferTotalCapacityBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES,
        )
        require(directBufferCount == null || directBufferCount in 0L..MAX_SUPPORT_RUNTIME_BUFFER_COUNT)
        require(mappedBufferUsedBytes == null || mappedBufferUsedBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES)
        require(
            mappedBufferTotalCapacityBytes == null ||
                mappedBufferTotalCapacityBytes in 0L..MAX_SUPPORT_RUNTIME_BYTES,
        )
        require(mappedBufferCount == null || mappedBufferCount in 0L..MAX_SUPPORT_RUNTIME_BUFFER_COUNT)
    }
}

internal fun unavailableSupportRuntimeSnapshot(): SupportRuntimeSnapshot = SupportRuntimeSnapshot(
    heapUsedBytes = null,
    heapCommittedBytes = null,
    heapMaximumBytes = null,
    nonHeapUsedBytes = null,
    nonHeapCommittedBytes = null,
    nonHeapMaximumBytes = null,
    processUptimeMillis = null,
    liveThreadCount = null,
    peakThreadCount = null,
    daemonThreadCount = null,
    garbageCollectionCount = null,
    garbageCollectionTimeMillis = null,
    unavailableMetrics = SupportRuntimeMetric.entries,
    truncatedMetrics = emptyList(),
)

internal fun SupportRuntimeSnapshot.withExplicitBufferAvailability(): SupportRuntimeSnapshot {
    val missing = buildList {
        if (directBufferUsedBytes == null) add(SupportRuntimeMetric.DirectBufferUsedBytes)
        if (directBufferTotalCapacityBytes == null) add(SupportRuntimeMetric.DirectBufferTotalCapacityBytes)
        if (directBufferCount == null) add(SupportRuntimeMetric.DirectBufferCount)
        if (mappedBufferUsedBytes == null) add(SupportRuntimeMetric.MappedBufferUsedBytes)
        if (mappedBufferTotalCapacityBytes == null) add(SupportRuntimeMetric.MappedBufferTotalCapacityBytes)
        if (mappedBufferCount == null) add(SupportRuntimeMetric.MappedBufferCount)
    }
    val normalizedUnavailable = (unavailableMetrics + missing).distinct()
    return copy(
        unavailableMetrics = normalizedUnavailable,
        truncatedMetrics = truncatedMetrics.filterNot(normalizedUnavailable::contains),
    )
}

@Serializable
internal enum class SupportDiagnosticHistoryTruncationStatus {
    NotObserved,
    Observed,
    UnknownAfterRestart,
}

@Serializable
internal data class SupportDiagnosticHistorySnapshot(
    val includedEventCount: Int,
    val includedEventBytes: Long,
    val maximumStoredEventCount: Int = MAX_SUPPORT_DIAGNOSTIC_EVENTS,
    val maximumStoredEventBytes: Long = MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES,
    val retentionWindowMillis: Long = MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS,
    val capacityTruncationStatus: SupportDiagnosticHistoryTruncationStatus,
) {
    init {
        require(includedEventCount in 0..maximumStoredEventCount)
        require(includedEventBytes in 0L..maximumStoredEventBytes)
        require(maximumStoredEventCount == MAX_SUPPORT_DIAGNOSTIC_EVENTS)
        require(maximumStoredEventBytes == MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES)
        require(retentionWindowMillis == MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS)
    }
}

internal const val SUPPORT_RUNTIME_SNAPSHOT_SCHEMA_VERSION = 1
internal const val MAX_SUPPORT_RUNTIME_BYTES = 1L shl 60
internal const val MAX_SUPPORT_RUNTIME_DURATION_MILLIS = 100L * 366L * 24L * 60L * 60L * 1_000L
internal const val MAX_SUPPORT_RUNTIME_THREAD_COUNT = 1_000_000
internal const val MAX_SUPPORT_RUNTIME_GC_COUNT = 1_000_000_000_000L
internal const val MAX_SUPPORT_RUNTIME_BUFFER_COUNT = 1_000_000_000L
