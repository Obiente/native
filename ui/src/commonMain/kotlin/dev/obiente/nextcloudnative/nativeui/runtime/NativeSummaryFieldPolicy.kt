package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec

/*
 * Decides which fields may appear in compact record summaries (card subtitles and inline facts).
 *
 * A summary has room for a few values and shows them without much context, so a value must be
 * readable on its own. Fields excluded here stay available in the record's Details section; this
 * policy never hides data, it only keeps unreadable values out of the most prominent slots.
 */

/**
 * An integer named as a point in time ("lastUpdated", "lockedUntil", "created_at") whose epoch
 * unit no reviewed adapter declared. Its raw number is unreadable, and guessing the unit from its
 * magnitude is not allowed, so it is not summary material.
 */
internal fun FieldSpec.isUndeclaredTimestampInteger(): Boolean {
    if (kind != FieldKind.integer || format == "unix-seconds") return false
    val words = id.summaryWords()
    val last = words.lastOrNull() ?: return false
    return last in TIMESTAMP_FINAL_WORDS || words.joinToString("") in TIMESTAMP_COMPOUND_IDS
}

/** An integer status, state, type, or access code without declared labels is an opaque number. */
internal fun FieldSpec.isUnlabeledIntegerCode(): Boolean {
    if (kind != FieldKind.integer || !enumLabels.isNullOrEmpty()) return false
    val words = id.summaryWords()
    val last = words.lastOrNull() ?: return false
    return last in INTEGER_CODE_FINAL_WORDS || words.joinToString("").endsWith("accesslevel")
}

/** Response bookkeeping, locking, and integrity values that describe the payload, not the record. */
internal fun FieldSpec.isInternalBookkeepingField(): Boolean {
    val normalized = id.lowercase().filter(Char::isLetterOrDigit)
    return normalized in INTERNAL_BOOKKEEPING_IDS ||
        normalized.startsWith("locked") ||
        "trash" in normalized ||
        normalized.endsWith("sort") ||
        (kind == FieldKind.boolean && normalized.endsWith("disabled"))
}

/** "2 items" names neither what is counted nor why it matters, so it is not a subtitle. */
internal fun String.isUnlabeledItemCount(): Boolean {
    val (count, noun) = trim().split(' ').takeIf { it.size == 2 } ?: return false
    return count.all(Char::isDigit) && count.isNotEmpty() && noun.lowercase() in setOf("item", "items")
}

internal fun FieldSpec.isUnreadableSummaryField(): Boolean =
    isUndeclaredTimestampInteger() || isUnlabeledIntegerCode() || isInternalBookkeepingField()

/**
 * Removes count prefixes that only make sense inside an API ("Nb Bills" becomes "Bills"), so a
 * summary such as "Bills: 3" reads naturally. Other labels are returned unchanged.
 */
internal fun nativeSummaryLabel(label: String): String {
    val trimmed = label.trim()
    val prefix = COUNT_LABEL_PREFIXES.firstOrNull { prefix -> trimmed.startsWith(prefix, ignoreCase = true) }
        ?: return trimmed
    val rest = trimmed.substring(prefix.length).trim()
    return if (rest.isEmpty()) trimmed else rest.replaceFirstChar(Char::uppercaseChar)
}

/** "Total" plus a measure label, without repeating "total" when the label already starts with it. */
internal fun nativeTotalLabel(measureLabel: String): String {
    val lowered = measureLabel.trim().lowercase()
    return if (lowered.startsWith("total")) lowered.replaceFirstChar(Char::uppercaseChar) else "Total $lowered"
}

private fun String.summaryWords(): List<String> = substringAfterLast('.')
    .replace(CAMEL_CASE_BOUNDARY, "$1 $2")
    .lowercase()
    .split(' ', '_', '-')
    .filter(String::isNotBlank)

private val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
private val TIMESTAMP_FINAL_WORDS = setOf(
    "at", "until", "since", "timestamp", "date", "datetime",
    "created", "updated", "modified", "changed", "expires", "expiry", "expiration", "mtime", "ctime",
)
private val TIMESTAMP_COMPOUND_IDS = setOf("lastchanged", "lastmodified", "lastupdated", "lastactivity", "lastseen")
private val INTEGER_CODE_FINAL_WORDS = setOf("state", "status", "type", "kind", "mode", "permissions", "permission")
private val INTERNAL_BOOKKEEPING_IDS = setOf(
    "hash", "guidhash", "fingerprint", "contenthash", "checksum", "partial", "opened", "ordering",
    "nonce", "revision", "rev",
)
private val COUNT_LABEL_PREFIXES = listOf("Number of ", "Nb ", "Num ", "No. of ")
