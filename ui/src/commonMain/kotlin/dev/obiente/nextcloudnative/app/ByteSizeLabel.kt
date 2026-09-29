package dev.obiente.nextcloudnative.app

/**
 * Formats a byte count for display with binary (1024-based) units and at most one decimal.
 *
 * Every screen that shows a file, transfer, cache, attachment, or limit size uses this so the
 * same quantity never appears as "1 MB" in one place and "1.0 MiB" in another. Whole values
 * drop the decimal ("4 KiB"), and a value that rounds up to the next unit is promoted
 * ("1 MiB" rather than "1024 KiB"). Negative input is not a size and reads as unknown.
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 0L) return UNKNOWN_BYTE_SIZE
    if (bytes < 1_024L) return "$bytes ${BYTE_SIZE_UNITS[0]}"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1_024.0 && unit < BYTE_SIZE_UNITS.lastIndex) {
        value /= 1_024.0
        unit += 1
    }
    var tenths = kotlin.math.round(value * 10.0).toLong()
    if (tenths >= 10_240L && unit < BYTE_SIZE_UNITS.lastIndex) {
        unit += 1
        tenths = kotlin.math.round(tenths / 1_024.0).toLong()
    }
    val whole = tenths / 10L
    val fraction = tenths % 10L
    val number = if (fraction == 0L) "$whole" else "$whole.$fraction"
    return "$number ${BYTE_SIZE_UNITS[unit]}"
}

/** Formats a size the server may omit; a missing size reads as unknown rather than zero. */
internal fun formatOptionalByteSize(bytes: Long?): String = bytes?.let(::formatByteSize) ?: UNKNOWN_BYTE_SIZE

private const val UNKNOWN_BYTE_SIZE = "Unknown size"

private val BYTE_SIZE_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB")
