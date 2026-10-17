package dev.obiente.nextcloudnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.obiente.nextcloudnative.app.AppStoreContractAcquisition
import dev.obiente.nextcloudnative.app.AppStoreContractAcquisitionException
import dev.obiente.nextcloudnative.app.AppStoreContractFailureKind
import dev.obiente.nextcloudnative.app.AppStoreContractRequest
import dev.obiente.nextcloudnative.app.AppStoreContractUnavailableReason
import dev.obiente.nextcloudnative.app.acquireAppStoreContract
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import java.io.File
import java.lang.reflect.Method
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the contract JVM module on Android's regex/runtime implementation using only synthetic JSON. */
@RunWith(AndroidJUnit4::class)
class AppOwnedOpenApiAndroidInstrumentedTest {
    @Test
    fun appOwnedContractInitializerAndPortableServerValidationWorkOnAndroid() {
        // This internal contract boundary belongs to another Gradle module. Reflection intentionally
        // initializes the actual packaged class rather than testing a copied parser expression.
        Class.forName("dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirerKt")
        Class.forName("dev.obiente.nextcloudnative.contracts.StaticRouteContractKt")
        val owner = Class.forName("dev.obiente.nextcloudnative.contracts.AppOwnedOpenApiContractKt")
        val validate = owner.getDeclaredMethod(
            "isAppOwnedOpenApiDocument", String::class.java, String::class.java, String::class.java,
        )
        for (server in listOf(
            "/apps/example", "https://{host}/apps/example", "https://{host}:{port}/apps/example",
            "http://{hostname}:8080/index.php/apps/example",
        )) {
            assertTrue("A portable app server was rejected", validate.accepts(server))
        }
        for (server in listOf(
            "https://foreign.example/apps/example", "https://{tenant}.example/apps/example",
            "https://{host/apps/example", "https://host}/apps/example", "https://{{host}}/apps/example",
            "https://{host}:{}/apps/example", "https://{host}:123456/apps/example",
        )) {
            assertFalse("An invalid or foreign server was accepted", validate.accepts(server))
        }
    }

    @Test
    fun everyContractStaticInitializerAndFailureBoundaryWorkOnAndroid() {
        // Initialize each packaged top-level facade and companion owner on ART and ICU. The JVM
        // unit test ContractModuleInitializationTest fails when a facade is missing from this list.
        listOf(
            "AppOwnedOpenApiContractKt", "AppStoreCatalogCacheKt", "AppStoreCatalogReleasesKt",
            "ContractAcquisitionFailureKt", "LinkedSourceArchiveKt",
            "NextcloudAppPackageTrustVerifierKt", "OpenApiYamlParserKt", "SignedAppStoreContractAcquirerKt",
            "SignedPackageTarReaderKt",
            "StaticPhpParametersKt", "StaticRouteContractKt", "TarArchivePathKt", "VerifiedChoresContractKt",
            "VerifiedContractCacheKt", "VerifiedExactWriteAdaptersKt", "SignedAppStoreContractAcquirer",
            "FileAppStoreCatalogCache", "DynamicApiResponseCache", "FileVerifiedContractCache",
        ).forEach { name -> Class.forName("dev.obiente.nextcloudnative.contracts.$name") }
        Class.forName("dev.obiente.nextcloudnative.app.JvmAppStoreContractAcquisitionKt")

        val failed = try {
            acquireAppStoreContract(AppStoreContractRequest("example", "34.0.3", "1.0.0")) { _, _ ->
                throw NoClassDefFoundError("dev.obiente.nextcloudnative.contracts.SyntheticKt")
            }
            null
        } catch (failure: AppStoreContractAcquisitionException) {
            failure
        }
        assertEquals(AppStoreContractFailureKind.RuntimeIncompatible, failed?.kind)
        assertFalse(failed?.message.orEmpty().contains("SyntheticKt"))
    }

    @Test
    fun realCatalogPathAndYamlParserWorkOnAndroid() {
        val cacheRoot = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "catalog-cache-test")
        cacheRoot.deleteRecursively()
        // A regular file where the cache directory belongs makes catalog publication fail on ART.
        check(cacheRoot.mkdirs() && File(cacheRoot, "blocked").createNewFile())
        try {
            MockWebServer().use { server ->
                server.start()
                repeat(2) { server.enqueue(MockResponse(body = """[{"id":"other","certificate":"c","releases":[]}]""")) }
                val acquirer = SignedAppStoreContractAcquirer(
                    httpClient = OkHttpClient(),
                    appStoreBaseUrl = server.url("api/v1").toString(),
                    requireHttps = false,
                    catalogCache = FileAppStoreCatalogCache(File(File(cacheRoot, "blocked"), "catalogs")),
                )
                val outcome = acquireAppStoreContract(
                    AppStoreContractRequest("example", "34.0.3", "1.0.0"),
                    acquirer::acquireOutcome,
                )
                assertEquals(
                    AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoMatchingRelease),
                    outcome,
                )
                assertEquals(2, server.requestCount)
            }
        } finally {
            cacheRoot.deleteRecursively()
        }

        val parseYaml = Class.forName("dev.obiente.nextcloudnative.contracts.OpenApiYamlParserKt")
            .getDeclaredMethod("parseYamlObject", String::class.java)
        val yaml = "openapi: 3.0.1\nservers: [{ url: /apps/example }]\npaths:\n  /items: {}\n"
        val document = parseYaml.invoke(null, yaml) as JSONObject
        assertEquals("3.0.1", document.optString("openapi"))
        assertTrue(document.getJSONObject("paths").has("/items"))
    }

    private fun Method.accepts(server: String): Boolean {
        val document = """{"openapi":"3.1.1","servers":[{"url":"$server"}],"paths":{"/items":{}}}"""
        return invoke(null, "example", "openapi.json", document) as Boolean
    }
}
