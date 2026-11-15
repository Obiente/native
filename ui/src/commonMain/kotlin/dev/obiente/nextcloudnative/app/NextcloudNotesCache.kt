package dev.obiente.nextcloudnative.app

/** One notes app on one account; two notes apps never share cached records. */
private data class NotesCacheScope(val account: NextcloudAccountId, val appId: String)

/** Small process-local cache used for stale-while-revalidate notes screens. */
internal class NextcloudNotesCache(
    private val gate: AccountPrivateMemoryGate = AccountPrivateMemoryGate(),
) {
    private val noteLists = mutableMapOf<NotesCacheScope, List<NextcloudNote>>()
    private val noteListEtags = mutableMapOf<NotesCacheScope, String>()
    private val noteDetails = mutableMapOf<Pair<NotesCacheScope, Long>, NextcloudNote>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? = gate.producer(session.accountId.storageKey)

    fun list(session: NextcloudSession, appId: String): List<NextcloudNote>? =
        gate.read(session.accountId.storageKey, null) { noteLists[NotesCacheScope(session.accountId, appId)] }

    fun listEtag(session: NextcloudSession, appId: String): String? =
        gate.read(session.accountId.storageKey, null) { noteListEtags[NotesCacheScope(session.accountId, appId)] }

    fun detail(session: NextcloudSession, appId: String, noteId: Long): NextcloudNote? =
        gate.read(session.accountId.storageKey, null) { noteDetails[NotesCacheScope(session.accountId, appId) to noteId] }

    fun storeList(
        session: NextcloudSession,
        appId: String,
        notes: List<NextcloudNote>,
        producer: AccountPrivateMemoryProducer?,
        etag: String? = null,
    ) {
        val account = NotesCacheScope(session.accountId, appId)
        gate.mutate(account.account.storageKey, producer) {
            noteLists[account] = notes
            etag?.takeIf(String::isNotBlank)?.let { noteListEtags[account] = it }
                ?: noteListEtags.remove(account)
            notes.filter { it.content != null }.forEach { noteDetails[account to it.id] = it }
        }
    }

    fun storeDetail(
        session: NextcloudSession,
        appId: String,
        note: NextcloudNote,
        producer: AccountPrivateMemoryProducer?,
    ) {
        val account = NotesCacheScope(session.accountId, appId)
        gate.mutate(account.account.storageKey, producer) {
            noteDetails[account to note.id] = note
            noteLists[account]?.let { listedNotes ->
                noteLists[account] = listedNotes.map { listed ->
                    if (listed.id == note.id) note.copy(content = null) else listed
                }
            }
        }
    }

    fun remove(
        session: NextcloudSession,
        appId: String,
        noteId: Long,
        producer: AccountPrivateMemoryProducer?,
    ) {
        val account = NotesCacheScope(session.accountId, appId)
        gate.mutate(account.account.storageKey, producer) {
            noteDetails.remove(account to noteId)
            noteLists[account]?.let { listedNotes ->
                noteLists[account] = listedNotes.filterNot { note -> note.id == noteId }
                noteListEtags.remove(account)
            }
        }
    }

    fun retireAccount(accountStorageKey: String) = gate.retireAccount(accountStorageKey) {
        purgeRetiredAccount(accountStorageKey)
    }

    fun activateAccount(accountStorageKey: String) = gate.activateAccount(accountStorageKey)

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        noteLists.keys.removeAll { scope -> scope.account.storageKey == accountStorageKey }
        noteListEtags.keys.removeAll { scope -> scope.account.storageKey == accountStorageKey }
        noteDetails.keys.removeAll { (scope, _) -> scope.account.storageKey == accountStorageKey }
    }

}

internal val sharedNextcloudNotesCache = NextcloudNotesCache(sharedAccountPrivateMemoryGate)
