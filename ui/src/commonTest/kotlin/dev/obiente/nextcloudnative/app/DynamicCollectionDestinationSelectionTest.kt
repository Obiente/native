package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicCollectionDestinationSelectionTest {
    @Test
    fun `process restoration reuses last known contract identity in read only mode`() {
        val plan = planDynamicContractResume(
            liveServerVersion = null,
            lastKnownServerVersion = "32.0.5",
            lastKnownInstalledAppVersion = "0.23.0",
        )

        assertEquals("32.0.5", plan.serverVersion)
        assertEquals("0.23.0", plan.installedAppVersionHint)
        assertFalse(plan.serverVersionVerified)
    }

    @Test
    fun `live server identity supersedes the restored version and enables verification`() {
        val plan = planDynamicContractResume(
            liveServerVersion = "33.0.0",
            lastKnownServerVersion = "32.0.5",
            lastKnownInstalledAppVersion = "0.23.0",
        )

        assertEquals("33.0.0", plan.serverVersion)
        assertEquals("0.23.0", plan.installedAppVersionHint)
        assertTrue(plan.serverVersionVerified)
    }

    @Test
    fun `metadata fallback demotes a retained verified contract to last known read only`() {
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("workspace", "Workspace", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test"),
        )
        val cached = DynamicDescriptorDiscovery(
            descriptor = descriptor,
            sourcePath = "signed-contract.json",
            acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
            versionStatus = DynamicContractVersionStatus.VerifiedCurrent,
        )
        val fallback = DynamicDescriptorDiscovery(
            descriptor = descriptor.copy(
                app = descriptor.app.copy(version = "metadata"),
            ),
            sourcePath = null,
            acquisition = DynamicDescriptorAcquisition.MetadataFallback,
            versionStatus = DynamicContractVersionStatus.VerifiedCurrent,
        )

        val resolved = resolveDynamicContractRediscovery(cached, fallback)

        assertSame(descriptor, resolved.descriptor)
        assertEquals(DynamicDescriptorAcquisition.SignedAppStorePackage, resolved.acquisition)
        assertEquals(DynamicContractVersionStatus.LastKnownReadOnly, resolved.versionStatus)
    }

    @Test
    fun `discovery exception downgrades cached write authority`() {
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("workspace", "Workspace", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test"),
        )
        val cached = DynamicDescriptorDiscovery(
            descriptor = descriptor,
            sourcePath = "signed-contract.json",
            acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
            versionStatus = DynamicContractVersionStatus.VerifiedCurrent,
        )

        val retained = retainedDynamicContractAfterDiscoveryFailure(cached)

        assertEquals(DynamicContractVersionStatus.LastKnownReadOnly, retained?.versionStatus)
        assertSame(descriptor, retained?.descriptor)
        assertEquals(cached.acquisition, retained?.acquisition)
        assertEquals(null, retainedDynamicContractAfterDiscoveryFailure(null))
    }

    @Test
    fun `last known read only schema exposes reads but no mutation actions or form views`() {
        val read = action("list-records", HttpMethod.GET, ActionIntent.list, ActionRisk.readOnly)
        val create = action("create-record", HttpMethod.POST, ActionIntent.create, ActionRisk.mutating)
        val delete = action("delete-record", HttpMethod.DELETE, ActionIntent.delete, ActionRisk.destructive)
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("workspace", "Workspace", "1"),
            confidence = Confidence.verified,
            actions = listOf(read, create, delete),
            views = listOf(
                view("records.list", read.id, NativeComponent.collectionList),
                view("records.create", create.id, NativeComponent.form),
                view("records.delete", delete.id, NativeComponent.form),
            ),
        )

        val readOnly = schema.forDynamicContractVersion(DynamicContractVersionStatus.LastKnownReadOnly)

        assertEquals(listOf(read.id), readOnly.actions.map(ActionSpec::id))
        assertEquals(listOf("records.list"), readOnly.views.map(ViewSpec::id))
        assertEquals(
            schema,
            schema.forDynamicContractVersion(DynamicContractVersionStatus.VerifiedCurrent),
        )
    }

    @Test
    fun `switching a root collection clears stale hierarchy context`() {
        val mutableParameters = mutableMapOf("houseId" to "house-2")

        val plan = planDynamicCollectionDestinationSelection(
            isTopLevelDestination = true,
            destinationPathParameterValues = mutableParameters,
        )
        mutableParameters["houseId"] = "stale"

        assertTrue(plan.clearHierarchyContext)
        assertEquals(mapOf("houseId" to "house-2"), plan.pathParameterValues)
    }

    @Test
    fun `switching a contextual collection preserves its selected parent`() {
        val plan = planDynamicCollectionDestinationSelection(
            isTopLevelDestination = false,
            destinationPathParameterValues = mapOf(
                "houseId" to "house-2",
                "checklistId" to "checklist-9",
            ),
        )

        assertFalse(plan.clearHierarchyContext)
        assertEquals(
            mapOf(
                "houseId" to "house-2",
                "checklistId" to "checklist-9",
            ),
            plan.pathParameterValues,
        )
    }

    private fun action(
        id: String,
        method: HttpMethod,
        intent: ActionIntent,
        risk: ActionRisk,
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = "records",
        binding = ApiBinding(
            method = method,
            path = "/records",
            operationId = id,
        ),
        intent = intent,
        risk = risk,
        requiresConfirmation = risk == ActionRisk.destructive,
        confidence = Confidence.verified,
    )

    private fun view(
        id: String,
        actionId: String,
        component: NativeComponent,
    ) = ViewSpec(
        id = id,
        title = id,
        resourceId = "records",
        component = component,
        sourceActionId = actionId,
        confidence = Confidence.verified,
    )
}
