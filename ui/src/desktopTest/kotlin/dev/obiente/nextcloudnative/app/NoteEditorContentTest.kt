package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import com.mikepenz.markdown.model.MarkdownTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoteEditorContentTest {
    @Test
    fun shortEditorKeepsSaveVisibleAndDetailsPreserveDraftAndPreview() {
        val title = mutableStateOf("Synthetic notebook")
        val category = mutableStateOf("Test folder")
        val content = mutableStateOf(TextFieldValue("# Small heading\n\nSynthetic body"))
        val mode = mutableStateOf(NoteViewMode.Edit)
        var saves = 0
        var typography: MarkdownTypography? = null
        nativeSceneTest(780, 260, content = {
            typography = nativeMarkdownTypography()
            NoteEditorContent(title.value, category.value, content.value, listOf("Test folder"), mode.value,
                readOnly = false, mutationInProgress = false, canSave = true, saving = false,
                previewAvailable = true, contentBytes = 40, loadError = null, saveError = null,
                onTitleChange = { title.value = it }, onCategoryChange = { category.value = it },
                onContentChange = { content.value = it }, onViewModeChange = { mode.value = it }, onSave = { saves++ })
        }) {
            assertTrue(has("Details"))
            assertFalse(has("Folder"))
            val editor = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == content.value.text
            })
            assertTrue(editor.boundsInRoot.height >= 80f, "Short layouts must leave room for writing")
            click("Save")
            assertEquals(1, saves)
            click("Details")
            assertTrue(has("Note details"))
            replaceText("Synthetic notebook", "Edited title")
            click("Done")
            assertEquals("Edited title", title.value)
            assertEquals("Test folder", category.value)
            click("Preview")
            assertEquals(NoteViewMode.Preview, mode.value)
            assertTrue(has("Small heading"))
            click("Edit")
            assertEquals(NoteViewMode.Edit, mode.value)
            assertEquals("# Small heading\n\nSynthetic body", content.value.text)
            val styles = assertNotNull(typography)
            assertTrue(styles.h1.fontSize.value <= 28f)
            assertTrue(styles.h1.fontSize.value >= styles.h2.fontSize.value)
            assertTrue(styles.h2.fontSize.value >= styles.h3.fontSize.value)
        }
    }
}
