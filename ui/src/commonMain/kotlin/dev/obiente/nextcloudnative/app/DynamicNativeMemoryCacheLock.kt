package dev.obiente.nextcloudnative.app

/** Platform lock for synchronous cache access from Compose and background JVM owners. */
internal class DynamicNativeMemoryCacheLock {
    private val monitor = dynamicNativeMemoryCacheMonitor()

    fun <T> withLock(action: () -> T): T = withDynamicNativeMemoryCacheLock(monitor, action)
}

internal expect fun dynamicNativeMemoryCacheMonitor(): Any

internal expect fun <T> withDynamicNativeMemoryCacheLock(monitor: Any, action: () -> T): T
