package dev.obiente.nextcloudnative.app

import kotlin.time.Instant

/** Talk timestamps are epoch seconds. Missing or out-of-range values stay undisplayed. */
internal fun formatTalkMessageTimeUtc(epochSeconds: Long): String? {
    if (epochSeconds !in 1L..253_402_300_799L) return null
    return Instant.fromEpochSeconds(epochSeconds).toString().replace('T', ' ').take(16) + " UTC"
}
