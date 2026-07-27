package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileShareVisualCaptureTest {
    @Test
    fun recipientPickerStateKeepsVisualStatesDeterministic() {
        val results = (1..10).map { index ->
            FileShareRecipient(
                id = "demo-$index",
                displayName = "Demo result $index",
                target = FileShareTarget.User,
                exact = index == 1,
            )
        }
        val ready = FileShareRecipientPickerUiState(query = "de", results = results)

        assertEquals(8, ready.visibleResults.size)
        assertNull(ready.supportingMessage(FileShareTarget.User))
        assertEquals(
            "Search your Nextcloud server and select a result.",
            FileShareRecipientPickerUiState(query = "d").supportingMessage(FileShareTarget.User),
        )
        assertEquals(
            "No matching groups",
            FileShareRecipientPickerUiState(query = "de").supportingMessage(FileShareTarget.Group),
        )
        assertNull(
            FileShareRecipientPickerUiState(query = "de", loading = true)
                .supportingMessage(FileShareTarget.User),
        )
        assertEquals(
            "Selected: demo-account",
            FileShareRecipientPickerUiState(selectedRecipient = "demo-account")
                .supportingMessage(FileShareTarget.User),
        )
    }

    @Test
    fun fileShareReviewCatalogCoversBothFormFactorsAndMaterialStates() {
        val reviewScenarios = fileShareCaptureScenarios

        assertEquals(4, reviewScenarios.size)
        assertTrue(reviewScenarios.any { it.presentation == NextcloudPresentation.Desktop })
        assertTrue(reviewScenarios.any { it.presentation == NextcloudPresentation.Adaptive })
        assertTrue(reviewScenarios.all { it.id.startsWith("file-share-") })

        val user = marketingFileShareCaptureState(MarketingCaptureScenario.FileShareUserMobile)
        assertEquals(FileShareTarget.User, user.dialog.target)
        assertTrue(user.recipientPicker.results.all { it.target == FileShareTarget.User })
        assertTrue(user.dialog.existingShares.orEmpty().any { it.shareType == FileShareTarget.Group.wireValue })

        val group = marketingFileShareCaptureState(MarketingCaptureScenario.FileShareGroupDesktop)
        assertEquals(FileShareTarget.Group, group.dialog.target)
        assertTrue(group.recipientPicker.results.all { it.target == FileShareTarget.Group })
        assertTrue(group.dialog.existingShares.orEmpty().any { it.shareType == FileShareTarget.User.wireValue })

        val loading = marketingFileShareCaptureState(MarketingCaptureScenario.FileShareLoadingMobile)
        assertTrue(loading.recipientPicker.loading)
        assertFalse(loading.dialog.capabilities.userExpirationSupported)

        val error = marketingFileShareCaptureState(MarketingCaptureScenario.FileShareErrorMobile)
        assertFalse(error.recipientPicker.error.isNullOrBlank())
        assertFalse(error.dialog.capabilities.userExpirationSupported)
    }

    @Test
    fun syntheticShareFixtureCannotEnableAWriteWithoutASelectedRecipient() {
        val capture = marketingFileShareCaptureState(MarketingCaptureScenario.FileShareUserMobile)

        assertIs<FileShareCreationPlan.Blocked>(capture.dialog.creationPlan)
        assertEquals("Projects/Product brief.pdf", capture.dialog.file.path)
        assertFalse(capture.dialog.file.path.startsWith("/"))
        assertFalse(capture.dialog.file.path.contains("home/"))
        assertTrue(
            capture.dialog.existingShares.orEmpty().all { share ->
                share.url == null && share.token == null
            },
        )
    }
}
