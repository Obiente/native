package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicContextualReadSafetyTest {
    private val context = DynamicResourceRecordContext(resourceId = "items", recordId = "item-1")

    @Test
    fun commandGetsNeverBecomeChildTabsOrAutomaticChildren() {
        listOf(LayoutKind.list, LayoutKind.detail).forEach { kind ->
            listOf(false, true).forEach { linked ->
                listOf("reset", "delete", "toggle", "rebuild", "clearcache", "clearCache", "scan").forEach { verb ->
                    // Compound camel-case commands are recognized in path segments, while IDs
                    // use the terminal concept to keep read actions such as reset-status safe.
                    val commandLocations = if (verb == "clearCache") listOf(false) else listOf(false, true)
                    commandLocations.forEach { commandInId ->
                        val descriptor = descriptor(kind, linked, verb, commandInId)
                        assertTrue(descriptor.planDynamicNavigation(context).contextualChildDestinations.isEmpty(), "$kind/$linked/$verb/$commandInId")
                        assertNull(descriptor.preferredSemanticContextualChild(context))
                        assertTrue(descriptor.explainDynamicChildNavigation(context).isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun statusPreviewExportAndDownloadRemainNavigableWithTheSameContext() {
        listOf(LayoutKind.list, LayoutKind.detail).forEach { kind ->
            listOf("status", "preview", "export", "download").forEach { verb ->
                val children = descriptor(kind, true, verb).planDynamicNavigation(context).contextualChildDestinations
                assertEquals(listOf("child-$verb"), children.map(DynamicNavigationDestination::actionId))
                assertEquals(mapOf("itemId" to "item-1"), children.single().pathParameterValues)
            }
        }
    }

    private fun descriptor(kind: LayoutKind, linked: Boolean, verb: String, commandInId: Boolean = false): DynamicAppDescriptor {
        val child = DynamicAction(
            id = "child-$verb", label = "Entries", resourceId = "entries",
            intent = if (kind == LayoutKind.list) ActionIntent.list else ActionIntent.read,
            risk = ActionRisk.readOnly, requiresConfirmation = false, confidence = Confidence.verified,
            binding = DynamicHttpBinding(HttpMethod.GET, "/apps/example/items/{itemId}/${if (commandInId) "status" else verb}",
                pathParameters = listOf(HttpParameter("itemId", true, buildJsonObject {}, ParameterSource.resourceField))),
            provenance = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "package", "Verified contract")),
        )
        return DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION, app = AppIdentity("example", "Example", "1"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test", listOf("/apps/example")),
            resources = listOf("items", "entries").map { DynamicResource(it, it, collection = true, confidence = Confidence.verified) },
            actions = listOf(child),
            layouts = listOf(DynamicLayout("entries", "Entries", "entries", kind, sourceActionId = child.id, confidence = Confidence.verified)),
            links = if (!linked) emptyList() else listOf(DynamicLink("items.entries", "Entries", "items", "id",
                DynamicLinkTarget.Action(child.id), confidence = Confidence.verified)),
        )
    }
}
