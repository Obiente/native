package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileBrowserChronologicalSortingTest {
    @Test
    fun `modified sorting compares DAV timestamps chronologically`() {
        val files = listOf(
            file("monday.txt", "Mon, 03 Jun 2024 09:00:00 GMT"),
            file("friday.txt", "Fri, 31 May 2024 18:00:00 GMT"),
            file("sunday.txt", "Sun, 02 Jun 2024 12:00:00 GMT"),
        )

        assertEquals(
            listOf("friday.txt", "sunday.txt", "monday.txt"),
            presentFiles(files, "", sortMode = FileSortMode.Modified).map(NextcloudFile::name),
        )
        assertEquals(
            listOf("monday.txt", "sunday.txt", "friday.txt"),
            presentFiles(
                files,
                "",
                sortMode = FileSortMode.Modified,
                sortDirection = FileSortDirection.Descending,
            ).map(NextcloudFile::name),
        )
    }

    private fun file(name: String, modified: String) = NextcloudFile(
        path = name,
        name = name,
        isDirectory = false,
        mimeType = "text/plain",
        size = 1,
        lastModified = modified,
        fileId = null,
        hasPreview = false,
    )
}
