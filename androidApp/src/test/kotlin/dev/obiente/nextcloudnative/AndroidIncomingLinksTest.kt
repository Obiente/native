package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidIncomingLinksTest {
    @Test
    fun viewIntentsProduceMonotonicNativeLinkRequests() {
        val first = nextAndroidIncomingLinkState(
            previousSequence = 0L,
            action = ANDROID_ACTION_VIEW,
            dataUrl = "https://cloud.example.test/f/42",
        )
        val second = nextAndroidIncomingLinkState(
            previousSequence = first.sequence,
            action = ANDROID_ACTION_VIEW,
            dataUrl = "nextcloudnative://open?url=https%3A%2F%2Fcloud.example.test%2Ff%2F43",
        )

        assertEquals(1L, first.sequence)
        assertEquals("https://cloud.example.test/f/42", first.request?.url)
        assertEquals(2L, second.sequence)
        assertEquals(
            "nextcloudnative://open?url=https%3A%2F%2Fcloud.example.test%2Ff%2F43",
            second.request?.url,
        )
    }

    @Test
    fun genericTextSharesAreNotAcceptedAsIncomingLinks() {
        val state = nextAndroidIncomingLinkState(
            previousSequence = 7L,
            action = "android.intent.action.SEND",
            dataUrl = "https://cloud.example.test/index.php/apps/files/?dir=%2FPhotos",
        )

        assertEquals(7L, state.sequence)
        assertNull(state.request)
    }

    @Test
    fun unrelatedActionsAndUnsafePayloadsAreIgnoredWithoutAdvancing() {
        listOf(
            "android.intent.action.MAIN" to "https://cloud.example.test/f/42",
            ANDROID_ACTION_VIEW to "javascript:alert(1)",
            ANDROID_ACTION_VIEW to "https://person@cloud.example.test/f/42",
        ).forEach { (action, data) ->
            val state = nextAndroidIncomingLinkState(9L, action, data)
            assertEquals(9L, state.sequence)
            assertNull(state.request)
        }
    }

    @Test
    fun restoredIntentDeliveriesAreSkippedButNewLaunchesAreProcessed() {
        val restoredId = "44ab77da-4d31-4b81-8d60-78f983683d45"

        assertEquals(false, isNewAndroidIncomingLinkDelivery(restoredId, restoredId))
        assertEquals(
            true,
            isNewAndroidIncomingLinkDelivery(
                restoredId,
                "57444e66-73ff-4123-9cb7-d2690d46112a",
            ),
        )
        assertEquals(true, isNewAndroidIncomingLinkDelivery(restoredId, null))
    }
}
