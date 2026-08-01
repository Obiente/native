package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteFolderPickerTest {
    @Test
    fun `selective sync identities are resolved relative to the mapped remote root`() {
        assertEquals(
            "Camera/2026/photo.raf",
            fileSyncSelectionRelativePath("Photos", "Photos/Camera/2026/photo.raf"),
        )
        assertEquals("Documents/report.pdf", fileSyncSelectionRelativePath("", "Documents/report.pdf"))
        assertNull(fileSyncSelectionRelativePath("Photos", "Documents/report.pdf"))
        assertNull(fileSyncSelectionRelativePath("Photos", "Photos"))
        assertNull(fileSyncSelectionRelativePath("Photos", "Photos/../Secrets"))
    }

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

    @Test
    fun `missing suggested destinations recover through ancestors and retain all segments`() {
        val missing = NextcloudFileListingHttpException(404)

        assertEquals("Photos", missingRemoteFolderParentAfter(missing, "Photos/Camera"))
        assertEquals("", missingRemoteFolderParentAfter(missing, "Photos"))
        assertNull(missingRemoteFolderParentAfter(missing, ""))

        assertEquals(
            MissingRemoteFolderDestination(
                intendedPath = "Photos/Camera",
                accessibleParentPath = "",
                pathsToCreate = listOf("Photos", "Photos/Camera"),
            ),
            missingRemoteFolderDestination("Photos/Camera", ""),
        )
        assertEquals(
            MissingRemoteFolderDestination(
                intendedPath = "Photos/Camera/2026",
                accessibleParentPath = "Photos",
                pathsToCreate = listOf("Photos/Camera", "Photos/Camera/2026"),
            ),
            missingRemoteFolderDestination("Photos/Camera/2026", "Photos"),
        )
        assertEquals(
            MissingRemoteFolderDestination(
                intendedPath = "Photos",
                accessibleParentPath = "",
                pathsToCreate = listOf("Photos"),
            ),
            missingRemoteFolderDestination("Photos", ""),
        )
    }

    @Test
    fun `only typed not found responses trigger suggested destination recovery`() {
        assertNull(
            missingRemoteFolderParentAfter(
                NextcloudFileListingHttpException(403),
                "Photos/Camera",
            ),
        )
        assertNull(
            missingRemoteFolderParentAfter(
                IllegalStateException("WebDAV folder listing failed (HTTP 404)."),
                "Photos/Camera",
            ),
        )
        assertNull(missingRemoteFolderDestination("Photos/Camera", "Documents"))
        assertNull(missingRemoteFolderDestination("../Photos", ""))
    }

    @Test
    fun `folder listing HTTP errors retain validated status`() {
        val failure = NextcloudFileListingHttpException(404)

        assertEquals(404, failure.status)
        assertTrue(failure.message.orEmpty().contains("404"))
        assertFailsWith<IllegalArgumentException> {
            NextcloudFileListingHttpException(207)
        }
    }

    @Test
    fun `folder operations preserve coroutine cancellation`() {
        val cancellation = CancellationException("folder changed")

        val thrown = assertFailsWith<CancellationException> {
            Result.failure<Unit>(cancellation).rethrowRemoteFolderCancellation()
        }

        assertSame(cancellation, thrown)
        assertNull(
            Result.failure<Unit>(IllegalStateException("offline"))
                .rethrowRemoteFolderCancellation()
                .getOrNull(),
        )
    }

    @Test
    fun `selection status explains that the folder is still loading`() {
        assertEquals(
            "Loading this Nextcloud folder before it can be selected.",
            remoteFolderSelectionStatus(
                loading = true,
                currentPath = "Photos",
                canConfirm = false,
                listingSource = null,
                manualPathVisible = false,
                manualPathDraft = "Photos",
            ),
        )
        assertEquals(
            "/Photos/Camera will be created when you confirm.",
            remoteFolderSelectionStatus(
                loading = false,
                currentPath = "Photos",
                canConfirm = true,
                listingSource = NextcloudFileListingSource.Network,
                manualPathVisible = false,
                manualPathDraft = "Photos",
                missingDestinationPath = "Photos/Camera",
            ),
        )
        assertEquals(
            "Open and verify the advanced path before selecting it.",
            remoteFolderSelectionStatus(
                loading = false,
                currentPath = "Photos",
                canConfirm = false,
                listingSource = NextcloudFileListingSource.Network,
                manualPathVisible = true,
                manualPathDraft = "Documents",
                missingDestinationPath = "Photos/Camera",
            ),
        )
    }

    @Test
    fun `missing destination creation requires the verified path and matching manual draft`() {
        val missing = requireNotNull(
            missingRemoteFolderDestination(
                intendedPath = "Photos/Camera",
                accessibleParentPath = "Photos",
            ),
        )

        assertTrue(
            canCreateMissingRemoteFolderDestination(
                missingDestination = missing,
                networkConfirmedPath = "Photos",
                currentPath = "Photos",
                manualPathVisible = false,
                manualPathDraft = "Photos",
                busy = false,
            ),
        )
        assertEquals(
            false,
            canCreateMissingRemoteFolderDestination(
                missingDestination = missing,
                networkConfirmedPath = "Photos",
                currentPath = "Photos",
                manualPathVisible = true,
                manualPathDraft = "Documents",
                busy = false,
            ),
        )
        assertEquals(
            false,
            canCreateMissingRemoteFolderDestination(
                missingDestination = missing,
                networkConfirmedPath = null,
                currentPath = "Photos",
                manualPathVisible = false,
                manualPathDraft = "Photos",
                busy = false,
            ),
        )
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
