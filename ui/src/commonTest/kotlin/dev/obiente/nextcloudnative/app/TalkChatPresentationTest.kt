package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun failedSendPreservesDraftAndDoesNotRefreshOrResend() = runBlocking {
        var draft = "Synthetic message"
        var sends = 0
        var refreshes = 0
        val result = submitTalkDraft(
            send = { sends++; throw IllegalStateException("Disconnected") },
            onAcknowledged = { draft = "" },
            refresh = { refreshes++ },
        )
        assertEquals(TalkSendResult.Unconfirmed, result)
        assertEquals("Synthetic message", draft)
        assertEquals(1, sends)
        assertEquals(0, refreshes)
    }

    @Test
    fun refreshFailureAfterAcknowledgementDoesNotMasqueradeAsSendFailure() = runBlocking {
        var draft = "Synthetic message"
        var sends = 0
        val result = submitTalkDraft(
            send = { sends++ },
            onAcknowledged = { draft = "" },
            refresh = { throw IllegalStateException("Refresh unavailable") },
        )
        assertEquals(TalkSendResult.SentRefreshFailed, result)
        assertEquals("", draft)
        assertEquals(1, sends)
    }

    @Test
    fun cancellationBeforeAcknowledgementPreservesDraft() = runBlocking {
        var draft = "Synthetic message"
        assertFailsWith<CancellationException> {
            submitTalkDraft(
                send = { throw CancellationException() },
                onAcknowledged = { draft = "" },
                refresh = { error("Must not refresh") },
            )
        }
        assertEquals("Synthetic message", draft)
    }

    @Test
    fun cancellationDuringRefreshKeepsAcknowledgedDraftCleared() = runBlocking {
        var draft = "Synthetic message"
        assertFailsWith<CancellationException> {
            submitTalkDraft(
                send = {},
                onAcknowledged = { draft = "" },
                refresh = { throw CancellationException() },
            )
        }
        assertEquals("", draft)
    }
}
