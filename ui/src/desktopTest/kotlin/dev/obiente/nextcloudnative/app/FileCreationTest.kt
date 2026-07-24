package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileCreationTest {
    @Test
    fun markdownGetsExtensionAndPreservesParent() {
        val plan = planFileCreation(FileCreationKind.Markdown, "Notes/Vault", "Daily note")

        assertEquals("Daily note.md", plan.name)
        assertEquals("Notes/Vault/Daily note.md", plan.path)
    }

    @Test
    fun explicitExtensionIsPreserved() {
        assertEquals(
            "README.markdown",
            planFileCreation(FileCreationKind.Markdown, "", "README.markdown").name,
        )
    }

    @Test
    fun folderDoesNotReceiveAnExtension() {
        assertEquals(
            "Projects",
            planFileCreation(FileCreationKind.Folder, "", " Projects ").path,
        )
    }

    @Test
    fun traversalAndPathSeparatorsAreRejected() {
        listOf("..", "child/name", "child\\name").forEach { name ->
            assertFailsWith<IllegalArgumentException> {
                planFileCreation(FileCreationKind.Text, "Notes", name)
            }
        }
    }

    @Test
    fun pathAndNameBoundsAreEnforced() {
        assertFailsWith<IllegalArgumentException> {
            planFileCreation(FileCreationKind.Text, "", "a".repeat(256))
        }
        assertFailsWith<IllegalArgumentException> {
            planFileCreation(FileCreationKind.Text, "a".repeat(4090), "note")
        }
    }
}
