package dev.obiente.nextcloudnative.nativeui.runtime

fun interface NativeActionExecutor {
    suspend fun execute(request: NativeActionRequest): NativeActionExecutionResult
}

enum class NativeActionFailureOutcome {
    Rejected,
    Unknown,
}

/** Why a guarded write was not sent. Either way nothing reached the target. */
enum class NativeWritePreconditionFailure {
    /** The fresh read shows the target no longer matches the state the edit started from. */
    Changed,

    /** The target could not be re-read and compared, so the write was withheld. */
    Unverified,
}

sealed interface NativeActionExecutionResult {
    data class Success(val message: String? = null) : NativeActionExecutionResult
    data class Failure(
        val message: String,
        val outcome: NativeActionFailureOutcome = NativeActionFailureOutcome.Unknown,
        /** Set only when a [NativeWritePrecondition] withheld the write; [outcome] is then Rejected. */
        val precondition: NativeWritePreconditionFailure? = null,
    ) : NativeActionExecutionResult
}

/**
 * The state a replacement write expects its target to still hold.
 *
 * A replacement (PUT) sends a complete copy built from what the editor read earlier. Immediately
 * before sending it, the executor re-reads the target through the write's own contract-declared
 * same-route read, bypassing caches, and sends nothing when that read fails or when [holds]
 * reports a difference. This keeps a concurrent change from being overwritten by a stale copy.
 */
class NativeWritePrecondition(
    /** Route values of the same-route read; the identities the write itself binds. */
    val readValues: Map<String, String>,
    /** Identity of the target record in the read result. */
    val targetId: String,
    private val unchanged: (NativeRecord) -> Boolean,
) {
    /** True only when [records] hold exactly one record for [targetId] and it is unchanged. */
    fun holds(records: List<NativeRecord>): Boolean {
        val target = records.filter { it.id == targetId }.singleOrNull() ?: return false
        return target.actionSafeIdentity && unchanged(target)
    }
}

internal fun nativeWritePreconditionFailure(
    reason: NativeWritePreconditionFailure,
): NativeActionExecutionResult.Failure = NativeActionExecutionResult.Failure(
    message = when (reason) {
        NativeWritePreconditionFailure.Changed ->
            "This changed since you opened it, so nothing was saved. Refresh to load the current values, then edit again."
        NativeWritePreconditionFailure.Unverified ->
            "The current values could not be checked, so nothing was saved. Try again when the connection is stable."
    },
    outcome = NativeActionFailureOutcome.Rejected,
    precondition = reason,
)
