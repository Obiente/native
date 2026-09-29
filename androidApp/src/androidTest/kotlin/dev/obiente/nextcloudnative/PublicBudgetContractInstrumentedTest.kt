package dev.obiente.nextcloudnative

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptorCompiler
import dev.obiente.nextcloudnative.nativeui.model.DynamicDiscoveryInput
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.OpenApiTrust
import dev.obiente.nextcloudnative.nativeui.model.requireValid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in public package probe. Does not access account credentials, state, or private server data. */
@RunWith(AndroidJUnit4::class)
class PublicBudgetContractInstrumentedTest {
    @Test
    fun exactPublicPackageCompilesOnAndroid() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicBudgetProbe") == "true")
        var stage = "acquire"
        try {
            report(stage, "started")
            val contract = SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("budget", "34.0.3", "2.54.0"),
            ) ?: throw AssertionError("No contract returned")
            report(stage, "${contract.sourceKind.name}:${contract.contractKind.name}")
            stage = "parse"
            val document = Json.parseToJsonElement(contract.document) as? JsonObject
                ?: throw AssertionError("Expected document object")
            val trust = when (contract.sourceKind) {
                OpenApiContractSourceKind.SignedAppPackage -> OpenApiTrust.nextcloudSignedAppPackage
                OpenApiContractSourceKind.SignedCompatibleAppPackage -> OpenApiTrust.nextcloudSignedCompatibleAppPackage
                OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag -> OpenApiTrust.appStoreLinkedExactGitHubTag
                OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag -> OpenApiTrust.appStoreLinkedCompatibleGitHubTag
            }
            stage = "compile"
            report(stage, "started")
            val descriptor = DynamicAppDescriptorCompiler().compile(
                DynamicDiscoveryInput(
                    app = AppIdentity("budget", "Budget", contract.appVersion),
                    endpointPolicy = EndpointPolicy(
                        serverOrigin = "https://fixture.invalid:8443",
                        approvedApiPrefixes = listOf(
                            "/apps/budget", "/ocs/v1.php/apps/budget", "/ocs/v2.php/apps/budget",
                            "/index.php/apps/budget", "/ocs/v2.php/cloud/capabilities",
                        ),
                    ),
                    advertisedOpenApi = AdvertisedOpenApi(
                        documentUrl = contract.sourceUrl, document = document, trust = trust,
                    ),
                ),
            ).requireValid()
            report(stage, "resources=${descriptor.resources.size};actions=${descriptor.actions.size}")
            check(descriptor.resources.isNotEmpty())
        } catch (failure: Throwable) {
            // This diagnostic boundary never forwards exception messages, payloads, or cause objects.
            val classes = generateSequence(failure) { it.cause }.take(5)
                .joinToString(",") { it.javaClass.simpleName.take(96) }
            report(stage, "failure:$classes")
            throw AssertionError("Public Budget contract probe failed at $stage ($classes)")
        }
    }

    private fun report(stage: String, status: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("budgetProbeStage", stage)
            putString("budgetProbeStatus", status)
        })
    }
}
