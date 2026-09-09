package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextcloudPlatformTest {
    @Test
    fun `chunk upload URL encodes the account and validates the durable session ID`() {
        assertEquals(
            "https://cloud.example/remote.php/dav/uploads/user%20name/01234567-89ab-cdef-0123-456789abcdef",
            buildNextcloudChunkUploadUrl(
                "https://cloud.example/",
                "user name",
                "01234567-89ab-cdef-0123-456789abcdef",
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            buildNextcloudChunkUploadUrl("https://cloud.example", "user", "../other-session")
        }
    }

    @Test
    fun encodesEveryWebDavPathSegmentWithoutTreatingSpacesAsFormData() {
        assertEquals(
            "https://cloud.example/remote.php/dav/files/alice%40example/Photos/July%20%26%20August/%E6%97%85%E8%A1%8C.jpg",
            buildNextcloudFileUrl(
                serverUrl = "https://cloud.example/",
                userId = "alice@example",
                path = "/Photos/July & August/旅行.jpg",
            ),
        )
    }

    @Test
    fun rejectsRelativePathSegments() {
        assertFailsWith<IllegalArgumentException> {
            buildNextcloudFileUrl("https://cloud.example", "user", "Documents/../secrets.txt")
        }
    }

    @Test
    fun clampsPreviewDimensionsToSafeRange() {
        assertEquals(MIN_PREVIEW_DIMENSION, boundedPreviewDimension(-1))
        assertEquals(1600, boundedPreviewDimension(1600))
        assertEquals(MAX_PREVIEW_DIMENSION, boundedPreviewDimension(20_000))
    }

    @Test
    fun clampsActivityPageSizeToServerSafeRange() {
        assertEquals(1, boundedActivityLimit(0))
        assertEquals(DEFAULT_ACTIVITY_LIMIT, boundedActivityLimit(DEFAULT_ACTIVITY_LIMIT))
        assertEquals(MAX_ACTIVITY_LIMIT, boundedActivityLimit(5_000))
    }

    @Test
    fun acceptsBoundedSameOriginDynamicApiRequests() {
        val request = NextcloudApiRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/ocs/v2.php/apps/example/api/items",
            queryParameters = mapOf("format" to "json"),
            contentType = "application/json",
            body = "{\"title\":\"Example\"}".encodeToByteArray(),
            ocsApiRequest = true,
        )

        assertEquals(request, request.requireSafe())
        assertEquals(
            "https://cloud.example/ocs/v2.php/apps/example/api/items?format=json",
            buildNextcloudApiUrl("https://cloud.example/", request),
        )
    }

    @Test
    fun forceNetworkReadsKeepTheSameCredentialFreeCacheIdentity() {
        val cached = NextcloudApiRequest(
            method = NextcloudApiMethod.GET,
            relativePath = "/index.php/apps/deck/api/v1.1/boards/7",
        )
        val authoritative = cached.copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)

        assertEquals(NextcloudApiCachePolicy.PreferCache, cached.cachePolicy)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, authoritative.cachePolicy)
        assertEquals(cached.dynamicReadCacheIdentity(), authoritative.dynamicReadCacheIdentity())
        assertEquals(authoritative, authoritative.requireSafe())
    }

    @Test
    fun rejectsDynamicApiTraversalAndEmbeddedOrigins() {
        listOf(
            "https://other.example/api",
            "//other.example/api",
            "/ocs/v2.php/apps/example/../admin",
            "/ocs/v2.php/apps/example/%2E%2E/admin",
            "/ocs/v2.php/apps/example/api?redirect=https://other.example",
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                NextcloudApiRequest(NextcloudApiMethod.GET, path).requireSafe()
            }
        }
    }

    @Test
    fun rejectsUnboundedDynamicApiResponses() {
        assertFailsWith<IllegalArgumentException> {
            NextcloudApiRequest(
                method = NextcloudApiMethod.GET,
                relativePath = "/ocs/v2.php/apps/example/api/items",
                maximumResponseBytes = MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES + 1,
            ).requireSafe()
        }
    }
}
