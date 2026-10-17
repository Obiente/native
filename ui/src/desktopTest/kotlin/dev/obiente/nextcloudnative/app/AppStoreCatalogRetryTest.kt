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
            0 to AppStoreCatalogPolicy.UseCache,
            1 to AppStoreCatalogPolicy.RefreshCatalog,
            3 to AppStoreCatalogPolicy.RefreshCatalog,
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
    fun theCachedPolicyKeepsTheOriginalServices() {
        val services = services(mutableListOf())
        assertSame(services, services.forDiscoveryAttempt(0))
        assertSame(services, services.withAppStoreCatalogPolicy(AppStoreCatalogPolicy.UseCache))
    }

    private fun services(requests: MutableList<AppStoreContractRequest>): NextcloudPlatformServices {
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
                return AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoMatchingRelease)
            }
        }
    }
}
