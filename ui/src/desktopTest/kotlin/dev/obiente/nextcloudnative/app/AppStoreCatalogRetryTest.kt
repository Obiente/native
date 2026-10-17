package dev.obiente.nextcloudnative.app

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AppStoreCatalogRetryTest {
    private val session = NextcloudSession("https://fixture.invalid", "fixture", "synthetic")
    private val app = NextcloudAppEntry("example", "Example", "/apps/example/")

    @Test
    fun theFirstDiscoveryUsesTheCachedCatalogAndAnExplicitRetryRefreshesIt() = runBlocking {
        for ((attempt, expected) in listOf(
            0 to AppStoreCachePolicy.UseCache,
            1 to AppStoreCachePolicy.Refresh,
            3 to AppStoreCachePolicy.Refresh,
        )) {
            val requests = mutableListOf<AppStoreContractRequest>()
            discoverDynamicAppDescriptor(
                services = services(requests).forDiscoveryAttempt(attempt),
                session = session,
                app = app,
                serverVersion = "34.0.3",
                installedAppVersionHint = "1.0.0",
            )
            assertEquals(
                listOf(AppStoreContractRequest("example", "34.0.3", "1.0.0", expected)),
                requests,
                "attempt $attempt",
            )
        }
    }

    @Test
    fun anUnusableAcquiredContractDiscardsExactlyThatCachedContract() = runBlocking {
        for (attempt in listOf(0, 2)) {
            val requests = mutableListOf<AppStoreContractRequest>()
            val discovery = discoverDynamicAppDescriptor(
                services = services(requests, AppStoreContractAcquisition.Acquired(unreadableContract())).forDiscoveryAttempt(attempt),
                session = session,
                app = app,
                serverVersion = "34.0.3",
                installedAppVersionHint = "1.0.0",
            )
            assertEquals(DynamicContractFallbackReason.UnsupportedContract, discovery.fallbackReason)
            val first = if (attempt == 0) AppStoreCachePolicy.UseCache else AppStoreCachePolicy.Refresh
            // A retry decorator never turns the discard request into a refresh.
            assertEquals(
                listOf(first, AppStoreCachePolicy.DiscardCachedContract),
                requests.map(AppStoreContractRequest::cachePolicy),
                "attempt $attempt",
            )
            assertEquals(setOf(AppStoreContractRequest("example", "34.0.3", "1.0.0")), requests.map { it.copy(cachePolicy = AppStoreCachePolicy.UseCache) }.toSet())
        }
    }

    @Test
    fun theCachedPolicyKeepsTheOriginalServices() {
        val services = services(mutableListOf())
        assertSame(services, services.forDiscoveryAttempt(0))
        assertSame(services, services.withAppStoreCachePolicy(AppStoreCachePolicy.UseCache))
    }

    private fun unreadableContract() = AcquiredOpenApiContract(
        appId = "example",
        appVersion = "1.0.0",
        contractVersion = "1.0.0",
        specFile = "openapi.json",
        document = "not json",
        packageUrl = "https://fixture.invalid/example.tar.gz",
        sourceUrl = "https://fixture.invalid/example.tar.gz#openapi.json",
        sourceKind = AcquiredOpenApiContractSourceKind.SignedAppPackage,
    )

    private fun services(
        requests: MutableList<AppStoreContractRequest>,
        result: AppStoreContractAcquisition = AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoMatchingRelease),
    ): NextcloudPlatformServices {
        val unavailable = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { _, method, _ -> error("Unexpected ${method.name}") } as NextcloudPlatformServices
        return object : NextcloudPlatformServices by unavailable {
            override fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) = Unit

            override suspend fun executeNextcloudApi(session: NextcloudSession, request: NextcloudApiRequest) =
                NextcloudApiResponse(404, ByteArray(0), "application/json", etag = null)

            override suspend fun acquireSignedOpenApiContract(request: AppStoreContractRequest): AppStoreContractAcquisition {
                requests += request
                return result
            }
        }
    }
}
