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
    fun `folder refresh readiness keeps cached content visible after a failure`() {
        assertEquals(
            PhotoFolderInventoryReadiness.Loading,
            photoFolderInventoryReadiness(hasInventory = false, refreshError = null),
        )
        assertEquals(
            PhotoFolderInventoryReadiness.InitialFailure,
            photoFolderInventoryReadiness(hasInventory = false, refreshError = "Offline"),
        )
        assertEquals(
            PhotoFolderInventoryReadiness.Ready,
            photoFolderInventoryReadiness(hasInventory = true, refreshError = null),
        )
        assertEquals(
            PhotoFolderInventoryReadiness.Stale,
            photoFolderInventoryReadiness(hasInventory = true, refreshError = "Offline"),
        )
    }

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
    fun `explicit Memories child folders remain visible without invented counts`() {
        val result = buildPhotoFolderBrowseResult(
            inventory = listOf(
                directory("Photos/Empty album").copy(
                    originalAccessAllowed = false,
                    davPathAuthoritative = false,
                ),
                file(
                    path = "Photos/photo.jpg",
                    mimeType = "image/jpeg",
                ),
            ),
            state = PhotoFolderBrowseState(
                selectedFolderPath = "Photos",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
            ),
        )

        val folder = result.folders.single()
        assertEquals("Photos/Empty album", folder.path)
        assertFalse(folder.countsAuthoritative)
        assertEquals(0, folder.directMediaCount)
        assertEquals(listOf("photo.jpg"), result.media.map { it.cover.name })
    }

    @Test
    fun `paged repository preserves explicit root child folders`() {
        val repository = PhotoFolderInventoryRepository()

        repository.tryAddPage(
            listOf(
                directory("Camera").copy(
                    originalAccessAllowed = false,
                    davPathAuthoritative = false,
                ),
                directory("Pictures").copy(
                    originalAccessAllowed = false,
                    davPathAuthoritative = false,
                ),
            ),
        )

        val result = repository.browse(
            PhotoFolderBrowseState(
                selectedFolderPath = "",
                scope = PhotoFolderBrowseScope.DirectMediaAndSubfolders,
            ),
        )
        assertEquals(listOf("Camera", "Pictures"), result.folders.map(PhotoFolderSummary::path))
        assertTrue(result.folders.none(PhotoFolderSummary::countsAuthoritative))
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
        assertEquals("Grid view", photoFolderViewModeLabel(PhotoFolderViewMode.Grid))
        assertEquals("List view", photoFolderViewModeLabel(PhotoFolderViewMode.List))
    }

    @Test
    fun `pasted search controls are sanitized before browse state construction`() {
        val sanitized = sanitizePhotoFolderQuery("Trips\t2026\nSummer\u0000")

        assertEquals("Trips 2026 Summer ", sanitized)
        assertTrue(sanitized.none(Char::isISOControl))
        assertEquals(sanitized, PhotoFolderBrowseState(query = sanitized).query)
        assertEquals(
            MAX_PHOTO_FOLDER_QUERY_LENGTH,
            sanitizePhotoFolderQuery("x".repeat(MAX_PHOTO_FOLDER_QUERY_LENGTH + 10)).length,
        )
    }

    @Test
    fun `folder media order parses RFC 1123 timestamps instead of sorting their text`() {
        val older = file(
            path = "Photos/older.jpg",
            mimeType = "image/jpeg",
            modified = "Mon, 27 Jul 2026 10:00:00 GMT",
        )
        val newer = file(
            path = "Photos/newer.jpg",
            mimeType = "image/jpeg",
            modified = "Fri, 31 Jul 2026 10:00:00 GMT",
        )

        val result = buildPhotoFolderBrowseResult(
            inventory = listOf(older, newer),
            state = PhotoFolderBrowseState(
                selectedFolderPath = "Photos",
                scope = PhotoFolderBrowseScope.DirectMediaOnly,
            ),
        )

        assertEquals(listOf("newer.jpg", "older.jpg"), result.media.map { it.cover.name })
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

    @Test
    fun `cached inventory is bounded normalized and excludes stale statuses`() {
        val first = file(path = "Photos/Camera/one.jpg", mimeType = "image/jpeg")
        val second = file(path = "Photos/Camera/two.jpg", mimeType = "image/jpeg")

        val snapshot = buildPhotoFolderInventorySnapshot(
            inventory = listOf(first, second),
            backupStatuses = mapOf(
                "/Photos/Camera/one.jpg/" to MediaBackupStatus.BackedUp,
                "Photos/removed.jpg" to MediaBackupStatus.Failed,
            ),
            maximumRecords = 2,
        )

        assertEquals(listOf(first.path, second.path), snapshot.files.map(NextcloudFile::path))
        assertEquals(
            mapOf(first.path to MediaBackupStatus.BackedUp),
            snapshot.backupStatuses,
        )
        assertFailsWith<IllegalArgumentException> {
            buildPhotoFolderInventorySnapshot(
                inventory = listOf(first, second),
                backupStatuses = emptyMap(),
                maximumRecords = 1,
            )
        }
    }

    @Test
    fun `cached status refresh only changes files in the retained inventory`() {
        val file = file(path = "Photos/Camera/one.jpg", mimeType = "image/jpeg")
        val snapshot = buildPhotoFolderInventorySnapshot(
            inventory = listOf(file),
            backupStatuses = mapOf(file.path to MediaBackupStatus.Pending),
        )

        val refreshed = snapshot.withUpdatedBackupStatuses(
            mapOf(
                "/${file.path}/" to MediaBackupStatus.BackedUp,
                "Photos/not-cached.jpg" to MediaBackupStatus.Failed,
            ),
        )

        assertEquals(mapOf(file.path to MediaBackupStatus.BackedUp), refreshed.backupStatuses)
        assertEquals(snapshot.files, refreshed.files)
    }

    @Test
    fun `paged summary preserves hierarchy and logical counts across timeline pages`() {
        val state = PhotoFolderBrowseState(
            selectedFolderPath = "Photos",
            scope = PhotoFolderBrowseScope.RecursiveMedia,
        )
        val inventory = buildPhotoFolderPagedInventory(
            pages = listOf(
                listOf(
                    file(path = "Photos/Camera/shot.jpg", mimeType = "image/jpeg"),
                ),
                listOf(
                    file(path = "Photos/Camera/shot.RAF", mimeType = "image/x-fuji-raf"),
                    file(path = "Photos/Trips/older.jpg", mimeType = "image/jpeg"),
                    file(path = "Photos/Trips/Archive/oldest.jpg", mimeType = "image/jpeg"),
                ),
            ),
            state = state,
            maximumMediaRecords = 8,
            maximumFolders = 8,
            maximumSelectedMediaRecords = 8,
        )

        assertEquals(4, inventory.summary.indexedMediaRecordCount)
        assertEquals(3, inventory.summary.rootRecursiveMediaCount)
        assertEquals(1, inventory.summary.folder("Photos/Camera")?.directMediaCount)
        assertEquals(2, inventory.summary.folder("Photos/Trips")?.recursiveMediaCount)
        assertEquals(1, inventory.summary.folder("Photos/Trips")?.directChildFolderCount)

        val result = buildPhotoFolderBrowseResult(inventory, state)
        assertTrue(result.folders.isEmpty())
        assertEquals(3, result.recursiveMediaCount)
        assertEquals(3, result.media.size)
        assertEquals(2, result.media.single { it.cover.name == "shot.jpg" }.members.size)
    }

    @Test
    fun `paged summary normalizes paths and deduplicates records across pages`() {
        val sparse = file(
            path = "/Photos/Camera/shot.jpg/",
            mimeType = null,
            fileId = null,
            hasPreview = false,
            etag = null,
        )
        val rich = sparse.copy(
            path = "Photos/Camera/shot.jpg",
            mimeType = "image/jpeg",
            fileId = 42L,
            hasPreview = true,
            etag = "rich",
        )
        val accumulator = PhotoFolderSummaryAccumulator(
            selectedFolderPath = "Photos/Camera",
            selectedScope = PhotoFolderBrowseScope.DirectMediaOnly,
            maximumMediaRecords = 4,
            maximumFolders = 4,
            maximumSelectedMediaRecords = 4,
        )

        accumulator.addPage(listOf(sparse))
        accumulator.addPage(
            listOf(
                rich,
                file(
                    path = "/Photos/Camera/shot.RAF/",
                    mimeType = "image/x-fuji-raf",
                ),
            ),
        )

        val inventory = accumulator.snapshot()
        assertEquals(2, inventory.summary.indexedMediaRecordCount)
        assertEquals(1, inventory.summary.rootRecursiveMediaCount)
        assertEquals(
            listOf("Photos/Camera/shot.RAF", "Photos/Camera/shot.jpg"),
            inventory.selectedMediaFiles.map(NextcloudFile::path).sorted(),
        )
        assertEquals(42L, inventory.selectedMediaFiles.single { it.path.endsWith(".jpg") }.fileId)
        assertEquals(
            1,
            buildPhotoFolderBrowseResult(
                inventory,
                PhotoFolderBrowseState(
                    selectedFolderPath = "Photos/Camera",
                    scope = PhotoFolderBrowseScope.DirectMediaOnly,
                ),
            ).media.size,
        )
    }

    @Test
    fun `paged summary and selected media windows enforce independent bounds`() {
        val selectedWindow = PhotoFolderSummaryAccumulator(
            selectedFolderPath = "Photos",
            selectedScope = PhotoFolderBrowseScope.RecursiveMedia,
            maximumMediaRecords = 3,
            maximumFolders = 3,
            maximumSelectedMediaRecords = 1,
        )
        selectedWindow.addPage(
            listOf(
                file(path = "Photos/one.jpg", mimeType = "image/jpeg"),
                file(path = "Photos/two.jpg", mimeType = "image/jpeg"),
            ),
        )
        assertEquals(2, selectedWindow.snapshot().summary.rootRecursiveMediaCount)
        assertEquals(1, selectedWindow.snapshot().selectedMediaFiles.size)

        val mediaBound = PhotoFolderSummaryAccumulator(
            selectedFolderPath = "",
            selectedScope = PhotoFolderBrowseScope.RecursiveMedia,
            maximumMediaRecords = 1,
            maximumFolders = 3,
            maximumSelectedMediaRecords = 1,
        )
        mediaBound.addPage(listOf(file(path = "Photos/one.jpg", mimeType = "image/jpeg")))
        assertFailsWith<IllegalArgumentException> {
            mediaBound.addPage(listOf(file(path = "Photos/two.jpg", mimeType = "image/jpeg")))
        }
        assertEquals(1, mediaBound.snapshot().summary.indexedMediaRecordCount)

        val folderBound = PhotoFolderSummaryAccumulator(
            selectedFolderPath = "",
            selectedScope = PhotoFolderBrowseScope.RecursiveMedia,
            maximumMediaRecords = 3,
            maximumFolders = 1,
            maximumSelectedMediaRecords = 1,
        )
        folderBound.addPage(listOf(file(path = "Photos/one.jpg", mimeType = "image/jpeg")))
        assertFailsWith<IllegalArgumentException> {
            folderBound.addPage(
                listOf(file(path = "Photos/Nested/two.jpg", mimeType = "image/jpeg")),
            )
        }
        assertEquals(listOf("Photos"), folderBound.snapshot().summary.folders.map { it.path })
    }

    @Test
    fun `paged inventory can retarget folder navigation without losing page summaries`() {
        val pages = listOf(
            listOf(
                file(path = "Photos/Camera/current.jpg", mimeType = "image/jpeg"),
            ),
            listOf(
                file(path = "Photos/Trips/older.jpg", mimeType = "image/jpeg"),
                file(path = "Photos/Trips/Nested/oldest.jpg", mimeType = "image/jpeg"),
            ),
        )
        val rootState = PhotoFolderBrowseState(
            selectedFolderPath = "Photos",
            scope = PhotoFolderBrowseScope.RecursiveMedia,
        )
        val nestedState = PhotoFolderBrowseState(
            selectedFolderPath = "Photos/Trips",
            scope = PhotoFolderBrowseScope.DirectMediaOnly,
        )

        val rootInventory = buildPhotoFolderPagedInventory(pages, rootState)
        val nestedInventory = buildPhotoFolderPagedInventory(pages, nestedState)

        assertEquals(rootInventory.summary, nestedInventory.summary)
        assertEquals(3, rootInventory.selectedMediaFiles.size)
        assertEquals(
            listOf("Photos/Trips/older.jpg"),
            nestedInventory.selectedMediaFiles.map(NextcloudFile::path),
        )
        assertEquals(
            listOf("older.jpg"),
            buildPhotoFolderBrowseResult(nestedInventory, nestedState).media.map { it.cover.name },
        )
    }

    @Test
    fun `folder summary includes accepted records beyond the timeline display limit`() {
        val recentPage = List(MAXIMUM_MEDIA_SEARCH_RESULTS) { index ->
            file(
                path = "Photos/Camera/recent-$index.jpg",
                mimeType = "image/jpeg",
            )
        }
        val olderPage = listOf(
            file(path = "Photos/Archive/older.jpg", mimeType = "image/jpeg"),
        )

        val inventory = buildPhotoFolderPagedInventory(
            pages = listOf(recentPage, olderPage),
            state = PhotoFolderBrowseState(scope = PhotoFolderBrowseScope.FoldersOnly),
        )

        assertEquals(MAXIMUM_MEDIA_SEARCH_RESULTS + 1, inventory.summary.indexedMediaRecordCount)
        assertEquals(MAXIMUM_MEDIA_SEARCH_RESULTS + 1, inventory.summary.rootRecursiveMediaCount)
        assertEquals(1, inventory.summary.folder("Photos/Archive")?.directMediaCount)
        assertTrue(inventory.selectedMediaFiles.isEmpty())
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
