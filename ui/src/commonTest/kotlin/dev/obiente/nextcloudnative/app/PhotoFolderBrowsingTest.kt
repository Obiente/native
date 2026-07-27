package dev.obiente.nextcloudnative.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoFolderBrowsingTest {
    @Test
    fun `folder scopes infer hierarchy and count logical media once`() {
        val inventory = photoInventory()

        val foldersOnly = buildPhotoFolderBrowseResult(
            inventory,
            PhotoFolderBrowseState(
                selectedFolderPath = "Photos",
                scope = PhotoFolderBrowseScope.FoldersOnly,
            ),
        )

        assertEquals(listOf("Photos/Camera", "Photos/Screenshots"), foldersOnly.folders.map { it.path })
        assertFalse(foldersOnly.folders.any { it.path == "Documents" })
        assertTrue(foldersOnly.media.isEmpty())
        val camera = foldersOnly.folders.first()
        assertEquals(2, camera.directMediaCount)
        assertEquals(3, camera.recursiveMediaCount)
        assertEquals(1, camera.directChildFolderCount)
        assertEquals(4, foldersOnly.recursiveMediaCount)

        val mixed = buildPhotoFolderBrowseResult(
            inventory,
            PhotoFolderBrowseState(
                selectedFolderPath = "Photos/Camera",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
            ),
        )
        assertEquals(listOf("Photos/Camera/Nested"), mixed.folders.map { it.path })
        assertEquals(listOf("clip.mp4", "shot.jpg"), mixed.media.map { it.cover.name })
        assertEquals(2, mixed.media.single { it.cover.name == "shot.jpg" }.members.size)

        val direct = buildPhotoFolderBrowseResult(
            inventory,
            PhotoFolderBrowseState(
                selectedFolderPath = "Photos/Camera",
                scope = PhotoFolderBrowseScope.DirectMediaOnly,
            ),
        )
        assertTrue(direct.folders.isEmpty())
        assertEquals(mixed.media, direct.media)

        val recursive = buildPhotoFolderBrowseResult(
            inventory,
            PhotoFolderBrowseState(
                selectedFolderPath = "Photos/Camera",
                scope = PhotoFolderBrowseScope.RecursiveMedia,
            ),
        )
        assertTrue(recursive.folders.isEmpty())
        assertEquals(listOf("clip.mp4", "shot.jpg", "nested.jpg"), recursive.media.map { it.cover.name })
    }

    @Test
    fun `search matches folder names and paths without becoming filename search`() {
        val inventory = photoInventory() + file(
            path = "Photos/Screenshots/camera-manual.jpg",
            mimeType = "image/jpeg",
            modified = "2026-07-20T09:00:00Z",
        )

        val folderMatches = buildPhotoFolderBrowseResult(
            inventory,
            PhotoFolderBrowseState(
                query = "camera",
                scope = PhotoFolderBrowseScope.FoldersOnly,
            ),
        )
        assertEquals(
            listOf("Photos/Camera", "Photos/Camera/Nested"),
            folderMatches.folders.map { it.path },
        )

        val mediaMatches = buildPhotoFolderBrowseResult(
            inventory,
            PhotoFolderBrowseState(
                query = "photos/camera",
                scope = PhotoFolderBrowseScope.RecursiveMedia,
            ),
        )
        assertEquals(listOf("clip.mp4", "shot.jpg", "nested.jpg"), mediaMatches.media.map { it.cover.name })
        assertFalse(mediaMatches.media.any { it.cover.name == "camera-manual.jpg" })
    }

    @Test
    fun `duplicate reconciliation and stack order do not depend on input order`() {
        val sparse = file(
            path = "Photos/Camera/shot.jpg",
            mimeType = null,
            modified = "2026-07-23T10:00:00Z",
            fileId = null,
            hasPreview = false,
            etag = null,
        )
        val rich = sparse.copy(
            mimeType = "image/jpeg",
            fileId = 7L,
            hasPreview = true,
            etag = "rich",
        )
        val raw = file(
            path = "Photos/Camera/shot.RAF",
            mimeType = "image/x-fuji-raf",
            modified = "2026-07-23T10:00:00Z",
        )
        val state = PhotoFolderBrowseState(
            selectedFolderPath = "Photos",
            scope = PhotoFolderBrowseScope.RecursiveMedia,
        )

        val forward = buildPhotoFolderBrowseResult(listOf(sparse, raw, rich), state)
        val reversed = buildPhotoFolderBrowseResult(listOf(rich, raw, sparse), state)

        assertEquals(forward, reversed)
        val stack = forward.media.single()
        assertEquals(2, stack.members.size)
        assertEquals(7L, stack.cover.fileId)
        assertTrue(stack.hasRaw)
    }

    @Test
    fun `grid and list preferences serialize without navigation ambiguity`() {
        val state = PhotoFolderBrowseState(
            selectedFolderPath = "Photos/Trips",
            query = "summer",
            scope = PhotoFolderBrowseScope.RecursiveMedia,
            preference = PhotoFolderBrowsePreference(PhotoFolderViewMode.List),
        )

        val encoded = Json.encodeToString(state)
        assertEquals(state, Json.decodeFromString<PhotoFolderBrowseState>(encoded))
        assertTrue("\"viewMode\":\"List\"" in encoded)
    }

    @Test
    fun `folder scope controls use user facing labels`() {
        assertEquals("Folders only", photoFolderScopeLabel(PhotoFolderBrowseScope.FoldersOnly))
        assertEquals(
            "Folder and photos",
            photoFolderScopeLabel(PhotoFolderBrowseScope.DirectMediaAndSubfolders),
        )
        assertEquals("Photos in folder", photoFolderScopeLabel(PhotoFolderBrowseScope.DirectMediaOnly))
        assertEquals("All nested photos", photoFolderScopeLabel(PhotoFolderBrowseScope.RecursiveMedia))
    }

    @Test
    fun `unsafe paths invalid search and unbounded inventories are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PhotoFolderBrowseState(selectedFolderPath = "../Photos")
        }
        assertFailsWith<IllegalArgumentException> {
            PhotoFolderBrowseState(query = "bad\u0000query")
        }
        assertFailsWith<IllegalArgumentException> {
            PhotoFolderBrowseState(selectedFolderPath = List(129) { "nested" }.joinToString("/"))
        }
        assertFailsWith<IllegalArgumentException> {
            buildPhotoFolderBrowseResult(
                listOf(file(path = "Photos/../private.jpg", mimeType = "image/jpeg")),
                PhotoFolderBrowseState(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            buildPhotoFolderBrowseResult(
                inventory = listOf(
                    file(path = "Photos/one.jpg", mimeType = "image/jpeg"),
                    file(path = "Photos/two.jpg", mimeType = "image/jpeg"),
                ),
                state = PhotoFolderBrowseState(),
                maximumRecords = 1,
            )
        }
    }

    private fun photoInventory(): List<NextcloudFile> = listOf(
        directory("Photos"),
        directory("Photos/Camera"),
        file(
            path = "Photos/Camera/shot.jpg",
            mimeType = "image/jpeg",
            modified = "2026-07-23T10:00:00Z",
        ),
        file(
            path = "Photos/Camera/shot.RAF",
            mimeType = "image/x-fuji-raf",
            modified = "2026-07-23T10:00:00Z",
        ),
        file(
            path = "Photos/Camera/clip.mp4",
            mimeType = "video/mp4",
            modified = "2026-07-24T10:00:00Z",
        ),
        // No directory row: a SEARCH response still needs to expose this inferred folder.
        file(
            path = "Photos/Camera/Nested/nested.jpg",
            mimeType = "image/jpeg",
            modified = "2026-07-22T10:00:00Z",
        ),
        file(
            path = "Photos/Screenshots/screen.png",
            mimeType = "image/png",
            modified = "2026-07-21T10:00:00Z",
        ),
        file(path = "Documents/ignore.txt", mimeType = "text/plain"),
    )

    private fun directory(path: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        lastModified = null,
        fileId = path.hashCode().toLong().let { if (it == 0L) 1L else it },
        hasPreview = false,
        etag = "folder-${path.length}",
    )

    private fun file(
        path: String,
        mimeType: String?,
        modified: String? = "2026-07-20T10:00:00Z",
        fileId: Long? = path.hashCode().toLong().let { if (it == 0L) 1L else it },
        hasPreview: Boolean = true,
        etag: String? = "etag-${path.length}",
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = mimeType,
        size = 1_024L,
        lastModified = modified,
        fileId = fileId,
        hasPreview = hasPreview,
        etag = etag,
    )
}
