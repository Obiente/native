package dev.obiente.nextcloudnative.app

internal data class RawJvmSupportRuntimeSnapshot(
    val heapUsedBytes: Long?,
    val heapCommittedBytes: Long?,
    val heapMaximumBytes: Long?,
    val nonHeapUsedBytes: Long?,
    val nonHeapCommittedBytes: Long?,
    val nonHeapMaximumBytes: Long?,
    val processUptimeMillis: Long?,
    val liveThreadCount: Long?,
    val peakThreadCount: Long?,
    val daemonThreadCount: Long?,
    val garbageCollectionCount: Long?,
    val garbageCollectionTimeMillis: Long?,
    val directBufferUsedBytes: Long? = null,
    val directBufferTotalCapacityBytes: Long? = null,
    val directBufferCount: Long? = null,
    val mappedBufferUsedBytes: Long? = null,
    val mappedBufferTotalCapacityBytes: Long? = null,
    val mappedBufferCount: Long? = null,
)

internal fun captureJvmSupportRuntimeSnapshot(): SupportRuntimeSnapshot {
    val runtime = Runtime.getRuntime()
    val heapCommitted = runCatching(runtime::totalMemory).getOrNull()
    val heapFree = runCatching(runtime::freeMemory).getOrNull()
    val heapUsed = if (heapCommitted != null && heapFree != null) {
        (heapCommitted - heapFree).coerceAtLeast(0L)
    } else {
        null
    }
    val management = JvmManagementRuntimeReader.read()
    return boundedJvmSupportRuntimeSnapshot(
        RawJvmSupportRuntimeSnapshot(
            heapUsedBytes = heapUsed,
            heapCommittedBytes = heapCommitted,
            heapMaximumBytes = runCatching(runtime::maxMemory).getOrNull(),
            nonHeapUsedBytes = management.nonHeapUsedBytes,
            nonHeapCommittedBytes = management.nonHeapCommittedBytes,
            nonHeapMaximumBytes = management.nonHeapMaximumBytes,
            processUptimeMillis = management.processUptimeMillis,
            liveThreadCount = management.liveThreadCount,
            peakThreadCount = management.peakThreadCount,
            daemonThreadCount = management.daemonThreadCount,
            garbageCollectionCount = management.garbageCollectionCount,
            garbageCollectionTimeMillis = management.garbageCollectionTimeMillis,
            directBufferUsedBytes = management.directBufferUsedBytes,
            directBufferTotalCapacityBytes = management.directBufferTotalCapacityBytes,
            directBufferCount = management.directBufferCount,
            mappedBufferUsedBytes = management.mappedBufferUsedBytes,
            mappedBufferTotalCapacityBytes = management.mappedBufferTotalCapacityBytes,
            mappedBufferCount = management.mappedBufferCount,
        ),
    )
}

