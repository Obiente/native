package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.app.dynamicRootFormTargetsActiveSurface
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.FileVerifiedContractCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PantryFirstHouseCompatibilityTest {
    @Test
    fun `signed Pantry 0 29 exposes first house creation on the empty house collection`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return

        val descriptor = signedPantryDescriptor("0.29.0")
        val schema = descriptor.toNativeAppSchema()
        val read = assertNotNull(schema.action("house-index"))
        val create = assertNotNull(schema.action("house-create"))
        val activeView = assertNotNull(
            schema.views.singleOrNull { view -> view.sourceActionId == read.id },
        )
        val createView = assertNotNull(
            schema.views.singleOrNull { view -> view.sourceActionId == create.id },
        )
        val rootCreate = descriptor.planDynamicNavigation().rootFormActions.singleOrNull { action ->
            action.actionId == create.id
        }

        assertNotNull(
            rootCreate,
            "The signed root create form is missing. " +
                "readResource=${read.resourceId}; createResource=${create.resourceId}; " +
                "rootForms=${descriptor.planDynamicNavigation().rootFormActions}",
        )
        assertTrue(
            dynamicRootFormTargetsActiveSurface(
                action = create,
                formView = createView,
                activeView = activeView,
                activeReadAction = read,
                selectedCollectionState = null,
            ),
            "The verified first-house form is not attached to the empty house collection. " +
                "read=$read; create=$create; activeView=$activeView; createView=$createView",
        )
    }

    private fun signedPantryDescriptor(version: String): DynamicAppDescriptor {
        val cacheRoot = File(
            System.getProperty("java.io.tmpdir"),
            "nc-native-signed-pantry-contract-test-cache",
        )
        val contract = requireNotNull(
            SignedAppStoreContractAcquirer(
                catalogCache = FileAppStoreCatalogCache(File(cacheRoot, "catalogs")),
                verifiedContractCache = FileVerifiedContractCache(File(cacheRoot, "contracts")),
            ).acquire(ContractAcquisitionRequest("pantry", "34.0.3", version)),
        )
        return DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("pantry", "Pantry", version),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/ocs/v2.php/apps/pantry/api"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = contract.sourceUrl,
                    document = Json.parseToJsonElement(contract.document),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )
    }
}
