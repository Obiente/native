package dev.obiente.nextcloudnative.app

import java.security.MessageDigest

internal actual fun previewCacheDigest(session: NextcloudSession): String {
    val identity = session.serverUrl.trimEnd('/') + "\u0000" + session.loginName
    return MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
