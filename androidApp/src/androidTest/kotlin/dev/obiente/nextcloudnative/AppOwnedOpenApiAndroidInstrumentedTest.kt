package dev.obiente.nextcloudnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.AppStoreContractAcquisitionException
import dev.obiente.nextcloudnative.app.AppStoreContractFailureKind
import dev.obiente.nextcloudnative.app.acquireAppStoreContract
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import java.lang.reflect.Method
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
        // Initialize each packaged top-level facade and companion owner on ART and ICU.
        listOf(
            "AppOwnedOpenApiContractKt", "ContractAcquisitionFailureKt", "SignedAppStoreContractAcquirerKt",
            "StaticPhpParametersKt", "StaticRouteContractKt", "TarArchivePathKt", "VerifiedChoresContractKt",
            "VerifiedContractCacheKt", "VerifiedExactWriteAdaptersKt", "SignedAppStoreContractAcquirer",
            "FileAppStoreCatalogCache", "DynamicApiResponseCache", "FileVerifiedContractCache",
        ).forEach { name -> Class.forName("dev.obiente.nextcloudnative.contracts.$name") }
        Class.forName("dev.obiente.nextcloudnative.app.JvmAppStoreContractAcquisitionKt")

        val failed = try {
            acquireAppStoreContract(ContractAcquisitionRequest("example", "34.0.3", "1.0.0")) {
                throw NoClassDefFoundError("dev.obiente.nextcloudnative.contracts.SyntheticKt")
            }
            null
        } catch (failure: AppStoreContractAcquisitionException) {
            failure
        }
        assertEquals(AppStoreContractFailureKind.RuntimeIncompatible, failed?.kind)
        assertFalse(failed?.message.orEmpty().contains("SyntheticKt"))
    }

    private fun Method.accepts(server: String): Boolean {
        val document = """{"openapi":"3.1.1","servers":[{"url":"$server"}],"paths":{"/items":{}}}"""
        return invoke(null, "example", "openapi.json", document) as Boolean
    }
}
