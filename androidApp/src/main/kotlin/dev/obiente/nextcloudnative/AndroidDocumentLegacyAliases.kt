package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudSession

/** Keeps pre-canonical document IDs valid only within the same account incarnation. */
internal class AndroidDocumentLegacyAliases(
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
) {
    constructor(context: Context) : this(
        read = { key -> context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(key, null) },
        write = { key, value ->
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(key, value).commit()
        },
    )

    fun read(accountKey: String, incarnation: NextcloudDocumentIncarnation): Set<String> = synchronized(LOCK) {
        require(ACCOUNT.matches(accountKey))
        val encoded = read(accountKey) ?: return@synchronized emptySet()
        if (encoded == "removed") return@synchronized emptySet()
        require(encoded.length <= 2200) { "Saved document aliases exceed their limit." }
        val fields = encoded.split('\n')
        require(fields.size in 2..65 && fields.drop(1).all(ALIAS::matches)) { "Saved document aliases are invalid." }
        require(fields.first() == "legacy" || ALIAS.matches(fields.first())) { "Saved document alias version is invalid." }
        if (fields.first() != incarnation.key()) emptySet() else fields.drop(1).toSet()
    }

    // Optional alias recovery never authorizes unknown identities. Ordinary lookup
    // stays strict; only verified session identities may be added after recovery.
    fun readVerified(accountKey: String, incarnation: NextcloudDocumentIncarnation): Set<String> {
        require(ACCOUNT.matches(accountKey))
        return try {
            read(accountKey, incarnation)
        } catch (_: ClassCastException) {
            emptySet()
        } catch (_: IllegalArgumentException) {
            emptySet()
        }
    }

    fun remember(session: NextcloudSession, previous: NextcloudSession?, incarnation: NextcloudDocumentIncarnation) {
        synchronized(LOCK) {
            require(previous == null || previous.accountId == session.accountId)
            val accountKey = session.accountId.storageKey
            val aliases = readVerified(accountKey, incarnation) + listOfNotNull(previous, session).map(NextcloudDocumentIds::accountKey)
            check(aliases.size <= 64) { "Too many saved document identity aliases." }
            check(write(accountKey, (listOf(incarnation.key()) + aliases.sorted()).joinToString("\n"))) {
                "Could not preserve existing document identities."
            }
        }
    }

    fun clear(accountKey: String) = synchronized(LOCK) {
        require(ACCOUNT.matches(accountKey))
        check(write(accountKey, "removed")) { "Could not retire saved document aliases." }
    }

    private fun NextcloudDocumentIncarnation.key() = when (this) {
        NextcloudDocumentIncarnation.Legacy -> "legacy"
        is NextcloudDocumentIncarnation.Versioned -> value
    }

    private companion object {
        const val STORE = "documents-provider-legacy-aliases-v1"
        val LOCK = Any()
        val ACCOUNT = Regex("[0-9a-f]{64}")
        val ALIAS = Regex("[0-9a-f]{32}")
    }
}
