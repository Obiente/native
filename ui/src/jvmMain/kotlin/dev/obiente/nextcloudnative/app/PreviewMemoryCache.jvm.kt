package dev.obiente.nextcloudnative.app

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal actual fun deriveNextcloudAccountId(serverUrl: String, loginName: String): NextcloudAccountId {
    val accountServer = canonicalAccountServerUrl(serverUrl)
    val identity = accountServer + "\u0000" + loginName
    return MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .let(::NextcloudAccountId)
}

internal actual fun previewCacheDigest(session: NextcloudSession): String = session.accountId.storageKey

private fun canonicalAccountServerUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull()
    requireNotNull(url) { "The account server address is invalid." }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "The account server address contains unsupported URL components."
    }
    return url.toString().trimEnd('/')
}
