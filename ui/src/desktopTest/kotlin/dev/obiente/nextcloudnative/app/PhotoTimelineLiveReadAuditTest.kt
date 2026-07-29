package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in saved-session audit. Every request is a bounded, read-only SearchDAV request.
 */
class PhotoTimelineLiveReadAuditTest {
    @Test
    fun `timeline and folder inventory can page the same account independently`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_PHOTO_TIMELINE_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val server = services.loadServerInfo(session)

        listOf(
            PhotoMediaQueryOwner.Timeline,
            PhotoMediaQueryOwner.FolderInventory,
        ).forEach { owner ->
            var cursor: PhotoTimelineCursor? = null
            var loadedEntries = 0
            var loadedPages = 0
            do {
                val page = services.listMediaTimelinePage(
                    session = session,
                    userId = server.userId,
                    cursor = cursor,
                    queryOwner = owner,
                )
                loadedEntries += page.entries.size
                loadedPages += 1
                cursor = page.nextCursor
            } while (cursor != null && loadedPages < 4)
            assertTrue(loadedEntries > 0)
        }
    }
}
