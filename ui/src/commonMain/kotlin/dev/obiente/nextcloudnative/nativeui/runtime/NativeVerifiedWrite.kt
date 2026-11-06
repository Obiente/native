package dev.obiente.nextcloudnative.nativeui.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * The one verified write path. Every write above an idempotent state set runs through
 * [runNativeVerifiedWrite]: an optional pre-read compare, one send, and an authoritative read-back
 * whose snapshot decides the outcome. A 2xx response alone is never completion.
 */

/** What the server said about the request itself. */
sealed interface NativeWriteSendResult {
    /** A successful status with a well-formed envelope. The read-back still decides. */
    data object Accepted : NativeWriteSendResult

    /** A definite refusal (4xx or an OCS failure). Nothing changed; no read-back is needed. */
    data class Rejected(val message: String) : NativeWriteSendResult

    /** Timeout, transport failure, 5xx, or an unreadable success: the write may have applied. */
    data class Ambiguous(val message: String) : NativeWriteSendResult
}

/** The result of comparing a pre-read with the state an edit started from. */
enum class NativeWritePreRead { Unchanged, Changed, Unverified }

/** What the read-back shows about the requested change. */
sealed interface NativeWriteVerification {
    data object Confirmed : NativeWriteVerification

    /**
     * The change applied, but the server stored a different value for [adjusted] fields, or a
     * field the write did not change now differs from the baseline ([drifted]).
     */
    data class Adjusted(
        val adjusted: Set<String> = emptySet(),
        val drifted: Set<String> = emptySet(),
        val message: String? = null,
    ) : NativeWriteVerification

    /** The read-back shows the previous state: the change was not applied. */
    data class NotApplied(val message: String) : NativeWriteVerification

    /** The read-back could not identify exactly one target. Nothing is known. */
    data object Unidentified : NativeWriteVerification
}

enum class NativeNotAppliedReason {
    /** The server refused the request. */
    Rejected,

    /** The pre-read showed a concurrent change; nothing was sent. */
    PreconditionChanged,

    /** The pre-read could not be performed; nothing was sent. */
    PreconditionUnverified,

    /** The server accepted the request, yet the read-back shows the previous state: a contradiction. */
    NotAppliedAfterSuccess,

    /** The request had an unknown result and the read-back shows it did not apply. */
    NotAppliedAfterAmbiguous,
}

sealed interface NativeVerifiedWriteOutcome<out T> {
    data class Confirmed<T>(val snapshot: T) : NativeVerifiedWriteOutcome<T>

    /** Applied with server adjustments. Non-empty [drifted] is a postcondition contradiction. */
    data class Adjusted<T>(
        val snapshot: T,
        val adjusted: Set<String>,
        val drifted: Set<String>,
        val message: String?,
    ) : NativeVerifiedWriteOutcome<T>

    data class NotApplied<T>(
        val snapshot: T?,
        val reason: NativeNotAppliedReason,
        val message: String,
    ) : NativeVerifiedWriteOutcome<T>

    /**
     * The write may have applied but no read confirmed it. Writes to the target stay blocked until a
     * later authoritative read reconciles it; the request is never repeated automatically.
     */
    data class Unknown(val message: String) : NativeVerifiedWriteOutcome<Nothing>
}

/** True when the outcome contradicts what the server reported: the route must be quarantined. */
val NativeVerifiedWriteOutcome<*>.contradictsPostcondition: Boolean
    get() = (this is NativeVerifiedWriteOutcome.NotApplied && reason == NativeNotAppliedReason.NotAppliedAfterSuccess) ||
        (this is NativeVerifiedWriteOutcome.Adjusted && drifted.isNotEmpty())

/**
 * Sends one write and verifies its postcondition with an authoritative read.
 *
 * [preRead] runs first when the write replaces state read earlier; any difference or failure
 * stops before sending. A definite rejection is reported without a second request. Any other
 * result is read back and [verify] decides. Cancellation is rethrown: a caller that is cancelled
 * after [send] started treats the write as unknown and reconciles on its next authoritative read.
 */
