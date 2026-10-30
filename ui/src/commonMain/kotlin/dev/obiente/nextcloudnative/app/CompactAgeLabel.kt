package dev.obiente.nextcloudnative.app

import kotlin.time.Instant

/** Compact age for News articles and notifications; older items show their calendar date (UTC). */
internal fun compactAgeLabel(publishedEpochSeconds: Long, nowEpochSeconds: Long): String {
    val age = nowEpochSeconds - publishedEpochSeconds
    return when {
        age < 0L -> calendarDateLabel(publishedEpochSeconds)
        age < 60L -> "Just now"
        age < 3_600L -> "${age / 60L} min ago"
        age < 86_400L -> "${age / 3_600L} h ago"
        age < 2L * 86_400L -> "Yesterday"
        age < 7L * 86_400L -> "${age / 86_400L} days ago"
        else -> calendarDateLabel(publishedEpochSeconds)
    }
}

private fun calendarDateLabel(epochSeconds: Long): String =
    Instant.fromEpochSeconds(epochSeconds).toString().take(10)
