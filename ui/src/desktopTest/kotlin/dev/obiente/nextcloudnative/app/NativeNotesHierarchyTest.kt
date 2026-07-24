package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeNotesHierarchyTest {
    @Test
    fun nestedCategoriesBecomeImmediateFoldersAndBreadcrumbs() {
        val notes = listOf(
            note(1, "Root", ""),
            note(2, "Project", "Work"),
            note(3, "Sprint", "Work/Nextcloud"),
            note(4, "Personal", "Home/Journal"),
        )

        val root = buildNativeNotesLocation(notes, "")
        assertEquals(listOf("Home", "Work"), root.folders.map(NativeNoteFolder::name))
        assertEquals(listOf("Root"), root.notes.map(NextcloudNote::title))
        assertEquals(2, root.folders.first { it.name == "Work" }.descendantNoteCount)

        val work = buildNativeNotesLocation(notes, "Work")
        assertEquals(listOf("Notes", "Work"), work.breadcrumbs.map(NativeNoteBreadcrumb::label))
        assertEquals(listOf("Nextcloud"), work.folders.map(NativeNoteFolder::name))
        assertEquals(listOf("Project"), work.notes.map(NextcloudNote::title))
        assertEquals("", noteFolderParent("Work"))
        assertEquals("Work/Cloud", noteFolderRenameTarget("Work/Nextcloud", "Cloud"))
    }

    @Test
    fun markdownMetadataProvidesUsefulListSignals() {
        val metadata = note(
            1,
            "Checklist",
            "Work",
            """
                # Release checklist

                - [x] Build
                - [ ] Test phone
                Some **useful** context.
            """.trimIndent(),
        ).markdownMetadata()

        assertEquals("Release checklist", metadata.preview)
        assertEquals(1, metadata.completedTasks)
        assertEquals(2, metadata.totalTasks)
        assertTrue(metadata.wordCount >= 7)
    }

    @Test
    fun folderAwareRequestsCarryLabelsAndNeverRawFolderIds() {
        val create = createNoteRequest("Sprint plan", "# Plan", "Work/Nextcloud")
        assertEquals(NextcloudApiMethod.POST, create.method)
        val createBody = Json.parseToJsonElement(requireNotNull(create.body).decodeToString()).jsonObject
        assertEquals("Work/Nextcloud", createBody.getValue("category").jsonPrimitive.content)
        assertFalse(createBody.containsKey("folderId"))

        assertFailsWith<IllegalArgumentException> { normalizeNoteCategory("../Secrets") }
        assertFailsWith<IllegalArgumentException> { noteFolderRenameTarget("Work", "Other/Nested") }
    }

    private fun note(
        id: Long,
        title: String,
        category: String,
        content: String? = null,
    ) = NextcloudNote(
        id = id,
        title = title,
        modified = id,
        category = category,
        favorite = false,
        readOnly = false,
        content = content,
        etag = "etag-$id",
        internalPath = "$category/$title.md",
    )
}
