package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OfficeEditorNavigationTest {
    private val server = "https://cloud.example.test/nextcloud"
    private val document = "$server/index.php/apps/files/directEditing/one-time-token"
    private val navigation = OfficeEditorNavigation(server, document)

    @Test
    fun acceptsOnlyTheSelectedDocumentAtTheTopLevel() {
        assertTrue(navigation.allowsMainFrame(document))
        assertTrue(navigation.allowsMainFrame("$document#slide2"))
        assertTrue(navigation.allowsMainFrame(document.replace("cloud.example.test", "cloud.example.test:443")))
        listOf(
            "$server/apps/office/", "$server/apps/files/", "$server/settings/user",
            "$server/login", "$document?redirect=/apps/files", "$document/other",
            document.replace("one-time-token", "another-document"),
            document.replace("https:", "http:"),
            document.replace("cloud.example.test", "other.example.test"),
            document.replace("cloud.example.test", "user@cloud.example.test"),
            "about:blank", "data:text/html,other", "blob:$server/id", "javascript:alert(1)",
            "intent://open", "file:///tmp/document",
        ).forEach { url -> assertFalse(navigation.allowsMainFrame(url), url) }
    }

    @Test
    fun rejectsDashboardAsInitialSession() {
        listOf(
            "$server/apps/office/", "$server/apps/files/",
            "$document?x=1", "$document#x", "$document/../other",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException> { OfficeEditorNavigation(server, url) }
        }
    }

    @Test
    fun permitsProviderBootstrapButNotClickedLinksInFrames() {
        assertTrue(navigation.allowsNavigation("https://office.example.test/editor", false, false))
        assertTrue(navigation.allowsNavigation("about:blank", false, false))
        assertFalse(navigation.allowsNavigation("https://office.example.test/help", false, true))
        assertFalse(navigation.allowsNavigation("intent://open", false, false))
        assertFalse(navigation.allowsNavigation("https://office.example.test/editor", true, false))
    }

    @Test
    fun diagnosticsDoNotExposeToken() {
        assertFalse(navigation.toString().contains("one-time-token"))
    }
}
