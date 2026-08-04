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
            listOf(
                file("Project Notes.md"),
                file("project-plan.md"),
                file("Notes.txt"),
                file("Brief.pdf", path = "Projects/Project Phoenix/Brief.pdf"),
            ),
            "PROJECT notes",
        )

        assertEquals(listOf("Project Notes.md"), result.map(NextcloudFile::name))
    }

    @Test
    fun searchMatchesPathMimeTypeAndOwner() {
        val result = presentFiles(
            listOf(
                file("Brief.pdf", path = "Projects/Phoenix/Brief.pdf", mimeType = "application/pdf"),
                file("Portrait.raw", owner = "Morgan Lee"),
                file("Notes.txt"),
            ),
            "phoenix",
        )

        assertEquals(listOf("Brief.pdf"), result.map(NextcloudFile::name))
        assertEquals(
            listOf("Portrait.raw"),
            presentFiles(
                listOf(
                    file("Brief.pdf", path = "Projects/Phoenix/Brief.pdf", mimeType = "application/pdf"),
                    file("Portrait.raw", owner = "Morgan Lee"),
                    file("Notes.txt"),
                ),
                "morgan",
            ).map(NextcloudFile::name),
        )
    }

    @Test
    fun filtersFavoritesMediaAndOfflineFiles() {
        val files = listOf(
            file("Projects", directory = true, favorite = true),
            file("Portrait.jpg", mimeType = "image/jpeg"),
            file("Brief.pdf", mimeType = "application/pdf"),
        )

        assertEquals(
            listOf("Projects"),
            presentFiles(files, "", filter = FileWorkspaceFilter.Favorites).map(NextcloudFile::name),
        )
        assertEquals(
            listOf("Portrait.jpg"),
            presentFiles(files, "", filter = FileWorkspaceFilter.Media).map(NextcloudFile::name),
        )
        assertEquals(
            listOf("Brief.pdf"),
            presentFiles(
                files,
                "",
                filter = FileWorkspaceFilter.Offline,
                offlinePaths = setOf("Brief.pdf"),
            ).map(NextcloudFile::name),
        )
    }

    @Test
    fun breadcrumbsKeepNavigablePaths() {
        assertEquals(
            listOf(
                FileBreadcrumb("All files", ""),
                FileBreadcrumb("Projects", "Projects"),
                FileBreadcrumb("Phoenix", "Projects/Phoenix"),
            ),
            fileBreadcrumbs("Projects/Phoenix"),
        )
    }

    private fun file(
        name: String,
        directory: Boolean = false,
        path: String = name,
        mimeType: String? = null,
        favorite: Boolean = false,
        owner: String? = null,
    ) = NextcloudFile(
        path = path,
        name = name,
        isDirectory = directory,
        mimeType = mimeType,
        size = null,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        favorite = favorite,
        ownerDisplayName = owner,
    )
}
