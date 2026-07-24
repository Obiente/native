package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TalkHistoryRequestTest {
    @Test
    fun historyPagesUseOpaqueCursorWithoutUpdatingReadOrPresenceState() {
        val path = talkMessageHistoryPath("room token/slash", olderCursor = 73L, limit = 25)

        assertTrue(path.startsWith("/ocs/v2.php/apps/spreed/api/v1/chat/room%20token%2Fslash?"))
        assertTrue("lookIntoFuture=0" in path)
        assertTrue("lastKnownMessageId=73" in path)
        assertTrue("includeLastKnown=0" in path)
        assertTrue("setReadMarker=0" in path)
        assertTrue("markNotificationsAsRead=0" in path)
        assertTrue("noStatusUpdate=1" in path)
    }

    @Test
    fun historyPageBoundsRejectInvalidLimitsAndCursors() {
        assertFailsWith<IllegalArgumentException> {
            talkMessageHistoryPath("room", olderCursor = -1L, limit = 25)
        }
        assertFailsWith<IllegalArgumentException> {
            talkMessageHistoryPath("room", olderCursor = null, limit = MAX_TALK_MESSAGE_PAGE_SIZE + 1)
        }
    }
}
