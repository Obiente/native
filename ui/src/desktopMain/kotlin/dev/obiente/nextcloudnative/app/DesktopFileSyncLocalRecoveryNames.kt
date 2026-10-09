package dev.obiente.nextcloudnative.app

import java.nio.file.Path
import java.util.UUID

/** Local artifacts the desktop sync engine owns while it downloads or replaces a destination. */
internal enum class DesktopLocalRecoveryKind(val marker: String) {
    Download(".nextcloud-native-download-"),
    Backup(".nextcloud-native-backup-"),
}

/** A parsed `.<destination><marker><uuid>` sibling of [destinationName]. */
internal data class DesktopLocalRecoveryName(
    val kind: DesktopLocalRecoveryKind,
    val destinationName: String,
    val token: String,
)

/** The owned sibling name for [destinationName]; [token] must be a UUID so lookalikes are kept. */
internal fun desktopLocalRecoveryName(
    kind: DesktopLocalRecoveryKind,
    destinationName: String,
    token: String,
): String = ".$destinationName${kind.marker}$token"

/** The owned sibling path of this destination. */
internal fun Path.desktopLocalRecoverySibling(kind: DesktopLocalRecoveryKind, token: String): Path =
    resolveSibling(desktopLocalRecoveryName(kind, fileName.toString(), token))

/** Parses an owned artifact name; user files that only resemble one return null. */
internal fun parseDesktopLocalRecoveryName(name: String): DesktopLocalRecoveryName? {
    if (!name.startsWith('.')) return null
    return DesktopLocalRecoveryKind.entries.firstNotNullOfOrNull { kind ->
        val markerIndex = name.lastIndexOf(kind.marker)
        if (markerIndex <= 1) return@firstNotNullOfOrNull null
        val token = name.substring(markerIndex + kind.marker.length)
        if (runCatching { UUID.fromString(token) }.isFailure) return@firstNotNullOfOrNull null
        val destinationName = name.substring(1, markerIndex)
        destinationName.takeIf(String::isNotBlank)?.let { DesktopLocalRecoveryName(kind, it, token) }
    }
}
