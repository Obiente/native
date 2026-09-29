package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileShareRecipientSelectionTest {
    @Test
    fun typedEmailAddressIsSelectedWhenTheServerConfirmsItExactly() {
        val typed = FileShareRecipient("reader@example.test", "reader@example.test", FileShareTarget.Email, exact = true)
        val contact = FileShareRecipient("reader.other@example.test", "Reader Other", FileShareTarget.Email)

        assertEquals(
            typed,
            automaticFileShareRecipient(FileShareTarget.Email, " Reader@Example.test ", listOf(typed, contact)),
        )
    }

    @Test
    fun typedFederatedCloudIdIsSelectedWhenTheServerConfirmsItExactly() {
        val remote = FileShareRecipient("reader@cloud.example.test", "reader", FileShareTarget.Remote, exact = true)

        assertEquals(remote, automaticFileShareRecipient(FileShareTarget.Remote, "reader@cloud.example.test", listOf(remote)))
    }

    @Test
    fun partialOrUnconfirmedMatchesStillNeedAnExplicitChoice() {
        val suggestion = FileShareRecipient("reader@example.test", "Reader", FileShareTarget.Email, exact = false)
        val exactOther = FileShareRecipient("reader@example.test", "Reader", FileShareTarget.Email, exact = true)

        assertNull(automaticFileShareRecipient(FileShareTarget.Email, "reader@example.test", listOf(suggestion)))
        assertNull(automaticFileShareRecipient(FileShareTarget.Email, "reader@exam", listOf(exactOther)))
        assertNull(
            automaticFileShareRecipient(FileShareTarget.Email, "reader@example.test", listOf(exactOther, exactOther.copy())),
        )
    }

    @Test
    fun peopleAndGroupsAreNeverSelectedAutomatically() {
        val user = FileShareRecipient("reader", "Reader", FileShareTarget.User, exact = true)
        val group = FileShareRecipient("readers", "Readers", FileShareTarget.Group, exact = true)

        assertNull(automaticFileShareRecipient(FileShareTarget.User, "reader", listOf(user)))
        assertNull(automaticFileShareRecipient(FileShareTarget.Group, "readers", listOf(group)))
    }
}
