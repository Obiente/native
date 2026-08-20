package dev.obiente.nextcloudnative.nativeui.runtime

internal class NativeAuthoritativeRecordsKey(
    private val records: List<NativeRecord>,
) {
    override fun equals(other: Any?): Boolean =
        other is NativeAuthoritativeRecordsKey && records === other.records

    override fun hashCode(): Int = 0
}

internal data class NativeCompletionOverride(
    val completed: Boolean,
    val sourceRecordsKey: NativeAuthoritativeRecordsKey,
)

internal fun effectiveNativeCompletion(
    override: NativeCompletionOverride?,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    authoritativeCompleted: Boolean,
): Boolean = override
    ?.takeIf { candidate -> candidate.sourceRecordsKey == authoritativeRecordsKey }
    ?.completed
    ?: authoritativeCompleted

internal fun MutableMap<String, NativeCompletionOverride>.reconcileNativeCompletionOverrides(
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
) {
    keys.filter { recordId ->
        get(recordId)?.sourceRecordsKey != authoritativeRecordsKey
    }.forEach(::remove)
}

/**
 * Records a completion result whose server outcome is unknown until a later authoritative refresh.
 * The Boolean return value tells the UI whether it must request that refresh.
 */
internal fun MutableMap<String, NativeAuthoritativeRecordsKey>.recordNativeCompletionFailure(
    recordId: String,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    outcome: NativeActionFailureOutcome,
): Boolean {
    if (!outcome.requiresMutationReconciliation()) {
        remove(recordId)
        return false
    }
    this[recordId] = authoritativeRecordsKey
    return true
}

internal fun Map<String, NativeAuthoritativeRecordsKey>.isNativeCompletionReconciling(
    recordId: String,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
): Boolean = get(recordId) == authoritativeRecordsKey

internal fun MutableMap<String, NativeAuthoritativeRecordsKey>.reconcileNativeCompletionFailures(
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
): Set<String> {
    val reconciledRecordIds = keys.filterTo(linkedSetOf()) { recordId ->
        get(recordId) != authoritativeRecordsKey
    }
    reconciledRecordIds.forEach(::remove)
    return reconciledRecordIds
}
