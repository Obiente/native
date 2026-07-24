package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeMediaCollectionLiveReadAuditTest {
    @Test
    fun `live album membership audit is GET only and never executes removal`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_COLLECTION_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())
        val observed = mutableListOf<NextcloudApiRequest>()
        suspend fun executeRead(request: NextcloudApiRequest): NextcloudApiResponse {
            assertTrue(request.method == NextcloudApiMethod.GET)
            assertTrue(request.body == null)
            observed += request
            return services.executeNextcloudApi(session, request)
        }

        val albums = parseMemoriesCollectionListResponse(
            executeRead(memoriesCollectionListRequest(NativeMediaCollectionType.Album)),
            NativeMediaCollectionType.Album,
        )
        val album = albums.firstOrNull { it.canBrowse && (it.itemCount ?: 0) > 0 }
            ?: error("The live account has no non-empty browseable album.")
        val index = parseMemoriesDayIndexResponse(
            executeRead(memoriesCollectionDayIndexRequest(album)),
            album,
        )
        val window = index.pageAfter(null, DEFAULT_MEMORIES_DAY_BATCH)
        val dayIds = window.days.map(NativeMediaDay::id)
        val pageItems = parseMemoriesDayContentsResponse(
            executeRead(memoriesCollectionDaysRequest(album, dayIds)),
            album,
            dayIds.toSet(),
        )
        val item = pageItems.firstOrNull() ?: error("The live album returned no media.")
        val plan = planRemoveItemFromMediaCollection(
            collection = album,
            item = item,
            currentUserId = session.loginName,
        )

        assertTrue(plan.enabled)
        assertTrue(requireNotNull(plan.request).method == NativeMediaCollectionTransportMethod.DELETE)
        assertTrue(observed.all { it.method == NextcloudApiMethod.GET && it.body == null })
        println(
            "album-membership-audit outcome=success reads=${observed.size} methods=get-only " +
                "albums=${albums.size} days=${index.days.size} page=${pageItems.size} " +
                "removal=planned-not-executed content=redacted",
        )
    }

    @Test
    fun `live album creation and membership plans use exact read identities without mutation`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_COLLECTION_AUDIT") != "1") return@runBlocking
        val delegate = DesktopNextcloudServices()
        val session = assertNotNull(delegate.loadSession())
        var mutationCalls = 0
        val services = object : NextcloudPlatformServices by delegate {
            override suspend fun executeMediaCollectionMutation(
                session: NextcloudSession,
                request: NativeMediaCollectionTransportRequest,
            ): NextcloudApiResponse {
                mutationCalls += 1
                error("Live collection mutations are forbidden in this audit.")
            }
        }
        val server = services.loadServerInfo(session)
        val catalog = NativeMediaCollectionReadService(services).loadCatalog(session)
        val ownAlbum = assertNotNull(
            catalog.albums.firstOrNull { album ->
                album.ownerUserId == server.userId && album.canBrowse
            },
        )
        val source = assertNotNull(
            services.listMedia(session, server.userId).firstOrNull { file ->
                !file.isDirectory && file.fileId != null && file.etag != null &&
                    file.permissions?.contains('R') == true
            },
        )

        val createPlan = planCreateMediaAlbum(
            name = "Nextcloud Native audit candidate",
            currentUserId = server.userId,
        )
        val addPlan = planAddFileToMediaCollection(ownAlbum, source, server.userId)

        assertTrue(createPlan.enabled)
        assertEquals(NativeMediaCollectionTransportMethod.MKCOL, createPlan.request?.method)
        assertTrue(addPlan.enabled, addPlan.disabledReason)
        assertEquals(NativeMediaCollectionTransportMethod.COPY, addPlan.request?.method)
        assertEquals(0, mutationCalls)
        println(
            "album-write-plan-audit outcome=success mutations=0 " +
                "create=planned add=planned source=redacted album=redacted",
        )
    }
}