internal fun boundedJvmSupportRuntimeSnapshot(raw: RawJvmSupportRuntimeSnapshot): SupportRuntimeSnapshot {
    val unavailable = mutableListOf<SupportRuntimeMetric>()
    val truncated = mutableListOf<SupportRuntimeMetric>()

    fun boundedLong(value: Long?, metric: SupportRuntimeMetric, maximum: Long): Long? {
        if (value == null || value < 0L) {
            unavailable += metric
            return null
        }
        if (value > maximum) truncated += metric
        return value.coerceAtMost(maximum)
    }

    fun boundedInt(value: Long?, metric: SupportRuntimeMetric): Int? =
        boundedLong(value, metric, MAX_SUPPORT_RUNTIME_THREAD_COUNT.toLong())?.toInt()

    return SupportRuntimeSnapshot(
        heapUsedBytes = boundedLong(raw.heapUsedBytes, SupportRuntimeMetric.HeapUsedBytes, MAX_SUPPORT_RUNTIME_BYTES),
        heapCommittedBytes = boundedLong(
            raw.heapCommittedBytes,
            SupportRuntimeMetric.HeapCommittedBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        heapMaximumBytes = boundedLong(
            raw.heapMaximumBytes,
            SupportRuntimeMetric.HeapMaximumBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        nonHeapUsedBytes = boundedLong(
            raw.nonHeapUsedBytes,
            SupportRuntimeMetric.NonHeapUsedBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        nonHeapCommittedBytes = boundedLong(
            raw.nonHeapCommittedBytes,
            SupportRuntimeMetric.NonHeapCommittedBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        nonHeapMaximumBytes = boundedLong(
            raw.nonHeapMaximumBytes,
            SupportRuntimeMetric.NonHeapMaximumBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        processUptimeMillis = boundedLong(
            raw.processUptimeMillis,
            SupportRuntimeMetric.ProcessUptimeMillis,
            MAX_SUPPORT_RUNTIME_DURATION_MILLIS,
        ),
        liveThreadCount = boundedInt(raw.liveThreadCount, SupportRuntimeMetric.LiveThreadCount),
        peakThreadCount = boundedInt(raw.peakThreadCount, SupportRuntimeMetric.PeakThreadCount),
        daemonThreadCount = boundedInt(raw.daemonThreadCount, SupportRuntimeMetric.DaemonThreadCount),
        garbageCollectionCount = boundedLong(
            raw.garbageCollectionCount,
            SupportRuntimeMetric.GarbageCollectionCount,
            MAX_SUPPORT_RUNTIME_GC_COUNT,
        ),
        garbageCollectionTimeMillis = boundedLong(
            raw.garbageCollectionTimeMillis,
            SupportRuntimeMetric.GarbageCollectionTimeMillis,
            MAX_SUPPORT_RUNTIME_DURATION_MILLIS,
        ),
        directBufferUsedBytes = boundedLong(
            raw.directBufferUsedBytes,
            SupportRuntimeMetric.DirectBufferUsedBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        directBufferTotalCapacityBytes = boundedLong(
            raw.directBufferTotalCapacityBytes,
            SupportRuntimeMetric.DirectBufferTotalCapacityBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        directBufferCount = boundedLong(
            raw.directBufferCount,
            SupportRuntimeMetric.DirectBufferCount,
            MAX_SUPPORT_RUNTIME_BUFFER_COUNT,
        ),
        mappedBufferUsedBytes = boundedLong(
            raw.mappedBufferUsedBytes,
            SupportRuntimeMetric.MappedBufferUsedBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        mappedBufferTotalCapacityBytes = boundedLong(
            raw.mappedBufferTotalCapacityBytes,
            SupportRuntimeMetric.MappedBufferTotalCapacityBytes,
            MAX_SUPPORT_RUNTIME_BYTES,
        ),
        mappedBufferCount = boundedLong(
            raw.mappedBufferCount,
            SupportRuntimeMetric.MappedBufferCount,
            MAX_SUPPORT_RUNTIME_BUFFER_COUNT,
        ),
        unavailableMetrics = unavailable,
        truncatedMetrics = truncated,
    )
}

private object JvmManagementRuntimeReader {
    fun read(): RawJvmSupportRuntimeSnapshot {
        val managementFactory = runCatching { Class.forName("java.lang.management.ManagementFactory") }.getOrNull()
            ?: return unavailable()
        val memory = managementFactory.invokeStatic("getMemoryMXBean")
        val nonHeap = memory.invokeContract("java.lang.management.MemoryMXBean", "getNonHeapMemoryUsage")
        val runtime = managementFactory.invokeStatic("getRuntimeMXBean")
        val threads = managementFactory.invokeStatic("getThreadMXBean")
        val garbageCollectors = managementFactory.invokeStatic("getGarbageCollectorMXBeans") as? Iterable<*>
        val bufferPools = managementFactory.readBufferPools()
        val directBuffers = bufferPools.fixedBufferPool("direct")
        val mappedBuffers = bufferPools.fixedBufferPool("mapped")
        return RawJvmSupportRuntimeSnapshot(
            heapUsedBytes = null,
            heapCommittedBytes = null,
            heapMaximumBytes = null,
            nonHeapUsedBytes = nonHeap.longValue("java.lang.management.MemoryUsage", "getUsed"),
            nonHeapCommittedBytes = nonHeap.longValue("java.lang.management.MemoryUsage", "getCommitted"),
            nonHeapMaximumBytes = nonHeap.longValue("java.lang.management.MemoryUsage", "getMax"),
            processUptimeMillis = runtime.longValue("java.lang.management.RuntimeMXBean", "getUptime"),
            liveThreadCount = threads.longValue("java.lang.management.ThreadMXBean", "getThreadCount"),
            peakThreadCount = threads.longValue("java.lang.management.ThreadMXBean", "getPeakThreadCount"),
            daemonThreadCount = threads.longValue("java.lang.management.ThreadMXBean", "getDaemonThreadCount"),
            garbageCollectionCount = garbageCollectors.sumMetric("getCollectionCount"),
            garbageCollectionTimeMillis = garbageCollectors.sumMetric("getCollectionTime"),
            directBufferUsedBytes = directBuffers.memoryUsedBytes,
            directBufferTotalCapacityBytes = directBuffers.totalCapacityBytes,
            directBufferCount = directBuffers.bufferCount,
            mappedBufferUsedBytes = mappedBuffers.memoryUsedBytes,
            mappedBufferTotalCapacityBytes = mappedBuffers.totalCapacityBytes,
            mappedBufferCount = mappedBuffers.bufferCount,
        )
    }

    private fun unavailable(): RawJvmSupportRuntimeSnapshot = RawJvmSupportRuntimeSnapshot(
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
        directBufferUsedBytes = null,
        directBufferTotalCapacityBytes = null,
        directBufferCount = null,
        mappedBufferUsedBytes = null,
        mappedBufferTotalCapacityBytes = null,
        mappedBufferCount = null,
    )
}

private fun Class<*>.invokeStatic(methodName: String): Any? =
    runCatching { getMethod(methodName).invoke(null) }.getOrNull()

private fun Class<*>.readBufferPools(): List<Any> = runCatching {
    val contract = Class.forName(BUFFER_POOL_MX_BEAN_CONTRACT)
    val pools = getMethod("getPlatformMXBeans", Class::class.java).invoke(null, contract) as? Iterable<*>
    pools?.filterNotNull().orEmpty()
}.getOrDefault(emptyList())

private fun Any?.invokeContract(contractName: String, methodName: String): Any? = this?.let { receiver ->
    runCatching { Class.forName(contractName).getMethod(methodName).invoke(receiver) }.getOrNull()
}

private fun Any?.longValue(contractName: String, methodName: String): Long? =
    (invokeContract(contractName, methodName) as? Number)?.toLong()

private fun Iterable<*>?.sumMetric(methodName: String): Long? {
    val values = this?.map { bean ->
        bean.longValue("java.lang.management.GarbageCollectorMXBean", methodName)
    } ?: return null
    if (values.isEmpty() || values.any { it == null || it < 0L }) return null
    return values.filterNotNull().fold(0L) { total, value ->
        if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }
}

private fun List<Any>.fixedBufferPool(fixedName: String): RawBufferPoolSnapshot {
    val matching = filter { pool ->
        pool.invokeContract(BUFFER_POOL_MX_BEAN_CONTRACT, "getName") == fixedName
    }
    return RawBufferPoolSnapshot(
        memoryUsedBytes = matching.sumContractMetric("getMemoryUsed"),
        totalCapacityBytes = matching.sumContractMetric("getTotalCapacity"),
        bufferCount = matching.sumContractMetric("getCount"),
    )
}

private fun List<Any>.sumContractMetric(methodName: String): Long? {
    if (isEmpty()) return null
    val values = map { pool -> pool.longValue(BUFFER_POOL_MX_BEAN_CONTRACT, methodName) }
    if (values.any { it == null || it < 0L }) return null
    return values.filterNotNull().fold(0L) { total, value ->
        if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }
}

private data class RawBufferPoolSnapshot(
    val memoryUsedBytes: Long?,
    val totalCapacityBytes: Long?,
    val bufferCount: Long?,
)

private const val BUFFER_POOL_MX_BEAN_CONTRACT = "java.lang.management.BufferPoolMXBean"
