package dev.obiente.nextcloudnative.app

import java.nio.file.Path

/**
 * Formats an absolute Windows path for direct Win32 and Cloud Files calls.
 *
 * Java NIO adds the extended-length prefix internally, but JNA calls receive the plain string
 * and fail with ERROR_PATH_NOT_FOUND once a path reaches MAX_PATH. Deep photo libraries under a
 * sync root routinely cross that limit, so every per-entry native call uses this form.
 */
internal fun windowsWin32Path(absolutePath: String): String {
    val path = absolutePath.replace('/', '\\')
    require(path.isWindowsAbsolutePath()) { "A Win32 path must be absolute." }
    return when {
        path.startsWith(EXTENDED_LENGTH_PREFIX) || path.startsWith(DEVICE_PREFIX) -> path
        path.startsWith("\\\\") -> EXTENDED_LENGTH_UNC_PREFIX + path.removePrefix("\\\\")
        else -> EXTENDED_LENGTH_PREFIX + path
    }
}

internal fun Path.toWindowsWin32Path(): String = windowsWin32Path(toAbsolutePath().normalize().toString())

private fun String.isWindowsAbsolutePath(): Boolean =
    startsWith("\\\\") || (length >= 3 && this[0].isLetter() && this[1] == ':' && this[2] == '\\')

private const val EXTENDED_LENGTH_PREFIX = "\\\\?\\"
private const val EXTENDED_LENGTH_UNC_PREFIX = "\\\\?\\UNC\\"
private const val DEVICE_PREFIX = "\\\\.\\"
