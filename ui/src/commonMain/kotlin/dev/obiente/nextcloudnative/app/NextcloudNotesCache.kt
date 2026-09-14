package dev.obiente.nextcloudnative.app

/** Small process-local cache used for stale-while-revalidate Notes screens. */
internal class NextcloudNotesCache(
    private val gate: AccountPrivateMemoryGate = AccountPrivateMemoryGate(),
) {
    private val noteLists = mutableMapOf<NextcloudAccountId, List<NextcloudNote>>()
    private val noteListEtags = mutableMapOf<NextcloudAccountId, String>()
    private val noteDetails = mutableMapOf<Pair<NextcloudAccountId, Long>, NextcloudNote>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? = gate.producer(session.accountId.storageKey)

    fun list(session: NextcloudSession): List<NextcloudNote>? =
        gate.read(session.accountId.storageKey, null) { noteLists[session.accountId] }

    fun listEtag(session: NextcloudSession): String? =
        gate.read(session.accountId.storageKey, null) { noteListEtags[session.accountId] }

    fun detail(session: NextcloudSession, noteId: Long): NextcloudNote? =
        gate.read(session.accountId.storageKey, null) { noteDetails[session.accountId to noteId] }

    fun storeList(
        session: NextcloudSession,
        notes: List<NextcloudNote>,
        producer: AccountPrivateMemoryProducer?,
        etag: String? = null,
    ) {
        val account = session.accountId
        gate.mutate(account.storageKey, producer) {
            noteLists[account] = notes
            etag?.takeIf(String::isNotBlank)?.let { noteListEtags[account] = it }
                ?: noteListEtags.remove(account)
            notes.filter { it.content != null }.forEach { noteDetails[account to it.id] = it }
        }
    }

    fun storeDetail(
        session: NextcloudSession,
        note: NextcloudNote,
        producer: AccountPrivateMemoryProducer?,
    ) {
        val account = session.accountId
        gate.mutate(account.storageKey, producer) {
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
        noteId: Long,
        producer: AccountPrivateMemoryProducer?,
    ) {
        val account = session.accountId
        gate.mutate(account.storageKey, producer) {
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
        noteLists.keys.removeAll { account -> account.storageKey == accountStorageKey }
        noteListEtags.keys.removeAll { account -> account.storageKey == accountStorageKey }
        noteDetails.keys.removeAll { (account, _) -> account.storageKey == accountStorageKey }
    }

}

internal val sharedNextcloudNotesCache = NextcloudNotesCache(sharedAccountPrivateMemoryGate)
