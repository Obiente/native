package dev.obiente.nextcloudnative.app

import kotlin.test.*

class NextcloudEntryNavigationTest {
    private val origin = "https://fixture.invalid:8443/nextcloud"
    private val installed = linkedSetOf("files", "notes", "deck")
    private fun selection(link: String?, app: String = "files", path: String? = "/Old folder/report.txt") =
        UnifiedSearchSelection(UnifiedSearchProvider(app, app, "Synthetic provider", null, 0, false, emptyList(), emptyList(), false),
            UnifiedSearchEntry(null, "report.txt", path, link, null, false, path?.let { mapOf("path" to it) }.orEmpty()))
    private fun activity(link: String?, app: String = "files") = NextcloudActivity(1, app, "file_changed",
        "Synthetic record changed", null, "file", "999", "/Old folder/report.txt", link, null, null)

    @Test fun exactFilesLinksTakePriorityOverSearchPathAndProviderHints() {
        for (link in listOf("/f/42", "/nextcloud/index.php/apps/files/?openfile=42", "/apps/files/files/42")) {
            val target = assertIs<UnifiedSearchOpenTarget.Link>(selection(link).openTarget(origin, installed))
            assertEquals(42L, assertIs<NextcloudLinkDestination.FileId>(nextcloudLinkDestination(origin, target.url)).value)
        }
    }
    @Test fun activityUsesSuppliedFileIdentityInsteadOfObjectIdOrParentFolder() {
        val target = assertNotNull(activity("/f/42").activityOpenAction(installed, origin))
        assertNull(target.filesParentPath)
        assertNull(target.appId)
        assertEquals(42L, assertIs<NextcloudLinkDestination.FileId>(nextcloudLinkDestination(origin, target.sameOriginUrl!!)).value)
    }
    @Test fun relativeSubpathLinksAreResolvedOnlyOnceAndForeignOriginsNeverBecomeNative() {
        val target = assertIs<UnifiedSearchOpenTarget.Link>(selection("/nextcloud/f/42").openTarget(origin, installed))
        assertEquals("$origin/f/42", target.url)
        for (link in listOf("https://foreign.invalid/f/42", "https://fixture.invalid/f/42", "/f/42?download=1",
            "/f/42#view", "/apps/files/?openfile=42&fileid=43", "/f/%2e%2e", "javascript:alert(1)")) {
            assertNull(preferredNativeEntryLink(origin, link, installed), link)
        }
    }
    @Test fun unsupportedAppRecordKeepsNativeAppFallbackWithoutInventingAnItemRoute() {
        assertEquals(UnifiedSearchOpenTarget.App("notes"), selection("/apps/notes/#/note/42", "notes", null).openTarget(origin, installed))
        val activity = activity("/apps/deck/board/42", "deck").copy(type = "card_changed", objectType = "card", objectName = "Synthetic card")
        assertEquals("deck", activity.activityOpenAction(installed, origin)?.appId)
        assertNull(preferredNativeEntryLink(origin, "/apps/deck/board/42", installed))
    }
    @Test fun parentFolderFallbackRemainsAvailableOnlyWithoutAnExactSupportedLink() {
        assertEquals(UnifiedSearchOpenTarget.FilesPath("Old folder"), selection(null).openTarget(origin, installed))
        assertEquals("Old folder", activity(null).activityOpenAction(installed, origin)?.filesParentPath)
        assertEquals(UnifiedSearchOpenTarget.App("files"), selection(null, path = "/unsafe\\path/report.txt").openTarget(origin, installed))
    }
    @Test fun providerOpensItsExactAppBeforeAnyPrefixMatch() {
        val apps = linkedSetOf("news", "newsletter")
        fun provider(id: String, appId: String) = UnifiedSearchSelection(
            UnifiedSearchProvider(id, appId, "Synthetic provider", null, 0, false, emptyList(), emptyList(), false),
            UnifiedSearchEntry(null, "Issue", null, null, null, false, emptyMap()),
        )
        assertEquals(UnifiedSearchOpenTarget.App("newsletter"), provider("newsletter-search", "newsletter").openTarget(origin, apps))
        // Without an exact app ID, only a whole segment matches, and the longest installed ID wins.
        assertEquals(UnifiedSearchOpenTarget.App("newsletter"), provider("newsletter-search", "unknown").openTarget(origin, apps))
        assertEquals(UnifiedSearchOpenTarget.App("news"), provider("news_feed", "unknown").openTarget(origin, apps))
        assertNull(provider("newsroom", "unknown").openTarget(origin, apps))
    }

    @Test fun appRootLinksUseVerifiedUrlBeforeUnrelatedProviderHint() {
        val target = assertIs<UnifiedSearchOpenTarget.Link>(selection("/apps/notes/", app = "deck", path = null).openTarget(origin, installed))
        assertEquals("notes", assertIs<NextcloudLinkDestination.App>(nextcloudLinkDestination(origin, target.url)).appId)
    }
}
