package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeckLiveContractCompatibilityTest {
    @Test
    fun `signed Deck contract keeps board stack and card hierarchy`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val contract = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("deck", "34.0.1", "1.18.2"),
            ),
        )
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("deck", "Deck", "1.18.2"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf(
                        "/apps/deck",
                        "/ocs/v1.php/apps/deck",
                        "/ocs/v2.php/apps/deck",
                        "/index.php/apps/deck",
                    ),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/deck#${contract.specFile}",
                    document = Json.parseToJsonElement(contract.document),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )
        val roots = descriptor.planDynamicNavigation().rootDestinations
        val boardRoot = assertNotNull(roots.firstOrNull { destination ->
            destination.resourceId.resourceWord() in setOf("board", "boards")
        }, "roots=$roots actions=${descriptor.actions.map { it.id to it.binding.path }}")
        val boardChildren = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = boardRoot.resourceId,
                recordId = "7",
                fieldValues = mapOf("id" to "7"),
                actionSafeIdentity = false,
            ),
        ).contextualChildDestinations
        val stacks = assertNotNull(boardChildren.firstOrNull { destination ->
            destination.resourceId.resourceWord() in setOf("stack", "stacks")
        }, "boardChildren=$boardChildren layouts=${descriptor.layouts} actions=${descriptor.actions.map { it.id to (it.resourceId to it.binding.path) }}")
        assertTrue(stacks.pathParameterValues.values.contains("7"))

        val cardDetail = assertNotNull(descriptor.layouts.firstOrNull { layout ->
            layout.kind == LayoutKind.detail && layout.resourceId.resourceWord() in setOf("card", "cards")
        })
        val cardParameters = assertNotNull(
            descriptor.resolveDynamicRecordReadParameters(
                assertNotNull(cardDetail.sourceActionId),
                DynamicResourceRecordContext(
                    resourceId = cardDetail.resourceId,
                    recordId = "42",
                    fieldValues = mapOf("boardId" to "7", "stackId" to "10", "id" to "42"),
                    actionSafeIdentity = false,
                ),
            ),
        )
        assertTrue(cardParameters.values.contains("42"))
        assertTrue(cardParameters.keys.none { it.equals("apiVersion", ignoreCase = true) })
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    private fun String.resourceWord(): String = lowercase().substringAfterLast('.').substringAfterLast('-')
}
