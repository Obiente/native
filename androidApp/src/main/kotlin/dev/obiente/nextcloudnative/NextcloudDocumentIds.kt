package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.security.MessageDigest
import java.util.Base64

internal data class NextcloudDocumentReference(
    val accountKey: String,
    val incarnation: NextcloudDocumentIncarnation,
    val path: String,
) {
    val isRoot: Boolean get() = path.isEmpty()
}

internal sealed interface NextcloudDocumentIncarnation {
    data object Legacy : NextcloudDocumentIncarnation

    data class Versioned(val value: String) : NextcloudDocumentIncarnation {
        init {
            require(VALUE_PATTERN.matches(value)) { "Invalid document incarnation." }
        }
    }

    companion object {
        private val VALUE_PATTERN = Regex("[0-9a-f]{32}")
    }
}

internal object NextcloudDocumentIds {
    private const val LEGACY_PREFIX = "nc1"
    private const val VERSIONED_PREFIX = "nc2"
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

    fun providerRootId(session: NextcloudSession, incarnation: NextcloudDocumentIncarnation): String =
        when (incarnation) {
            NextcloudDocumentIncarnation.Legacy -> accountKey(session)
            is NextcloudDocumentIncarnation.Versioned -> "${accountKey(session)}:${incarnation.value}"
        }

    fun rootId(session: NextcloudSession, incarnation: NextcloudDocumentIncarnation): String =
        rootId(accountKey(session), incarnation)

    fun rootId(accountKey: String, incarnation: NextcloudDocumentIncarnation): String {
        require(accountKeyPattern.matches(accountKey)) { "Invalid document account." }
        return when (incarnation) {
            NextcloudDocumentIncarnation.Legacy -> "$LEGACY_PREFIX:$accountKey:"
            is NextcloudDocumentIncarnation.Versioned -> "$VERSIONED_PREFIX:$accountKey:${incarnation.value}:"
        }
    }

    fun documentId(
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
        path: String,
    ): String {
        val normalizedPath = normalizePath(path)
        val encodedPath = encoder.encodeToString(normalizedPath.encodeToByteArray())
        return when (incarnation) {
            NextcloudDocumentIncarnation.Legacy -> "$LEGACY_PREFIX:${accountKey(session)}:$encodedPath"
            is NextcloudDocumentIncarnation.Versioned ->
                "$VERSIONED_PREFIX:${accountKey(session)}:${incarnation.value}:$encodedPath"
        }
    }

    fun parse(documentId: String): NextcloudDocumentReference {
        val parts = documentId.split(':')
        val incarnation = when {
            parts.size == 3 && parts[0] == LEGACY_PREFIX -> NextcloudDocumentIncarnation.Legacy
            parts.size == 4 && parts[0] == VERSIONED_PREFIX ->
                NextcloudDocumentIncarnation.Versioned(parts[2])
            else -> throw IllegalArgumentException("Unsupported document ID.")
        }
        val accountKey = parts[1]
        require(accountKeyPattern.matches(accountKey)) { "Invalid document account." }
        val encodedPath = parts.last()
        val decodedBytes = runCatching { decoder.decode(encodedPath) }
            .getOrElse { throw IllegalArgumentException("Invalid document path.", it) }
        require(encoder.encodeToString(decodedBytes) == encodedPath) { "Document path encoding is not canonical." }
        val decodedPath = runCatching { decodedBytes.decodeToString(throwOnInvalidSequence = true) }
            .getOrElse { throw IllegalArgumentException("Document path is not valid UTF-8.", it) }
        val normalizedPath = normalizePath(decodedPath)
        require(decodedPath == normalizedPath) { "Document path is not canonical." }
        return NextcloudDocumentReference(accountKey, incarnation, normalizedPath)
    }

    fun requireForSession(
        documentId: String,
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
    ): NextcloudDocumentReference =
        parse(documentId).also { reference ->
            require(reference.accountKey == accountKey(session)) { "Document belongs to another account." }
            require(reference.incarnation == incarnation) { "Document belongs to an earlier account incarnation." }
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
