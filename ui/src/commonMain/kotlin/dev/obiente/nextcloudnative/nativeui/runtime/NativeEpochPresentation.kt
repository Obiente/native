package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import kotlin.time.Instant

/**
 * True when the contract (or a reviewed presentation adapter) declares this integer as Unix epoch
 * seconds through its format. Epoch units are never guessed from a value's magnitude or name.
 */
internal fun FieldSpec.declaresEpochSeconds(): Boolean = kind == FieldKind.integer && format in EPOCH_SECONDS_FORMATS

internal fun FieldSpec.formatDeclaredEpochSeconds(value: String): String? {
    if (!declaresEpochSeconds()) return null
    val seconds = value.trim().toLongOrNull()?.takeIf { it in 0..253_402_300_799L } ?: return null
    return Instant.fromEpochSeconds(seconds).toString().replace('T', ' ').take(16) + " UTC"
}

private val EPOCH_SECONDS_FORMATS = setOf("unix-seconds", "unix-timestamp", "unix-time", "unixtime", "epoch-seconds")
