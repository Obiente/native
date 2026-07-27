package dev.obiente.nextcloudnative.app

data class DeckLocalDateTime(
    val date: String,
    val time: String,
)

/**
 * Converts an absolute Deck timestamp to the device timezone for native editing.
 *
 * Invalid server values return null so callers can preserve the original value instead of
 * guessing a timezone or changing the represented instant.
 */
internal expect fun deckInstantToLocalDateTime(value: String): DeckLocalDateTime?

/**
 * Converts a validated local date and time selected by the user into an absolute UTC timestamp.
 */
internal expect fun deckLocalDateTimeToInstant(date: String, time: String): String

/** Returns a local calendar date relative to today for quick native due-date choices. */
internal expect fun deckLocalDatePlusDays(days: Int): String

/**
 * Presents a Deck instant in the device timezone without discarding an unrecognized server value.
 */
internal fun deckInstantDisplayLabel(value: String): String =
    deckInstantToLocalDateTime(value)?.let { local ->
        "${local.date} ${local.time}"
    } ?: value
