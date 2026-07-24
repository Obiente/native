package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChoresLiveContractCompatibilityTest {
    @Test
    fun `signed Chores routes expose household reads and only proven guarded deletion`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val contract = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("chores", "34.0.1", "0.1.0"),
            ),
        )
        assertEquals(OpenApiContractSourceKind.SignedAppPackage, contract.sourceKind)
        assertEquals(VerifiedContractKind.VerifiedReadRoutes, contract.contractKind)

        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("chores", "Chores", "0.1.0"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/chores"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = contract.sourceUrl,
                    document = Json.parseToJsonElement(contract.document),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )
        val teamRead = descriptor.action(HttpMethod.GET, "/apps/chores/api/v1.0/team")
        val choreRead = descriptor.action(HttpMethod.GET, "/apps/chores/api/v1.0/team/{teamId}/chores")
        val workRead = descriptor.action(HttpMethod.GET, "/apps/chores/api/v1.0/team/{teamId}/work")
        val choreDelete = descriptor.action(
            HttpMethod.DELETE,
            "/apps/chores/api/v1.0/team/{teamId}/chores/{choreId}",
        )

        assertEquals(ActionIntent.list, choreRead.intent)
        assertEquals(ActionIntent.list, workRead.intent)
        assertTrue(descriptor.actions.none { action ->
            action.binding.method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
        })
        assertEquals(ActionRisk.destructive, choreDelete.risk)
        assertTrue(choreDelete.requiresConfirmation)
        assertTrue(choreDelete.binding.body == null)
        assertEquals(setOf("teamId", "choreId"), choreDelete.binding.pathParameters.mapTo(linkedSetOf()) { it.name })

        val root = descriptor.planDynamicNavigation().rootDestinations
        assertTrue(
            root.any { destination -> destination.actionId == teamRead.id },
            "root=${root.map { destination -> destination.actionId }}",
        )
        val teamPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = teamRead.resourceId,
                recordId = "opaque-team",
                actionSafeIdentity = false,
            ),
        )
        assertTrue(teamPlan.contextualChildDestinations.any { destination ->
            destination.actionId == choreRead.id && destination.pathParameterValues["teamId"] == "opaque-team"
        })
        assertTrue(teamPlan.contextualChildDestinations.any { destination ->
            destination.actionId == workRead.id && destination.pathParameterValues["teamId"] == "opaque-team"
        })
        val observedChorePlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = choreRead.resourceId,
                recordId = "opaque-chore",
                parameterValues = mapOf("teamId" to "opaque-team"),
                actionSafeIdentity = false,
            ),
        )
        assertTrue(observedChorePlan.contextualFormActions.none { form -> form.actionId == choreDelete.id })
        assertEquals(
            null,
            descriptor.resolveDynamicRecordReadParameters(
                choreDelete.id,
                DynamicResourceRecordContext(
                    resourceId = choreRead.resourceId,
                    recordId = "opaque-chore",
                    actionSafeIdentity = false,
                ),
            ),
        )
        assertFalse(descriptor.validationErrors().isNotEmpty())
    }

    private fun DynamicAppDescriptor.action(method: HttpMethod, path: String): DynamicAction =
        actions.single { action -> action.binding.method == method && action.binding.path == path }
}
