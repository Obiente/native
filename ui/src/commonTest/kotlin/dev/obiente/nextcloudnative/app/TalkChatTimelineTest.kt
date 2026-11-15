package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkChatTimelineTest {
    private fun entry(message: TalkMessage, parent: TalkMessage? = null, ownReactionsKnown: Boolean = true) =
        TalkChatEntry(message, parent, ownReactionsKnown)

    private fun update(id: Long, kind: TalkSystemMessageType, parent: TalkMessage, ownReactionsKnown: Boolean = true) = entry(
        syntheticMessage(id).copy(
            systemMessage = kind,
            systemMessageName = kind.wireValue,
            messageType = TalkMessageType.System,
        ),
        parent,
        ownReactionsKnown,
    )

    @Test
    fun `entries merge by id in order and advance the cursor`() {
        val timeline = TalkTimeline.of(listOf(syntheticMessage(2), syntheticMessage(1)))
            .applying(listOf(entry(syntheticMessage(4)), entry(syntheticMessage(3))), lastGiven = 6)
        assertEquals(listOf(1L, 2L, 3L, 4L), timeline.messages.map(TalkMessage::id))
        assertEquals(6L, timeline.newestKnownId)
    }

    @Test
    fun `write responses do not move the cursor past undelivered messages`() {
        val timeline = TalkTimeline.of(listOf(syntheticMessage(1)))
            .applying(listOf(entry(syntheticMessage(9))), lastGiven = 9, advanceCursor = false)
        assertEquals(listOf(1L, 9L), timeline.messages.map(TalkMessage::id))
        assertEquals(1L, timeline.newestKnownId)
    }

    @Test
    fun `update entries replace their parent and are never shown`() {
        val original = syntheticMessage(1, text = "Before")
        val timeline = TalkTimeline.of(listOf(original))
            .applying(listOf(update(2, TalkSystemMessageType.MessageEdited, original.copy(message = "After", editedAt = 5))))
        assertEquals(listOf(1L), timeline.messages.map(TalkMessage::id))
        assertEquals("After", timeline.messages.single().message)
        assertEquals(2L, timeline.newestKnownId)
    }

    @Test
    fun `an update for a message that is not loaded is ignored`() {
        val timeline = TalkTimeline.of(listOf(syntheticMessage(5)))
            .applying(listOf(update(6, TalkSystemMessageType.MessageDeleted, syntheticMessage(1).copy(deleted = true))))
        assertEquals(listOf(5L), timeline.messages.map(TalkMessage::id))
    }

    @Test
    fun `own reaction flags are kept when the parent omits them`() {
        val mine = syntheticMessage(1, reactions = listOf(TalkReaction("a", 1, reactedByMe = true)))
        val counted = mine.copy(reactions = listOf(TalkReaction("a", 2, false), TalkReaction("b", 1, false)))
        val unknown = TalkTimeline.of(listOf(mine))
            .applying(listOf(update(2, TalkSystemMessageType.Reaction, counted, ownReactionsKnown = false)))
        assertEquals(listOf(TalkReaction("a", 2, true), TalkReaction("b", 1, false)), unknown.messages.single().reactions)
        val known = TalkTimeline.of(listOf(mine))
            .applying(listOf(update(2, TalkSystemMessageType.Reaction, counted, ownReactionsKnown = true)))
        assertFalse(known.messages.single().reactions.first().reactedByMe)
    }

    @Test
    fun `reply quotes follow edits and deletions of their parent`() {
        val parent = syntheticMessage(1, text = "Original")
        val reply = syntheticMessage(2).copy(parent = TalkMessageQuote(1, "alice", "Original", deleted = false))
        val timeline = TalkTimeline.of(listOf(parent, reply))
            .applying(listOf(update(3, TalkSystemMessageType.MessageDeleted, parent.copy(message = "Message deleted", deleted = true))))
        assertTrue(requireNotNull(timeline.message(2)?.parent).deleted)
    }

    @Test
    fun `clearing history drops older messages`() {
        val cleared = syntheticMessage(3).copy(
            systemMessage = TalkSystemMessageType.HistoryCleared,
            systemMessageName = "history_cleared",
            messageType = TalkMessageType.System,
        )
        val timeline = TalkTimeline.of(listOf(syntheticMessage(1), syntheticMessage(2))).applying(listOf(entry(cleared)))
        assertEquals(listOf(3L), timeline.messages.map(TalkMessage::id))
    }

    @Test
    fun `older pages add only regular messages and keep the cursor`() {
        val current = TalkTimeline.of(listOf(syntheticMessage(10)))
        val older = current.withOlder(
            listOf(
                entry(syntheticMessage(4)),
                entry(syntheticMessage(10)),
                update(5, TalkSystemMessageType.Reaction, syntheticMessage(4)),
            ),
        )
        assertEquals(listOf(4L, 10L), older.messages.map(TalkMessage::id))
        assertEquals(10L, older.newestKnownId)
    }

    @Test
    fun `a single reaction can be replaced added or removed`() {
        val message = syntheticMessage(1, reactions = listOf(TalkReaction("a", 1, false), TalkReaction("b", 2, true)))
        assertEquals(listOf("a", "b"), message.withReaction("a", TalkReaction("a", 2, true)).reactions.map(TalkReaction::emoji))
        assertEquals(listOf("b"), message.withReaction("a", null).reactions.map(TalkReaction::emoji))
        assertEquals(listOf("a", "b", "c"), message.withReaction("c", TalkReaction("c", 1, true)).reactions.map(TalkReaction::emoji))
    }
}
