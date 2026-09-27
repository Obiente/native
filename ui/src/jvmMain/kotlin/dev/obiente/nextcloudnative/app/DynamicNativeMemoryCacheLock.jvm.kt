package dev.obiente.nextcloudnative.app

internal actual fun dynamicNativeMemoryCacheMonitor(): Any = Any()

internal actual fun <T> withDynamicNativeMemoryCacheLock(monitor: Any, action: () -> T): T =
    synchronized(monitor, action)
