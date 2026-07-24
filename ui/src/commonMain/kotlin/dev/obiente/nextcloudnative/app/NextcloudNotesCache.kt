package dev.obiente.nextcloudnative.app

/** Small process-local cache used for stale-while-revalidate Notes screens. */
internal class NextcloudNotesCache {
    private val noteLists = mutableMapOf<String, List<NextcloudNote>>()
    private val noteListEtags = mutableMapOf<String, String>()
    private val noteDetails = mutableMapOf<Pair<String, Long>, NextcloudNote>()

    fun list(session: NextcloudSession): List<NextcloudNote>? = noteLists[session.cacheKey()]

    fun listEtag(session: NextcloudSession): String? = noteListEtags[session.cacheKey()]

    fun detail(session: NextcloudSession, noteId: Long): NextcloudNote? =
        noteDetails[session.cacheKey() to noteId]

    fun storeList(session: NextcloudSession, notes: List<NextcloudNote>, etag: String? = null) {
        val account = session.cacheKey()
        noteLists[account] = notes
        etag?.takeIf(String::isNotBlank)?.let { noteListEtags[account] = it }
            ?: noteListEtags.remove(account)
        notes.filter { it.content != null }.forEach { noteDetails[account to it.id] = it }
    }

    fun storeDetail(session: NextcloudSession, note: NextcloudNote) {
        val account = session.cacheKey()
        noteDetails[account to note.id] = note
        noteLists[account] = noteLists[account]?.map { listed ->
            if (listed.id == note.id) note.copy(content = null) else listed
        } ?: return
    }

    fun remove(session: NextcloudSession, noteId: Long) {
        val account = session.cacheKey()
        noteDetails.remove(account to noteId)
        noteLists[account] = noteLists[account]?.filterNot { note -> note.id == noteId } ?: return
        noteListEtags.remove(account)
    }

    private fun NextcloudSession.cacheKey(): String =
        serverUrl.trim().trimEnd('/').lowercase() + '\u0000' + loginName
}

internal val sharedNextcloudNotesCache = NextcloudNotesCache()
