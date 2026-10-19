package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicChildCandidateStatus
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicLayout
import dev.obiente.nextcloudnative.nativeui.model.DynamicLink
import dev.obiente.nextcloudnative.nativeui.model.DynamicLinkTarget
import dev.obiente.nextcloudnative.nativeui.model.DynamicResource
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.LayoutKind
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicContractInfoTest {
    @Test
    fun `contract info exposes only safe source identity and diagnostic codes`() {
        val discovery = DynamicDescriptorDiscovery(
            descriptor = descriptor().copy(app = AppIdentity("example", "Example", "1.2 user@example.test")),
            sourcePath = "https://user:secret@example.test/private/path/package.tar.gz#spec/openapi.json?token=secret",
            acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
            diagnostics = listOf(
                "The API viewer is not installed at https://private.example.test/path.",
                "Unclassified private diagnostic content",
            ),
            diagnosticCodes = listOf(DynamicDiagnosticCode.ApiViewerUnavailable),
        )

        val info = discovery.toContractInfo(null)

        assertEquals("Signed App Store package", info.acquisition)
        assertEquals("Unavailable", info.appVersion)
        assertEquals("openapi.json", info.sourceSpecFile)
        assertEquals(listOf("api-viewer-unavailable", "signed-package-imported"), info.diagnosticCodes)
        val rendered = info.toString()
        assertFalse(rendered.contains("example.test"))
        assertFalse(rendered.contains("secret"))
    }

    @Test
    fun `contract info identifies verified static read routes`() {
        val info = DynamicDescriptorDiscovery(
            descriptor = descriptor(),
            sourcePath = "https://apps.nextcloud.com/packages/deck#appinfo/routes.php",
            acquisition = DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes,
        ).toContractInfo(null)

        assertEquals("Verified signed static read routes", info.acquisition)
        assertEquals("routes.php", info.sourceSpecFile)
        assertEquals(listOf("verified-static-read-routes"), info.diagnosticCodes)
    }

    @Test
    fun `diagnostic codes come from typed events and never from diagnostic prose`() {
        val typed = DynamicDescriptorDiscovery(
            descriptor = descriptor(),
            sourcePath = null,
            acquisition = DynamicDescriptorAcquisition.MetadataFallback,
            diagnostics = listOf("The server API probe was inconclusive: ServerError."),
            diagnosticCodes = listOf(DynamicDiagnosticCode.StaticContractUnavailable, DynamicDiagnosticCode.ServerProbeInconclusive),
        )
        val reworded = typed.copy(diagnostics = listOf("Completely reworded text.", "Only app metadata is available."))
        val expected = listOf("static-contract-unavailable", "server-probe-inconclusive")
        assertEquals(expected, typed.toContractInfo(null).diagnosticCodes)
        assertEquals(expected, reworded.toContractInfo(null).diagnosticCodes)
        // Old trigger phrases without typed events produce no codes.
        val prose = typed.copy(diagnosticCodes = emptyList(), diagnostics = listOf(
            "OCS API Viewer is not installed", "No valid static OpenAPI document", "contained invalid OpenAPI",
            "App Store contract acquisition failed: Network.", "Only app metadata is available.",
        ))
        assertEquals(emptyList(), prose.toContractInfo(null).diagnosticCodes)
    }

    @Test
    fun `every typed code and acquisition import keeps its stable code string`() {
        assertEquals(
            listOf(
                "api-viewer-unavailable", "static-contract-unavailable", "app-store-acquisition-failed",
                "server-probe-inconclusive", "server-probe-access-denied", "server-contract-unsupported",
                "verified-static-read-routes", "verified-read-fallbacks", "signed-package-imported",
                "linked-source-tag-imported", "contract-invalid", "metadata-only",
            ),
            DynamicDiagnosticCode.entries.map(DynamicDiagnosticCode::code),
        )
        val imports = DynamicDescriptorAcquisition.entries.associateWith { acquisition ->
            DynamicDescriptorDiscovery(descriptor(), null, acquisition).toContractInfo(null).diagnosticCodes
        }
        assertEquals(listOf("signed-package-imported"), imports.getValue(DynamicDescriptorAcquisition.SignedAppStorePackage))
        assertEquals(listOf("linked-source-tag-imported"), imports.getValue(DynamicDescriptorAcquisition.AppStoreLinkedGitHubTag))
        assertEquals(listOf("verified-static-read-routes"), imports.getValue(DynamicDescriptorAcquisition.AppStoreLinkedStaticRoutes))
        assertEquals(listOf("verified-read-fallbacks"), imports.getValue(DynamicDescriptorAcquisition.SignedAppStoreMergedContract))
        assertEquals(listOf("verified-read-fallbacks"), imports.getValue(DynamicDescriptorAcquisition.AppStoreLinkedMergedContract))
        for (acquisition in listOf(
            DynamicDescriptorAcquisition.OcsApiViewer,
            DynamicDescriptorAcquisition.StaticAppAsset,
            DynamicDescriptorAcquisition.MetadataFallback,
        )) {
            assertEquals(emptyList(), imports.getValue(acquisition), acquisition.name)
        }
    }

    @Test
    fun `current record diagnostics explain included and omitted child candidates`() {
        val info = DynamicDescriptorDiscovery(
            descriptor = descriptor(),
            sourcePath = "/apps/example/openapi.json",
            acquisition = DynamicDescriptorAcquisition.StaticAppAsset,
        ).toContractInfo(
            DynamicResourceRecordContext(resourceId = "parents", recordId = "parent-1"),
        )
        val byResource = info.childCandidates.associateBy(DynamicContractChildInfo::resourceId)

        assertEquals(DynamicChildCandidateStatus.included, byResource.getValue("children").status)
        assertEquals(DynamicChildCandidateStatus.selfEdge, byResource.getValue("parents").status)
        assertEquals(DynamicChildCandidateStatus.missingContext, byResource.getValue("orphans").status)
        assertEquals(listOf("ownerId"), byResource.getValue("orphans").missingContextParameters)
        assertEquals(DynamicChildCandidateStatus.noLayout, byResource.getValue("hidden").status)
        assertEquals(DynamicChildCandidateStatus.noLink, byResource.getValue("unlinked").status)
        assertTrue(info.countSummary().contains("resources"))
    }

    private fun descriptor(): DynamicAppDescriptor {
        val actions = listOf(
            action("list-parents", "parents"),
            action("list-children", "children", "parentId"),
            action("list-orphans", "orphans", "ownerId"),
            action("list-hidden", "hidden"),
            action("list-unlinked", "unlinked"),
        )
        return DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("example", "Example", "1.2.3"),
            endpointPolicy = EndpointPolicy(
                "https://cloud.example.test",
                listOf("/ocs/v2.php/apps/example"),
            ),
            resources = actions.map(DynamicAction::resourceId).distinct().map { resourceId ->
                DynamicResource(resourceId, resourceId, true, confidence = Confidence.high)
            },
            layouts = actions.filterNot { it.resourceId == "hidden" }.map { action ->
                DynamicLayout(
                    id = "${action.resourceId}.list",
                    title = action.resourceId,
                    resourceId = action.resourceId,
                    kind = LayoutKind.list,
                    sourceActionId = action.id,
                    confidence = Confidence.high,
                )
            },
            links = listOf(
                DynamicLink(
                    id = "parents.children",
                    label = "children",
                    resourceId = "parents",
                    sourceFieldId = "id",
                    target = DynamicLinkTarget.Action("list-children"),
                    confidence = Confidence.high,
                ),
                DynamicLink(
                    id = "parents.parents",
                    label = "parents",
                    resourceId = "parents",
                    sourceFieldId = "id",
                    target = DynamicLinkTarget.Action("list-parents"),
                    confidence = Confidence.high,
                ),
            ),
            actions = actions,
        )
    }

    private fun action(id: String, resourceId: String, parameterName: String? = null) = DynamicAction(
        id = id,
        label = id,
        resourceId = resourceId,
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.GET,
            path = "/ocs/v2.php/apps/example/$resourceId" + parameterName?.let { "/{$it}" }.orEmpty(),
            pathParameters = parameterName?.let { name ->
                listOf(HttpParameter(name, true, buildJsonObject {}, ParameterSource.resourceField))
            }.orEmpty(),
        ),
        confidence = Confidence.high,
    )
}