suspend fun <T> runNativeVerifiedWrite(
    send: suspend () -> NativeWriteSendResult,
    readBack: suspend () -> T,
    verify: (T) -> NativeWriteVerification,
    actionLabel: String,
    preRead: (suspend () -> NativeWritePreRead)? = null,
): NativeVerifiedWriteOutcome<T> {
    when (preRead?.let { check -> runPreRead(check) }) {
        NativeWritePreRead.Changed -> return NativeVerifiedWriteOutcome.NotApplied(
            null,
            NativeNotAppliedReason.PreconditionChanged,
            "This changed since you opened it, so nothing was saved. Refresh to load the current values, then edit again.",
        )
        NativeWritePreRead.Unverified -> return NativeVerifiedWriteOutcome.NotApplied(
            null,
            NativeNotAppliedReason.PreconditionUnverified,
            "The current values could not be checked, so nothing was saved. Try again when the connection is stable.",
        )
        NativeWritePreRead.Unchanged, null -> Unit
    }
    val sent = try {
        send()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Transport fault boundary: a thrown request has an unknown result.
        NativeWriteSendResult.Ambiguous("The app did not confirm this change ($actionLabel).")
    }
    if (sent is NativeWriteSendResult.Rejected) {
        return NativeVerifiedWriteOutcome.NotApplied(null, NativeNotAppliedReason.Rejected, sent.message)
    }
    val snapshot = try {
        readBack()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Read fault boundary: the state is simply not known.
        return NativeVerifiedWriteOutcome.Unknown(
            "The app may have applied this change ($actionLabel). Refresh before making another change.",
        )
    }
    return when (val verification = verify(snapshot)) {
        NativeWriteVerification.Confirmed -> NativeVerifiedWriteOutcome.Confirmed(snapshot)
        is NativeWriteVerification.Adjusted ->
            NativeVerifiedWriteOutcome.Adjusted(snapshot, verification.adjusted, verification.drifted, verification.message)
        is NativeWriteVerification.NotApplied -> NativeVerifiedWriteOutcome.NotApplied(
            snapshot,
            if (sent == NativeWriteSendResult.Accepted) NativeNotAppliedReason.NotAppliedAfterSuccess else NativeNotAppliedReason.NotAppliedAfterAmbiguous,
            (sent as? NativeWriteSendResult.Ambiguous)?.message ?: verification.message,
        )
        NativeWriteVerification.Unidentified -> NativeVerifiedWriteOutcome.Unknown(
            "The app's current state does not identify this record, so the change could not be confirmed.",
        )
    }
}

private suspend fun runPreRead(check: suspend () -> NativeWritePreRead): NativeWritePreRead = try {
    check()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    NativeWritePreRead.Unverified
}

/**
 * The immutable expectation a record write is verified against: the target identity, the exact
 * wire values the request carried for changed fields, the untouched baseline, and the values the
 * changed fields held before the edit.
 */
class NativeWriteExpectation(
    val targetId: String,
    val submitted: Map<String, JsonElement>,
    val untouchedBaseline: Map<String, JsonElement>,
    val previous: Map<String, JsonElement>,
    private val verifyTarget: ((NativeRecord) -> NativeWriteVerification)? = null,
    private val absentPrevious: Set<String> = emptySet(),
) {
    /**
     * Verifies [records] read back after the write. Exactly one record must carry [targetId]. A
     * changed field holding its previous value means not applied; holding neither value means the
     * server adjusted it; an untouched field that differs from the baseline drifted.
     */
    fun verify(records: List<NativeRecord>): NativeWriteVerification {
        val target = records.filter { it.id == targetId }.singleOrNull() ?: return NativeWriteVerification.Unidentified
        if (!target.actionSafeIdentity) return NativeWriteVerification.Unidentified
        val base = verifyFields(target)
        val extra = verifyTarget?.invoke(target) ?: return base
        return combineVerifications(base, extra)
    }

    private fun verifyFields(target: NativeRecord): NativeWriteVerification {
        val changed = submitted.filterKeys { field -> !sameWireValue(target.wireValue(field), submitted.getValue(field)) }
        val drifted = untouchedBaseline.keys.filterTo(linkedSetOf()) { field ->
            field.lowercase().filter(Char::isLetterOrDigit) !in SERVER_BOOKKEEPING_FIELDS &&
                !sameWireValue(target.wireValue(field), untouchedBaseline.getValue(field))
        }
        val reverted = changed.isNotEmpty() && changed.keys.all { field ->
            if (field in absentPrevious) target.wireValue(field) == null
            else previous[field]?.let { before -> sameWireValue(target.wireValue(field), before) } == true
        }
        return when {
            reverted && changed.size == submitted.size && drifted.isEmpty() ->
                NativeWriteVerification.NotApplied("The app accepted the change, but it was not applied.")
            changed.isEmpty() && drifted.isEmpty() -> NativeWriteVerification.Confirmed
            else -> NativeWriteVerification.Adjusted(adjusted = changed.keys, drifted = drifted)
        }
    }
}

