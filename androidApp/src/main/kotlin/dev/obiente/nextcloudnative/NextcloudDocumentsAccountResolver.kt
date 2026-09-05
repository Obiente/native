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
) {
    fun resolvableAccounts(): List<ResolvedNextcloudDocumentsAccount> {
        val records = runCatching(listAccounts).getOrElse { return emptyList() }
        val unambiguousKeys = records
            .groupingBy(NextcloudAccountRecord::documentAccountKey)
            .eachCount()
            .filterValues { count -> count == 1 }
            .keys
        return records.mapNotNull { record ->
            record.takeIf { it.documentAccountKey() in unambiguousKeys }
                ?.let(::loadExactAccountSafely)
        }
    }

    fun requireDocument(documentId: String): ResolvedNextcloudDocument {
        val parsed = NextcloudDocumentIds.parse(documentId)
        val account = requireAccount(parsed.accountKey)
        return ResolvedNextcloudDocument(
            session = account.session,
            reference = NextcloudDocumentIds.requireForSession(documentId, account.session, account.incarnation),
        )
    }

    fun requireRoot(rootId: String): ResolvedNextcloudDocumentsAccount {
        val parsed = NextcloudDocumentIds.parseProviderRootId(rootId)
        val account = requireAccount(parsed.accountKey)
        require(account.incarnation == parsed.incarnation) { "The document root belongs to an earlier account." }
        return account
    }

    private fun requireAccount(accountKey: String): ResolvedNextcloudDocumentsAccount {
        val matches = listAccounts().filter { record -> record.documentAccountKey() == accountKey }
        require(matches.size == 1) { "The document account is missing or ambiguous." }
        return requireNotNull(loadExactAccount(matches.single())) {
            "The document account credentials are unavailable."
        }
    }

    private fun loadExactAccountSafely(record: NextcloudAccountRecord): ResolvedNextcloudDocumentsAccount? =
        runCatching { loadExactAccount(record) }.getOrNull()

    private fun loadExactAccount(record: NextcloudAccountRecord): ResolvedNextcloudDocumentsAccount? {
        val session = loadSession(record.id)?.takeIf { candidate ->
            candidate.accountRecord() == record && NextcloudDocumentIds.accountKey(candidate) == record.documentAccountKey()
        } ?: return null
        return ResolvedNextcloudDocumentsAccount(session, loadIncarnation(record.id.storageKey))
    }
}

private fun NextcloudAccountRecord.documentAccountKey(): String =
    NextcloudDocumentIds.accountKey(serverUrl, loginName)
