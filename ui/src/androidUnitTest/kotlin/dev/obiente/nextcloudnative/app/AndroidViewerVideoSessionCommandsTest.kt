package dev.obiente.nextcloudnative.app

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidViewerVideoSessionCommandsTest {
    private val previous = setOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
    private val next = setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)

    @Test
    fun previousAndNextFollowTheViewerSequence() {
        assertEquals(ViewerVideoSkipCommands(enabled = previous + next, disabled = emptySet()), viewerVideoSkipCommands(true, true))
    }

    @Test
    fun theEndsOfTheSequenceOrABlockedViewerOfferNoSkip() {
        assertEquals(
            ViewerVideoSkipCommands(enabled = next, disabled = previous),
            viewerVideoSkipCommands(hasPrevious = false, hasNext = true),
            "The single-item player must not restart the video instead of going back.",
        )
        assertEquals(ViewerVideoSkipCommands(enabled = previous, disabled = next), viewerVideoSkipCommands(true, false))
        assertEquals(ViewerVideoSkipCommands(enabled = emptySet(), disabled = previous + next), viewerVideoSkipCommands(false, false))
    }
}
