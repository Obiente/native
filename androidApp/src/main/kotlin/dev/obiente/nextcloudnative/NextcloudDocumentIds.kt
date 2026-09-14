package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.security.MessageDigest
import java.util.Base64

internal data class NextcloudDocumentReference(
    val accountKey: String,
    val path: String,
) {
    val isRoot: Boolean get() = path.isEmpty()
}

internal object NextcloudDocumentIds {
    private const val PREFIX = "nc1"
    private val accountKeyPattern = Regex("[0-9a-f]{32}")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun accountKey(session: NextcloudSession): String {
        return accountKey(session.serverUrl, session.loginName)
    }

    fun accountKey(serverUrl: String, loginName: String): String {
        return accountDigest(serverUrl, loginName)
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** Full digest for private caches which require a canonical SHA-256 directory key. */
    fun cacheAccountId(session: NextcloudSession): String =
        accountDigest(session.serverUrl, session.loginName)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun rootId(session: NextcloudSession): String = rootId(accountKey(session))

    fun rootId(accountKey: String): String {
        require(accountKeyPattern.matches(accountKey)) { "Invalid document account." }
        return "$PREFIX:$accountKey:"
    }

    fun documentId(session: NextcloudSession, path: String): String =
        documentId(accountKey(session), path)

    fun documentId(accountKey: String, path: String): String {
        require(accountKeyPattern.matches(accountKey)) { "Invalid document account." }
        val normalizedPath = normalizePath(path)
        val encodedPath = encoder.encodeToString(normalizedPath.encodeToByteArray())
        return "$PREFIX:$accountKey:$encodedPath"
    }

    fun parse(documentId: String): NextcloudDocumentReference {
        val parts = documentId.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == PREFIX) { "Unsupported document ID." }
        val accountKey = parts[1]
        require(accountKeyPattern.matches(accountKey)) { "Invalid document account." }
        val decodedBytes = runCatching { decoder.decode(parts[2]) }
            .getOrElse { throw IllegalArgumentException("Invalid document path.", it) }
        require(encoder.encodeToString(decodedBytes) == parts[2]) { "Document path encoding is not canonical." }
        val decodedPath = runCatching { decodedBytes.decodeToString(throwOnInvalidSequence = true) }
            .getOrElse { throw IllegalArgumentException("Document path is not valid UTF-8.", it) }
        val normalizedPath = normalizePath(decodedPath)
        require(decodedPath == normalizedPath) { "Document path is not canonical." }
        return NextcloudDocumentReference(accountKey, normalizedPath)
    }

    fun requireForSession(documentId: String, session: NextcloudSession): NextcloudDocumentReference =
        parse(documentId).also { reference ->
            require(reference.accountKey == accountKey(session)) { "Document belongs to another account." }
        }

    private fun accountDigest(serverUrl: String, loginName: String): ByteArray {
        val identity = serverUrl.trimEnd('/') + "\n" + loginName
        return MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray())
    }

    fun parentPath(path: String): String = normalizePath(path).substringBeforeLast('/', missingDelimiterValue = "")

    private fun normalizePath(path: String): String {
        require('\u0000' !in path) { "Document path contains a null character." }
        val segments = path.trim('/').split('/').filter(String::isNotEmpty)
        require(segments.none { it == "." || it == ".." }) { "Relative path segments are not allowed." }
        return segments.joinToString("/")
    }
}
