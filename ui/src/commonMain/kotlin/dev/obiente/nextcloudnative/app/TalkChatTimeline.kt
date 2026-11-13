package dev.obiente.nextcloudnative.app

/**
 * The visible messages of one conversation and the newest entry ID the server has given.
 *
 * Edit, delete, and reaction system messages update the message they refer to and are never
 * shown. [newestKnownId] still advances past them, so the next long poll does not receive them
 * again.
 */
internal data class TalkTimeline(
    val messages: List<TalkMessage>,
    val newestKnownId: Long,
) {
    fun message(id: Long): TalkMessage? = messages.firstOrNull { it.id == id }

    /**
     * Applies entries from the latest page or a long poll.
     *
     * A write response is applied with [advanceCursor] false: its ID can be newer than messages
     * the poll has not delivered yet, and advancing past them would skip those messages.
     */
    fun applying(entries: List<TalkChatEntry>, lastGiven: Long? = null, advanceCursor: Boolean = true): TalkTimeline {
        val givenCursor = if (advanceCursor) lastGiven ?: 0L else 0L
        if (entries.isEmpty()) return copy(newestKnownId = maxOf(newestKnownId, givenCursor))
        val byId = LinkedHashMap<Long, TalkMessage>(messages.size + entries.size)
        messages.forEach { byId[it.id] = it }
        var newest = maxOf(newestKnownId, givenCursor)
        entries.sortedBy { it.message.id }.forEach { entry ->
            if (advanceCursor) newest = maxOf(newest, entry.message.id)
            when {
                entry.message.systemMessage == TalkSystemMessageType.HistoryCleared -> {
                    byId.keys.removeAll { it < entry.message.id }
                    byId[entry.message.id] = entry.message
                }
                entry.isTimelineUpdate -> entry.updatedParent?.let { updated ->
                    val current = byId[updated.id] ?: return@let
                    byId[updated.id] = current.updatedBy(updated, entry.parentIncludesOwnReactions)
                }
                else -> byId[entry.message.id] = entry.message
            }
        }
        val updatedQuotes = entries.mapNotNull { it.updatedParent }.associateBy(TalkMessage::id)
        return TalkTimeline(
            messages = byId.values
                .map { message -> message.withRefreshedQuote(updatedQuotes) }
                .sortedBy(TalkMessage::id),
            newestKnownId = newest,
        )
    }

    /** Adds an older history page. Update entries apply only to messages already shown. */
    fun withOlder(entries: List<TalkChatEntry>): TalkTimeline {
        val known = messages.mapTo(HashSet(), TalkMessage::id)
        val older = entries
            .filterNot { it.isTimelineUpdate || it.message.id in known }
            .map(TalkChatEntry::message)
        return copy(messages = (older + messages).sortedBy(TalkMessage::id))
    }

    fun replacing(message: TalkMessage): TalkTimeline =
        copy(messages = messages.map { if (it.id == message.id) message else it })

    companion object {
        val Empty = TalkTimeline(emptyList(), 0L)

        fun of(messages: List<TalkMessage>): TalkTimeline =
            TalkTimeline(messages.sortedBy(TalkMessage::id).distinctBy(TalkMessage::id), messages.maxOfOrNull(TalkMessage::id) ?: 0L)
    }
}

private fun TalkMessage.updatedBy(updated: TalkMessage, includesOwnReactions: Boolean): TalkMessage {
    val reactions = if (includesOwnReactions) {
        updated.reactions
    } else {
        val ownByEmoji = reactions.associate { it.emoji to it.reactedByMe }
        updated.reactions.map { it.copy(reactedByMe = ownByEmoji[it.emoji] ?: false) }
    }
    // A parent copy never carries its own parent, so the reply quote is kept.
    return updated.copy(parent = updated.parent ?: parent, reactions = reactions)
}

private fun TalkMessage.withRefreshedQuote(updated: Map<Long, TalkMessage>): TalkMessage {
    val quote = parent ?: return this
    val source = updated[quote.id] ?: return this
    return copy(parent = quote.copy(summary = source.message, deleted = source.deleted))
}

/** Replaces one message's reactions with the authoritative list returned by the reaction API. */
internal fun TalkMessage.withReactions(updated: List<TalkReaction>): TalkMessage = copy(reactions = updated)

/** Replaces a single emoji's reaction state, keeping the order of the others. */
internal fun TalkMessage.withReaction(emoji: String, updated: TalkReaction?): TalkMessage {
    val existing = reactions.indexOfFirst { it.emoji == emoji }
    val next = when {
        updated == null -> reactions.filterNot { it.emoji == emoji }
        existing >= 0 -> reactions.toMutableList().also { it[existing] = updated }
        else -> reactions + updated
    }
    return copy(reactions = next)
}
