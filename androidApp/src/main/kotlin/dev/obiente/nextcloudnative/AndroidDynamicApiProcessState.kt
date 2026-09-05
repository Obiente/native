package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.NextcloudApiResponse
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates every Android owner of one dynamic response cache directory.
 *
 * AndroidManifest.xml does not assign an android:process to an app component, so activities,
 * providers, and workers share this process registry. Disk deletion still fails closed in the
 * cache implementation instead of relying on this process-only coordination for filesystem safety.
 */
internal class AndroidDynamicApiProcessState internal constructor(root: File) {
    val cache = DynamicApiResponseCache(root)
    val coalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
}

internal fun androidDynamicApiProcessState(root: File): AndroidDynamicApiProcessState {
    val canonicalRoot = root.canonicalFile
    return ANDROID_DYNAMIC_API_PROCESS_STATES.computeIfAbsent(canonicalRoot.path) {
        AndroidDynamicApiProcessState(canonicalRoot)
    }
}

private val ANDROID_DYNAMIC_API_PROCESS_STATES = ConcurrentHashMap<String, AndroidDynamicApiProcessState>()
