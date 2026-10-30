package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TalkChatPresentationTest {
    @Test
    fun scrollingToLatestMessageIncludesOnlyVisibleHistoryHeader() {
        assertNull(talkLastMessageListIndex(0, hasHistoryHeader = false))
        assertNull(talkLastMessageListIndex(0, hasHistoryHeader = true))
        assertEquals(2, talkLastMessageListIndex(3, hasHistoryHeader = false))
        assertEquals(3, talkLastMessageListIndex(3, hasHistoryHeader = true))
    }

    @Test
    fun timestampsUseSecondsAndExplicitUtcWithoutInventingMissingTimes() {
        assertEquals("1970-01-01 00:01 UTC", formatTalkMessageTimeUtc(60))
        assertEquals("2024-01-01 00:00 UTC", formatTalkMessageTimeUtc(1_704_067_200))
        assertNull(formatTalkMessageTimeUtc(0))
        assertNull(formatTalkMessageTimeUtc(-1))
        assertNull(formatTalkMessageTimeUtc(Long.MAX_VALUE))
    }

    @Test
    fun liveStatusNamesTheStateWithoutColor() {
        assertEquals("Live", talkLiveStatusLabel(TalkLiveState.Connected))
        assertEquals("Reconnecting...", talkLiveStatusLabel(TalkLiveState.Reconnecting(TalkFailure(TalkFailureKind.Offline))))
        assertEquals(
            TalkFailure(TalkFailureKind.NotFound).message,
            talkLiveStatusLabel(TalkLiveState.Stopped(TalkFailure(TalkFailureKind.NotFound))),
        )
    }
}
