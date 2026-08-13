package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicMutationPersistenceTest {
    @Test
    fun `pending dynamic mutation round trips only for its exact target`() {
        val values = mapOf(
            "teamId" to "42",
            "work" to
                "[{\"id\":\"123e4567-e89b-42d3-a456-426614174000\",\"chore_id\":\"7\"}]",
        )
        val encoded = requireNotNull(
            encodePersistedDynamicMutation(
                appId = "chores",
                actionId = "chores-completion-v1:create-work",
                targetRecordId = "7",
                values = values,
            ),
        )

        assertEquals(
            values,
            decodePersistedDynamicMutation(
                encoded = encoded,
                expectedAppId = "chores",
                expectedActionId = "chores-completion-v1:create-work",
                expectedTargetRecordId = "7",
            ),
        )
        assertNull(
            decodePersistedDynamicMutation(
                encoded = encoded,
                expectedAppId = "chores",
                expectedActionId = "chores-completion-v1:create-work",
                expectedTargetRecordId = "8",
            ),
        )
    }

    @Test
    fun `pending dynamic mutation rejects unsafe identity and control data`() {
        assertNull(
            encodePersistedDynamicMutation(
                appId = "chores",
                actionId = "create/work",
                targetRecordId = "7",
                values = mapOf("work" to "safe"),
            ),
        )
        assertNull(
            encodePersistedDynamicMutation(
                appId = "chores",
                actionId = "create-work",
                targetRecordId = "7",
                values = mapOf("work" to "unsafe\u0000value"),
            ),
        )
    }
}
