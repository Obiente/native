package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteFolderPickerTest {
    @Test
    fun `canonical paths preserve server names while manual input normalizes outer separators`() {
        assertEquals("", canonicalRemoteFolderPath(""))
        assertEquals(" Photos / Camera ", canonicalRemoteFolderPath(" Photos / Camera "))
        assertNull(canonicalRemoteFolderPath("/Photos/Camera/"))
        assertEquals("", normalizeRemoteFolderInput("/"))
        assertEquals("Photos/Camera", normalizeRemoteFolderInput("/Photos/Camera/"))
        assertEquals(" Photos ", normalizeRemoteFolderInput("/ Photos /"))
        assertEquals(" Photos ", normalizeRemoteFolderInput(" Photos "))
        assertEquals(" Photos /Camera ", normalizeRemoteFolderInput("/ Photos /Camera /"))
        assertEquals(
            listOf(RemoteFolderBreadcrumb("Files", ""), RemoteFolderBreadcrumb(" Photos ", " Photos ")),
            remoteFolderBreadcrumbs(requireNotNull(normalizeRemoteFolderInput("/ Photos /"))),
        )

        assertNull(canonicalRemoteFolderPath("Photos//Camera"))
        assertNull(canonicalRemoteFolderPath("Photos/../Secrets"))
        assertNull(canonicalRemoteFolderPath("Photos\\Camera"))
        assertNull(canonicalRemoteFolderPath("Photos/\u0000Camera"))
    }

    @Test
    fun `unopened advanced path draft cannot confirm the previously verified folder`() {
        assertEquals(
            true,
            canConfirmRemoteFolderSelection(
                currentPath = "Photos",
                networkConfirmedPath = "Photos",
                manualPathVisible = false,
                manualPathDraft = "Photos",
                busy = false,
            ),
        )
        assertEquals(
            false,
            canConfirmRemoteFolderSelection(
                currentPath = "Documents",
                networkConfirmedPath = null,
                manualPathVisible = false,
                manualPathDraft = "Documents",
                busy = false,
            ),
        )
        assertEquals(
            true,
            canConfirmRemoteFolderSelection(
                currentPath = "Documents",
                networkConfirmedPath = "Documents",
                manualPathVisible = false,
                manualPathDraft = "Documents",
                busy = false,
            ),
        )
        assertEquals(
            false,
            canConfirmRemoteFolderSelection(
                currentPath = "Photos",
                networkConfirmedPath = "Photos",
                manualPathVisible = true,
                manualPathDraft = "Documents",
                busy = false,
            ),
        )
        assertEquals(
            false,
            canConfirmRemoteFolderSelection(
                currentPath = "Photos",
                networkConfirmedPath = "Photos",
                manualPathVisible = true,
                manualPathDraft = "../Photos",
                busy = false,
            ),
        )
        assertEquals(
            true,
            canConfirmRemoteFolderSelection(
                currentPath = " Photos ",
                networkConfirmedPath = " Photos ",
                manualPathVisible = true,
                manualPathDraft = "/ Photos /",
                busy = false,
            ),
        )
    }

    @Test
    fun `breadcrumbs retain canonical path for files root and every ancestor`() {
        assertEquals(
            listOf(
                RemoteFolderBreadcrumb("Files", ""),
                RemoteFolderBreadcrumb("Photos", "Photos"),
                RemoteFolderBreadcrumb("Camera", "Photos/Camera"),
            ),
            remoteFolderBreadcrumbs("Photos/Camera"),
        )
        assertEquals(listOf(RemoteFolderBreadcrumb("Files", "")), remoteFolderBreadcrumbs(""))
        assertEquals("Photos", remoteFolderParentPath("Photos/Camera"))
        assertEquals("", remoteFolderParentPath("Photos"))
        assertNull(remoteFolderParentPath(""))
    }

    @Test
    fun `folder listing rejects files nested entries and duplicate server rows`() {
        val files = listOf(
            directory("Photos/Camera", "Camera"),
            directory("Photos/Screenshots", "Screenshots"),
            directory("Photos/Camera/2026", "2026"),
            directory("Photos/Camera", "Camera duplicate"),
            file("Photos/readme.txt", "readme.txt"),
            directory("../Outside", "Outside"),
        )

        assertEquals(
            listOf("Photos/Camera", "Photos/Screenshots"),
            remoteFolderDirectories(files, "Photos", "").map(NextcloudFile::path),
        )
        assertEquals(
            listOf("Photos/Screenshots"),
            remoteFolderDirectories(files, "Photos", "shots").map(NextcloudFile::path),
        )
    }

    @Test
    fun `directory row keys cannot collide with static picker item keys`() {
        val staticKeys = setOf(
            "breadcrumbs",
            "current-path",
            "search",
            "loading",
            "load-error",
            "refreshing",
            "listing-error",
            "empty-folders",
            "folder-actions",
            "create-folder",
            "manual-path",
            "selection-status",
        )
        val collisionPronePaths = staticKeys + setOf(
            "remote-folder:11:breadcrumbs",
            "folder-actions/selection-status",
        )
        val directoryKeys = collisionPronePaths.map(::remoteFolderRowKey)

        assertEquals(directoryKeys.size, directoryKeys.distinct().size)
        assertEquals(emptySet(), directoryKeys.toSet().intersect(staticKeys))
        assertEquals("remote-folder:11:breadcrumbs", remoteFolderRowKey("breadcrumbs"))
    }

    @Test
    fun `new folders are canonical children and unsafe names are rejected`() {
        assertEquals("Photos/Camera", newRemoteFolderPath("Photos", "Camera"))
        assertEquals("Photos", newRemoteFolderPath("", "Photos"))

        assertNull(newRemoteFolderPath("Photos", " Camera"))
        assertNull(newRemoteFolderPath("Photos", "../Camera"))
        assertNull(newRemoteFolderPath("Photos", "Camera/2026"))
        assertNull(newRemoteFolderPath("../Outside", "Camera"))
    }

    private fun directory(path: String, name: String) = nextcloudFile(path, name, isDirectory = true)

    private fun file(path: String, name: String) = nextcloudFile(path, name, isDirectory = false)

    private fun nextcloudFile(path: String, name: String, isDirectory: Boolean) = NextcloudFile(
        path = path,
        name = name,
        isDirectory = isDirectory,
        mimeType = if (isDirectory) "httpd/unix-directory" else "text/plain",
        size = null,
        lastModified = null,
        fileId = null,
        hasPreview = false,
    )
}
