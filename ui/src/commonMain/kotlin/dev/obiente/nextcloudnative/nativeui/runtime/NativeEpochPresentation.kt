package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import kotlin.time.Instant

/** Epoch units must be declared by a reviewed presentation adapter, never guessed from magnitude. */
internal fun FieldSpec.formatDeclaredEpochSeconds(value: String): String? {
    if (kind != FieldKind.integer || format != "unix-seconds") return null
    val seconds = value.toLongOrNull()?.takeIf { it in 0..253_402_300_799L } ?: return null
    return Instant.fromEpochSeconds(seconds).toString().replace('T', ' ').take(16) + " UTC"
}
