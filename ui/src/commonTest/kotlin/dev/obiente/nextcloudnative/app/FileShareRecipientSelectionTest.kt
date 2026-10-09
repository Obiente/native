package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun aCompleteTypedEmailAddressIsOfferedUntilTheServerReturnsTheSameAddress() {
        val typing = FileShareRecipientPickerUiState(query = " Reader@Example.test ", loading = true)
        val typed = FileShareRecipient(
            "Reader@Example.test",
            "Reader@Example.test",
            FileShareTarget.Email,
            exact = true,
            origin = FileShareRecipientOrigin.Typed,
        )

        assertEquals(typed, typing.typedRecipient(FileShareTarget.Email))
        assertEquals(listOf(typed), typing.visibleChoices(FileShareTarget.Email))
        assertEquals("Press Enter or select the address to use it.", typing.supportingMessage(FileShareTarget.Email))

        val failedSearch = typing.copy(loading = false, error = "Could not search recipients.")
        assertEquals(typed, failedSearch.typedRecipient(FileShareTarget.Email))

        val contact = FileShareRecipient("reader@example.test", "Reader Contact", FileShareTarget.Email)
        val confirmed = typing.copy(loading = false, results = listOf(contact))
        assertNull(confirmed.typedRecipient(FileShareTarget.Email))
        assertEquals(listOf(contact), confirmed.visibleChoices(FileShareTarget.Email))
        assertEquals("Select a result to continue.", confirmed.supportingMessage(FileShareTarget.Email))
    }

    @Test
    fun typedAddressesAreOnlyOfferedForEmailSharesWithoutAnExistingSelection() {
        val state = FileShareRecipientPickerUiState(query = "reader@example.test")

        assertNull(state.typedRecipient(FileShareTarget.User))
        assertNull(state.typedRecipient(FileShareTarget.Group))
        assertNull(state.typedRecipient(FileShareTarget.Remote))
        assertNull(state.copy(selectedRecipient = "reader@example.test").typedRecipient(FileShareTarget.Email))
        assertEquals(
            "Selected: reader@example.test",
            state.copy(selectedRecipient = "reader@example.test").supportingMessage(FileShareTarget.Email),
        )
    }

    @Test
    fun anInvalidTypedEmailAddressShowsWhyItCannotBeUsed() {
        val incomplete = FileShareRecipientPickerUiState(query = "reader@example")

        assertNull(incomplete.typedRecipient(FileShareTarget.Email))
        assertTrue(incomplete.visibleChoices(FileShareTarget.Email).isEmpty())
        assertEquals(
            "Enter a complete domain after the @, such as example.com.",
            incomplete.supportingMessage(FileShareTarget.Email),
        )
        assertEquals(
            "Enter the domain after the @.",
            FileShareRecipientPickerUiState(query = "reader@").supportingMessage(FileShareTarget.Email),
        )
        assertEquals(
            "No matching email addresses",
            FileShareRecipientPickerUiState(query = "Reader").supportingMessage(FileShareTarget.Email),
        )
        assertEquals(
            "Enter an email address, or search and select a result.",
            FileShareRecipientPickerUiState().supportingMessage(FileShareTarget.Email),
        )
    }

    @Test
    fun aChosenEmailRecipientEnablesCreationOnlyWhenTheServerAdvertisesEmailShares() {
        val recipient = typedFileShareEmailRecipient("reader@example.test")!!
        val ready = assertIs<FileShareCreationPlan.Ready>(
            dialog(recipient = recipient, capabilities = emailCapabilities).creationPlan,
        )
        assertEquals(FileShareTarget.Email, ready.request.target)
        assertEquals("reader@example.test", ready.request.shareWith)

        val queryOnly = emailCapabilities.copy(emailProviderAdvertised = false, emailProviderObserved = true)
        assertEquals(
            FileShareCreationPlan.Blocked("Sharing by email is unavailable on this server."),
            dialog(recipient = recipient, capabilities = queryOnly).creationPlan,
        )
        assertEquals(
            FileShareCreationPlan.Blocked("File sharing is unavailable for this account."),
            dialog(recipient = recipient, capabilities = NextcloudFileSharingCapabilities.Unavailable).creationPlan,
        )
    }

    @Test
    fun emailCreationNeedsAValidRecipientChosenForTheEmailTarget() {
        assertEquals(
            FileShareCreationPlan.Blocked("Enter an email address or choose one from the search results."),
            dialog(recipient = null, capabilities = emailCapabilities).creationPlan,
        )
        val user = FileShareRecipient("reader", "Reader", FileShareTarget.User, exact = true)
        assertEquals(
            FileShareCreationPlan.Blocked("Enter an email address or choose one from the search results."),
            dialog(recipient = user, capabilities = emailCapabilities.copy(userShares = true)).creationPlan,
        )
    }

    @Test
    fun onlyATypedAddressIsHeldToTheLocalEmailGrammar() {
        val typed = FileShareRecipient(
            "reader@example",
            "reader@example",
            FileShareTarget.Email,
            exact = true,
            origin = FileShareRecipientOrigin.Typed,
        )
        assertEquals(
            FileShareCreationPlan.Blocked("Enter a complete domain after the @, such as example.com."),
            dialog(recipient = typed, capabilities = emailCapabilities).creationPlan,
        )
        val queryOnly = emailCapabilities.copy(emailProviderAdvertised = false)
        assertEquals(
            FileShareCreationPlan.Blocked("Sharing by email is unavailable on this server."),
            dialog(recipient = typed, capabilities = queryOnly).creationPlan,
        )

        listOf("reader@localhost", "\"quoted name\"@example.test", "reader@[192.0.2.1]").forEach { address ->
            val returned = FileShareRecipient(address, "Synthetic contact", FileShareTarget.Email)
            assertEquals(FileShareRecipientOrigin.Server, returned.origin)
            val ready = assertIs<FileShareCreationPlan.Ready>(
                dialog(recipient = returned, capabilities = emailCapabilities).creationPlan,
                address,
            )
            assertEquals(address, ready.request.shareWith)
            assertEquals(
                FileShareCreationPlan.Blocked("Sharing by email is unavailable on this server."),
                dialog(recipient = returned, capabilities = queryOnly).creationPlan,
            )
        }
    }

    @Test
    fun aMatchingServerResultBeyondTheVisibleLimitIsPromotedAsTheOnlyChoiceForTheAddress() {
        val others = (1..8).map {
            FileShareRecipient("reader.$it@example.test", "Reader $it", FileShareTarget.Email)
        }
        val match = FileShareRecipient("reader@example.test", "Reader Contact", FileShareTarget.Email)
        val state = FileShareRecipientPickerUiState(query = "Reader@example.test", results = others + match)

        assertNull(state.typedRecipient(FileShareTarget.Email))
        val choices = state.visibleChoices(FileShareTarget.Email)
        assertEquals(match, choices.first())
        assertEquals(8, choices.size)
        assertEquals(1, choices.count { it.id.equals("reader@example.test", ignoreCase = true) })
        assertEquals(others.take(7), choices.drop(1))
        assertEquals("Select a result to continue.", state.supportingMessage(FileShareTarget.Email))

        val withinLimit = state.copy(results = listOf(others[0], match))
        assertEquals(listOf(match, others[0]), withinLimit.visibleChoices(FileShareTarget.Email))
        assertEquals(others, state.copy(query = "Reader").visibleChoices(FileShareTarget.Email))
    }

    @Test
    fun aRecipientChosenForAnotherTargetCannotBecomeTheShareTarget() {
        val email = typedFileShareEmailRecipient("reader@example.test")!!
        val state = dialog(recipient = email, capabilities = emailCapabilities.copy(userShares = true))
            .copy(target = FileShareTarget.User)

        assertEquals(
            FileShareCreationPlan.Blocked("Choose a recipient from the search results."),
            state.creationPlan,
        )
    }

    private fun dialog(
        recipient: FileShareRecipient?,
        capabilities: NextcloudFileSharingCapabilities,
    ) = FileShareDialogUiState(
        file = NextcloudFile("Synthetic/report.md", "report.md", false, "text/markdown", 0L, null, null, false),
        capabilities = capabilities,
        existingShares = emptyList(),
        target = FileShareTarget.Email,
        recipient = recipient,
    )

    private val emailCapabilities = NextcloudFileSharingCapabilities(
        apiEnabled = true,
        publicLinks = true,
        emailRecipientQuery = true,
        emailProviderAdvertised = true,
    )
}
