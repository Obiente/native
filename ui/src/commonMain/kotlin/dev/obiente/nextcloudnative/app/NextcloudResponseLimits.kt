package dev.obiente.nextcloudnative.app

class NextcloudResponseTooLargeException(
    val maximumBytes: Long,
) : IllegalStateException(
    "The server response is larger than the allowed ${formatNextcloudByteLimit(maximumBytes)} limit.",
) {
    init {
        require(maximumBytes > 0L)
    }
}

private fun formatNextcloudByteLimit(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
    bytes >= 1024 -> "${bytes / 1024} KiB"
    else -> "$bytes bytes"
}
