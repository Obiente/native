package dev.obiente.nextcloudnative.app

/** What happened to one item of a multi-item operation. */
enum class BulkItemOutcome {
    /** The server confirmed the change, or a later read verified it. */
    Completed,

    /** The requested state already held before this request. */
    AlreadyDone,

    /** The server declined the change; the item is unchanged. */
    Failed,

    /** The request may have taken effect and a verification read could not decide. */
    Unconfirmed,

    /** The item was not attempted, for example because it was blocked or the batch was cancelled. */
    Skipped,
}

data class BulkItemResult(
    /** Stable item identity within the operation, for example a path or file ID. */
    val itemKey: String,
    val label: String,
    val outcome: BulkItemOutcome,
    /** Short user-facing reason for [BulkItemOutcome.Failed], [Unconfirmed][BulkItemOutcome.Unconfirmed] or skips. */
    val reason: String? = null,
    /**
     * Whether repeating the same request for this item cannot duplicate or overwrite anything.
     * Unconfirmed items are never retry-safe until a refresh settles their state.
     */
    val retrySafe: Boolean = false,
) {
    init {
        require(outcome != BulkItemOutcome.Unconfirmed || !retrySafe) {
            "An unconfirmed item cannot be retried before its state is verified."
        }
    }
}

/**
 * Per-item result of a partial-success operation. [verb] is the past-tense action phrase, for
 * example "restored" or "moved to Photos/2024".
 */
data class BulkOperationReport(
    val verb: String,
    val results: List<BulkItemResult>,
) {
    val completed: List<BulkItemResult> get() = results.filter {
        it.outcome == BulkItemOutcome.Completed || it.outcome == BulkItemOutcome.AlreadyDone
    }
    val failed: List<BulkItemResult> get() = results.filter { it.outcome == BulkItemOutcome.Failed }
    val unconfirmed: List<BulkItemResult> get() = results.filter { it.outcome == BulkItemOutcome.Unconfirmed }
    val skipped: List<BulkItemResult> get() = results.filter { it.outcome == BulkItemOutcome.Skipped }
    val problems: List<BulkItemResult> get() = results.filter {
        it.outcome != BulkItemOutcome.Completed && it.outcome != BulkItemOutcome.AlreadyDone
    }
    val retryableKeys: Set<String> get() = results.filter(BulkItemResult::retrySafe).mapTo(LinkedHashSet(), BulkItemResult::itemKey)
    val fullySucceeded: Boolean get() = results.isNotEmpty() && problems.isEmpty()

    /** One line that never claims more than the per-item results support. */
    fun summary(): String {
        val total = results.size
        val done = completed.size
        if (total == 0) return "Nothing was changed."
        if (done == total) return "${itemCount(total)} $verb."
        return buildString {
            append("$done of ${itemCount(total)} $verb.")
            if (failed.isNotEmpty()) append(" ${failed.size} failed.")
            if (unconfirmed.isNotEmpty()) append(" ${unconfirmed.size} could not be confirmed; refresh before trying again.")
            if (skipped.isNotEmpty()) append(" ${skipped.size} not attempted.")
        }
    }
}

internal fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"
