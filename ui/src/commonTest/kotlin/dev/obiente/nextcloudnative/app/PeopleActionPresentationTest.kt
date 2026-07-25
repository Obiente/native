package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeopleActionPresentationTest {
    @Test
    fun keepsMemoriesCoverSelectionAvailableWithoutRecognizeKey() {
        val items = personActionMenuItems(
            person = person,
            support = support(apiKey = false),
            hasSelectablePhoto = true,
            hasDirectFaceReferences = false,
        ).associateBy(PeopleActionMenuItem::action)

        assertTrue(requireNotNull(items[PeopleAction.SetCover]).enabled)
        assertNull(requireNotNull(items[PeopleAction.SetCover]).disabledReason)

        val recognizeActions = setOf(
            PeopleAction.RenamePerson,
            PeopleAction.MergePerson,
            PeopleAction.RemoveFace,
            PeopleAction.DeletePerson,
        )
        recognizeActions.forEach { action ->
            val item = requireNotNull(items[action])
            assertFalse(item.enabled)
            assertEquals("Recognize's short-lived API key is unavailable.", item.disabledReason)
        }
    }

    @Test
    fun requiresARealLoadedPhotoBeforeStartingCoverSelection() {
        val cover = personActionMenuItems(
            person = person,
            support = support(apiKey = false),
            hasSelectablePhoto = false,
            hasDirectFaceReferences = false,
        ).single { it.action == PeopleAction.SetCover }

        assertFalse(cover.enabled)
        assertEquals("No loaded photo is available.", cover.disabledReason)
    }

    @Test
    fun doesNotOfferFaceRemovalWithoutDetectionReferencesEvenWhenDavIsReady() {
        val removeFace = personActionMenuItems(
            person = person,
            support = support(apiKey = true),
            hasSelectablePhoto = true,
            hasDirectFaceReferences = false,
        ).single { it.action == PeopleAction.RemoveFace }

        assertFalse(removeFace.enabled)
        assertEquals("Face assignment details are not available yet.", removeFace.disabledReason)
    }

    private fun support(apiKey: Boolean) = PeopleActionSupport(
        currentUserId = "ada",
        memoriesPeopleApiAvailable = true,
        recognizeDavAvailable = true,
        recognizeApiKeyRequired = true,
        recognizeApiKeyAvailable = apiKey,
    )

    private companion object {
        val person = PersonMediaReference(
            backend = NextcloudPeopleBackend.Recognize,
            clusterId = 42L,
            ownerUserId = "ada",
            lookupName = "Grace Hopper",
        )
    }
}
