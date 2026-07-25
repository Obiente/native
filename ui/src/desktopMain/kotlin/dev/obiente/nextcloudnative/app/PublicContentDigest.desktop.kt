package dev.obiente.nextcloudnative.app

import java.security.MessageDigest

internal actual fun publicContentSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
