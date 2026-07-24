package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountAvatarContentDescriptionTest {
    @Test
    fun genericAvatarDoesNotAssumeAMockAccountIdentity() {
        assertEquals("Account avatar", accountAvatarContentDescription(null))
        assertEquals("Account avatar", accountAvatarContentDescription("  "))
    }

    @Test
    fun knownAccountNameProducesAnAccurateAvatarDescription() {
        assertEquals(
            "Account avatar for Example User",
            accountAvatarContentDescription(" Example User "),
        )
    }
}
