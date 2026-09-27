package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord

internal data class ResolvedNextcloudDocument(
    val session: NextcloudSession,
    val reference: NextcloudDocumentReference,
)

internal data class ResolvedNextcloudDocumentsAccount(
    val session: NextcloudSession,
    val incarnation: NextcloudDocumentIncarnation,
)

/** Resolves provider identities without changing or depending on the selected account. */
internal class NextcloudDocumentsAccountResolver(
    private val listAccounts: () -> List<NextcloudAccountRecord>,
    private val loadSession: (NextcloudAccountId) -> NextcloudSession?,
    private val loadIncarnation: (String) -> NextcloudDocumentIncarnation,
    private val loadLegacyAliases: (NextcloudAccountId) -> Set<String> = { emptySet() },
) {
    fun resolvableAccounts(): List<ResolvedNextcloudDocumentsAccount> {
        val records = runCatching(listAccounts).getOrElse { return emptyList() }
        val unambiguousKeys = records
            .groupingBy { record -> record.id.storageKey.take(DOCUMENT_ACCOUNT_KEY_CHARACTERS) }
            .eachCount()
            .filterValues { count -> count == 1 }
            .keys
        return records.mapNotNull { record ->
            record.takeIf { it.canonicalDocumentAccountKey() in unambiguousKeys }
                ?.let(::loadExactAccountSafely)
        }
    }

    fun requireDocument(documentId: String): ResolvedNextcloudDocument {
        val parsed = NextcloudDocumentIds.parse(documentId)
        val account = requireAccount(parsed.accountKey)
        return ResolvedNextcloudDocument(
            session = account.session,
            reference = requireReference(documentId, account.session, account.incarnation),
        )
    }

    fun requireReference(
        documentId: String,
        session: NextcloudSession,
        incarnation: NextcloudDocumentIncarnation,
    ): NextcloudDocumentReference {
        val key = NextcloudDocumentIds.parse(documentId).accountKey
        val aliases = if (key in session.accountRecord().documentAccountKeys()) emptySet()
            else verifiedLegacyAliases(session.accountId)
        return NextcloudDocumentIds.requireForSession(documentId, session, incarnation, aliases)
    }

    fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = requireDocument(parentDocumentId)
        val child = requireReference(documentId, parent.session, parent.reference.incarnation)
        if (child.isRoot || parent.reference.path == child.path) return false
        return parent.reference.isRoot || child.path.startsWith(parent.reference.path + "/")
    }

    private fun verifiedLegacyAliases(accountId: NextcloudAccountId): Set<String> = try {
        loadLegacyAliases(accountId)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Optional unreadable metadata cannot authorize an old identity or block current IDs.
        emptySet()
    }

    fun requireRoot(rootId: String): ResolvedNextcloudDocumentsAccount {
        val parsed = NextcloudDocumentIds.parseProviderRootId(rootId)
        val account = requireAccount(parsed.accountKey)
        require(account.incarnation == parsed.incarnation) { "The document root belongs to an earlier account." }
        return account
    }

    fun diagnosticSessionForAccountKey(accountKey: String): NextcloudSession = requireAccount(accountKey).session

    private fun requireAccount(accountKey: String): ResolvedNextcloudDocumentsAccount {
        val matches = listAccounts().filter { record -> accountKey in record.documentAccountKeys() || accountKey in verifiedLegacyAliases(record.id) }
        require(matches.size == 1) { "The document account is missing or ambiguous." }
        return requireNotNull(loadExactAccount(matches.single())) {
            "The document account credentials are unavailable."
        }
    }

    private fun loadExactAccountSafely(record: NextcloudAccountRecord): ResolvedNextcloudDocumentsAccount? =
        runCatching { loadExactAccount(record) }.getOrNull()

    private fun loadExactAccount(record: NextcloudAccountRecord): ResolvedNextcloudDocumentsAccount? {
        val session = loadSession(record.id)?.takeIf { candidate ->
            candidate.accountRecord() == record
        } ?: return null
        return ResolvedNextcloudDocumentsAccount(session, loadIncarnation(record.id.storageKey))
    }
}

internal fun nextcloudDocumentsAccountResolver(
    services: AndroidNextcloudServices,
    incarnations: AndroidDocumentProviderIncarnationStore,
    aliases: AndroidDocumentLegacyAliases,
) = NextcloudDocumentsAccountResolver(
    services::listAccounts,
    services::loadSession,
    incarnations::activeIncarnation,
    { id -> aliases.read(id.storageKey, incarnations.activeIncarnation(id.storageKey)) },
)

private fun NextcloudAccountRecord.canonicalDocumentAccountKey(): String =
    id.storageKey.take(DOCUMENT_ACCOUNT_KEY_CHARACTERS)

private fun NextcloudAccountRecord.documentAccountKeys(): Set<String> =
    setOf(canonicalDocumentAccountKey(), NextcloudDocumentIds.accountKey(serverUrl, loginName))

private const val DOCUMENT_ACCOUNT_KEY_CHARACTERS = 32
