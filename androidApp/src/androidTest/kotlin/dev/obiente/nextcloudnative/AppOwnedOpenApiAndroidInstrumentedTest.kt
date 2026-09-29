package dev.obiente.nextcloudnative

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Method
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

    private fun Method.accepts(server: String): Boolean {
        val document = """{"openapi":"3.1.1","servers":[{"url":"$server"}],"paths":{"/items":{}}}"""
        return invoke(null, "example", "openapi.json", document) as Boolean
    }
}