/** The exact wire value of [field], with a field the read did not return treated as absent. */
private fun NativeRecord.wireValue(field: String): JsonElement? = wireValues.valueNamed(field)

/**
 * Bookkeeping the server maintains on every write. A changed modification time or revision is the
 * expected consequence of the write, not drift.
 */
private val SERVER_BOOKKEEPING_FIELDS = setOf(
    "modified", "lastmodified", "updated", "updatedat", "mtime", "etag", "revision", "lastedited", "lasteditedat",
)

private fun combineVerifications(first: NativeWriteVerification, second: NativeWriteVerification): NativeWriteVerification = when {
    first == NativeWriteVerification.Unidentified || second == NativeWriteVerification.Unidentified -> NativeWriteVerification.Unidentified
    first == NativeWriteVerification.Confirmed -> second
    second == NativeWriteVerification.Confirmed -> first
    first is NativeWriteVerification.NotApplied && second is NativeWriteVerification.NotApplied -> first
    else -> NativeWriteVerification.Adjusted(
        adjusted = (first as? NativeWriteVerification.Adjusted)?.adjusted.orEmpty() + (second as? NativeWriteVerification.Adjusted)?.adjusted.orEmpty(),
        drifted = (first as? NativeWriteVerification.Adjusted)?.drifted.orEmpty() + (second as? NativeWriteVerification.Adjusted)?.drifted.orEmpty(),
    )
}

/**
 * Wire values compare as JSON: strings by content, numbers by numeric value (so `5` equals
 * `5.0`), booleans and null exactly, arrays in order, and objects by key.
 */
internal fun sameWireValue(left: JsonElement?, right: JsonElement?): Boolean = when {
    left == null || right == null -> left == null && right == null
    left is JsonNull || right is JsonNull -> left is JsonNull && right is JsonNull
    left is JsonPrimitive && right is JsonPrimitive -> when {
        left.isString || right.isString -> left.isString == right.isString && left.content == right.content
        left.booleanOrNull != null || right.booleanOrNull != null -> left.booleanOrNull == right.booleanOrNull
        else -> left.contentOrNull == right.contentOrNull ||
            (left.contentOrNull?.toDoubleOrNull()?.let { value -> value == right.contentOrNull?.toDoubleOrNull() } == true)
    }
    left is JsonArray && right is JsonArray -> left.size == right.size && left.zip(right).all { (a, b) -> sameWireValue(a, b) }
    left is JsonObject && right is JsonObject -> left.keys == right.keys && left.keys.all { key -> sameWireValue(left[key], right[key]) }
    else -> false
}

/**
 * Asks the executor to verify a record write by reading [targetId] back through the contract's
 * route-family read. [previous] holds present edited values before the edit, [absentPrevious]
 * records edited fields omitted by the read, and [untouched] holds
 * every other field exactly as read. With [echoUntouched] those values travel verbatim in the body
 * (a full-echo replacement); otherwise they are only compared after the write.
 */
class NativeWriteReadBack(
    val targetId: String,
    val previous: Map<String, JsonElement>,
    val untouched: Map<String, JsonElement>,
    val echoUntouched: Boolean,
    /** An extra comparison for a field whose read shape differs from its write shape, such as a cell map. */
    val verifyTarget: ((NativeRecord) -> NativeWriteVerification)? = null,
    /** Body fields compared by [verifyTarget] instead of by their wire value. */
    val customFields: Set<String> = emptySet(),
    /** Edited body fields, including ones absent from the original read; never request context. */
    val editedFields: Set<String> = previous.keys,
    /** Absence is distinct from an explicit JSON null. */
    val absentPrevious: Set<String> = emptySet(),
    /** Compares custom-shaped fields with their complete baseline when restoring, not the edit. */
    val verifyRestoreTarget: ((NativeRecord) -> NativeWriteVerification)? = null,
)
