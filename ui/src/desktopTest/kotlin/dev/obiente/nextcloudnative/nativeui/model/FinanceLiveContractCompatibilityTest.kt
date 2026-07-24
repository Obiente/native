package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FinanceLiveContractCompatibilityTest {
    @Test
    fun `signed Cospend contract keeps project finance children without project loops`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = acquireDescriptor(
            appId = "cospend",
            version = "4.0.2",
            approvedPrefixes = listOf("/ocs/v2.php/apps/cospend/api"),
        )
        val root = descriptor.planDynamicNavigation().rootDestinations.single()
        val rootAction = descriptor.actions.single { it.id == root.actionId }

        assertEquals("projects", root.resourceId)
        assertTrue(rootAction.binding.path.endsWith("/api/v1/projects"))

        val projectPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = root.resourceId,
                recordId = "project-7",
                parameterValues = mapOf("projectId" to "project-7"),
                actionSafeIdentity = false,
            ),
        )
        val childPaths = projectPlan.contextualChildDestinations.mapTo(mutableSetOf()) { destination ->
            descriptor.actions.single { it.id == destination.actionId }.binding.path
        }

        assertTrue(childPaths.any { it.endsWith("/projects/{id}/bills") })
        assertTrue(childPaths.any { it.endsWith("/projects/{id}/members") })
        assertFalse(projectPlan.contextualChildDestinations.any { it.resourceId == root.resourceId })
        assertTrue(projectPlan.contextualChildDestinations.all {
            "project-7" in it.pathParameterValues.values
        })

        val schema = descriptor.toNativeAppSchema()
        assertEquals(NativeComponent.dashboard, schema.views.single { it.id == "projects.list" }.component)
        assertEquals(NativeComponent.dashboard, schema.views.single { it.id == "bills.list" }.component)
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun `signed Budget routes keep finance roots and nested account reads without account loops`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val descriptor = acquireDescriptor(
            appId = "budget",
            version = "2.39.1",
            approvedPrefixes = listOf("/apps/budget", "/index.php/apps/budget"),
        )
        val rootPaths = descriptor.planDynamicNavigation().rootDestinations.associateWith { destination ->
            descriptor.actions.single { it.id == destination.actionId }.binding.path
        }

        listOf(
            "/api/accounts",
            "/api/bills",
            "/api/budget-snapshots",
            "/api/categories",
            "/api/categories/recurring-budgets",
        ).forEach { suffix ->
            assertTrue(rootPaths.values.any { it.endsWith(suffix) }, "missing root suffix=$suffix")
        }

        val accountRoot = rootPaths.entries.single { (_, path) -> path.endsWith("/api/accounts") }.key
        val accountPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = accountRoot.resourceId,
                recordId = "account-7",
                actionSafeIdentity = false,
            ),
        )
        val accountChildPaths = accountPlan.contextualChildDestinations.mapTo(mutableSetOf()) { destination ->
            descriptor.actions.single { it.id == destination.actionId }.binding.path
        }

        assertTrue(accountChildPaths.any { it.endsWith("/accounts/{id}/balance-history") })
        assertTrue(accountChildPaths.any { it.endsWith("/accounts/{id}/metrics") })
        assertFalse(accountPlan.contextualChildDestinations.any {
            it.resourceId == accountRoot.resourceId
        })
        assertTrue(accountPlan.contextualChildDestinations.filter { destination ->
            descriptor.actions.single { it.id == destination.actionId }.binding.path.let { path ->
                path.endsWith("/accounts/{id}/balance-history") || path.endsWith("/accounts/{id}/metrics")
            }
        }.all { "account-7" in it.pathParameterValues.values })
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    private fun acquireDescriptor(
        appId: String,
        version: String,
        approvedPrefixes: List<String>,
    ): DynamicAppDescriptor {
        val contract = assertNotNull(
            SignedAppStoreContractAcquirer(
                catalogCache = FileAppStoreCatalogCache(
                    File(System.getProperty("user.home"), ".cache/nextcloud-native/contracts/catalogs"),
                ),
            ).acquire(ContractAcquisitionRequest(appId, "34.0.1", version)),
        )
        return DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity(appId, appId, version),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = approvedPrefixes,
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/$appId#${contract.specFile}",
                    document = Json.parseToJsonElement(contract.document),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )
    }
}
