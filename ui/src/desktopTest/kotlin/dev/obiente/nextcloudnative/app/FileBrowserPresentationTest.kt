package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileBrowserPresentationTest {
    @Test
    fun foldersComeFirstAndNamesSortNaturally() {
        val result = presentFiles(
            listOf(file("zeta.txt"), file("beta", directory = true), file("Alpha.txt"), file("alpha", directory = true)),
            "",
        )

        assertEquals(listOf("alpha", "beta", "Alpha.txt", "zeta.txt"), result.map(NextcloudFile::name))
    }

    @Test
    fun allSearchTermsMustMatchIgnoringCase() {
        val result = presentFiles(
            listOf(file("Project Notes.md"), file("project-plan.md"), file("Notes.txt")),
            "PROJECT notes",
        )

        assertEquals(listOf("Project Notes.md"), result.map(NextcloudFile::name))
    }

    private fun file(name: String, directory: Boolean = false) = NextcloudFile(
        path = name,
        name = name,
        isDirectory = directory,
        mimeType = null,
        size = null,
        lastModified = null,
        fileId = null,
        hasPreview = false,
    )
}
