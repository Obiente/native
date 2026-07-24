package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActivityLiveReadAuditTest {
    @Test
    fun `live activity refresh and paging audit is GET only and sanitized`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_ACTIVITY_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val observed = mutableListOf<NextcloudApiRequest>()

        suspend fun load(cursor: Long?): NextcloudActivityPage =
            loadNextcloudActivityPage(since = cursor, limit = 5) { request ->
                assertEquals(NextcloudApiMethod.GET, request.method)
                assertTrue(request.body == null)
                observed += request
                services.executeNextcloudApi(session, request)
            }

        val first = load(null)
        var state = ActivityTimelineState().beginActivityRefresh().applyActivityRefresh(first)
        if (first.hasMore && first.nextSince != null) {
            state = state.beginNextActivityPage().applyNextActivityPage(load(first.nextSince))
        }
        state = state.beginActivityRefresh().applyActivityRefresh(load(null))

        assertTrue(state.initialized)
        val installedAppIds = services.loadServerInfo(session).apps.mapTo(linkedSetOf(), NextcloudAppEntry::id)
        val feed = buildActivityFeedPresentation(state.activities)
        assertEquals(state.activities.size, feed.matchedCount)
        state.activities.forEach { activity ->
            val action = activity.activityOpenAction(installedAppIds, session.serverUrl)
            activity.objectId?.takeIf(String::isNotBlank)?.let { objectId ->
                assertTrue(action?.label?.contains(objectId) != true)
            }
            assertTrue(action?.sameOriginUrl?.startsWith(session.serverUrl.trimEnd('/')) != false)
        }
        assertTrue(observed.all { request ->
            request.method == NextcloudApiMethod.GET &&
                request.relativePath == "/ocs/v2.php/apps/activity/api/v2/activity" &&
                request.body == null
        })
        println("activity-audit outcome=success requests=get-only refresh=verified paging=verified content=redacted")
    }
}
