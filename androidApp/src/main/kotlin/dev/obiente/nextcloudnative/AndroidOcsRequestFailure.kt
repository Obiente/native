package dev.obiente.nextcloudnative

/** Typed status from the OCS transport boundary; never classify display text. */
internal class AndroidOcsRequestFailure(val status: Int) : IllegalStateException(
    "Nextcloud API request failed (HTTP $status).",
)
