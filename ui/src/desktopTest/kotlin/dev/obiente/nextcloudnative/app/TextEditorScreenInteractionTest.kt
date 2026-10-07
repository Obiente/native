package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextEditorScreenInteractionTest {
    @Test
    fun emptyMarkdownStartsLabeledEditingWithSaveInHeaderAndConfirmationIntact() {
        listOf(800 to 360, 390 to 844).forEach { (width, height) ->
            val fixture = EditorFixture()
            nativeSceneTest(width, height, content = {
                TextEditorScreen(fixture.services, session, "synthetic-user", file, {})
            }) {
                assertTrue(has("Document content"))
                val field = assertNotNull(nodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null
                })
                if (width > height) assertTrue(field.boundsInRoot.height > height / 2f)
                assertTrue(assertNotNull(node("Save")).boundsInRoot.bottom < field.boundsInRoot.top)
                replaceText("", "Synthetic unsaved draft")
                // The recovery copy is written after a short typing debounce.
                settleUntil { fixture.stored?.text == "Synthetic unsaved draft" }
                assertEquals("Synthetic unsaved draft", fixture.stored?.text)
                click("Save")
                assertTrue(has("Save changes to Nextcloud?"))
                assertEquals(0, fixture.uploads)
                click("Cancel")
                assertEquals(0, fixture.uploads)
                assertEquals("Synthetic unsaved draft", fixture.stored?.text)
            }
        }
    }

    @Test
    fun restoredDraftAndExplicitPreviewChoiceSurviveRecomposition() {
        val fixture = EditorFixture().apply { stored = TextEditorDraft("", "Restored synthetic draft", "etag") }
        val navigationBusy = mutableStateOf(false)
        nativeSceneTest(800, 360, content = {
            TextEditorScreen(fixture.services, session, "synthetic-user", file, {},
                navigationCommitInProgress = navigationBusy.value)
        }) {
            assertTrue(has("Document content"))
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Restored synthetic draft"
            })
            click("Preview")
            assertFalse(has("Document content"))
            navigationBusy.value = true
            settle()
            navigationBusy.value = false
            settle()
            assertFalse(has("Document content"))
            assertEquals("Restored synthetic draft", fixture.stored?.text)
            assertEquals(0, fixture.downloads)
            assertEquals(0, fixture.uploads)
        }
    }

    @Test
    fun hidingTheAppFlushesTheDraftBeforeTheTypingDebounce() {
        val fixture = EditorFixture()
        val visibility = MutableStateFlow(true)
        nativeSceneTest(800, 360, content = {
            CompositionLocalProvider(LocalAppWindowVisibility provides visibility) {
                TextEditorScreen(fixture.services, session, "synthetic-user", file, {})
            }
        }) {
            val field = assertNotNull(nodes().lastOrNull {
                it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                    it.config.getOrNull(SemanticsActions.SetText)?.action != null
            })
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Unsaved line")))
            // Stay well inside the 300 ms typing debounce so only the visibility flush can save.
            repeat(2) { scene.render(System.nanoTime()).close(); delay(10) }
            visibility.value = false
            repeat(3) { scene.render(System.nanoTime()).close(); delay(10) }
            assertEquals("Unsaved line", fixture.stored?.text)
        }
    }

    @Test
    fun unknownRevisionStillExplainsDisabledSavingAndOffersVerification() {
        val fixture = EditorFixture().apply { revision = null }
        nativeSceneTest(800, 360, content = {
            TextEditorScreen(fixture.services, session, "synthetic-user", file, {})
        }) {
            assertTrue(has("Saving is disabled until the server version is verified."))
            assertTrue(has("Verify server version"))
            fixture.revision = "verified-etag"
            click("Verify server version")
            assertEquals(2, fixture.downloads)
            assertEquals(0, fixture.uploads)
        }
    }

    private class EditorFixture : TextEditorDraftStore {
        var stored: TextEditorDraft? = null
        var revision: String? = "etag"
        var downloads = 0
        var uploads = 0
        override suspend fun load() = stored
        override suspend fun save(draft: TextEditorDraft) { stored = draft }
        override suspend fun remove() { stored = null }

        val services = Proxy.newProxyInstance(NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "SyntheticEditorServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "textEditorDraftStore" -> this
                "downloadFile" -> { downloads++; NextcloudFileContent(byteArrayOf(), "text/markdown", revision) }
                "saveTextFile" -> { uploads++; SavedTextFile("new-etag", false) }
                else -> error("Unexpected synthetic editor operation: ${method.name}")
            }
        } as NextcloudPlatformServices
    }

    private val session = NextcloudSession("https://fixture.invalid", "synthetic-user", "synthetic-password")
    private val file = NextcloudFile("draft.md", "draft.md", false, "text/markdown", 0L, null, null, false)
}
