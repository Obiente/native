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
                    // The namespaced child-clearCache ID ends in cache, so this fixture
                    // covers that compound command through its path. Prefixes are tested below.
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

    @Test
    fun commandBeforeIdentifiersAndOperationIdPrefixesCannotBecomeAutomaticReads() {
        listOf(LayoutKind.list, LayoutKind.detail).forEach { kind ->
            listOf(false, true).forEach { linked ->
                listOf("reset", "delete", "toggle", "rebuild", "clearCache", "scan").forEach { verb ->
                    listOf(
                        "/apps/example/items/$verb/{itemId}" to "child-status",
                        "/apps/example/items/$verb/{itemId}/{otherId}" to "child-status",
                        "/apps/example/items/{itemId}" to "${verb}Item",
                    ).forEach { (path, id) ->
                        val descriptor = descriptor(kind, linked, "status", path = path, id = id)
                        val action = descriptor.actions.single()
                        assertTrue(action.looksLikeStateChangingGet(), "$path/$id")
                        assertTrue(!action.hasPositiveRootReadEvidence())
                        assertTrue(descriptor.planDynamicNavigation(context).contextualChildDestinations.isEmpty())
                        assertNull(descriptor.preferredSemanticContextualChild(context))
                        assertTrue(descriptor.explainDynamicChildNavigation(context).isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun commandStatusReadsAndReadVerbsBeforeIdentifiersRemainEligible() {
        listOf("status", "preview", "export", "download").forEach { verb ->
            val descriptor = descriptor(LayoutKind.detail, true, verb,
                path = "/apps/example/items/$verb/{itemId}", id = "getResetStatus")
            assertTrue(!descriptor.actions.single().looksLikeStateChangingGet())
            assertEquals(1, descriptor.planDynamicNavigation(context).contextualChildDestinations.size)
        }
    }

    private fun descriptor(
        kind: LayoutKind, linked: Boolean, verb: String, commandInId: Boolean = false,
        path: String = "/apps/example/items/{itemId}/${if (commandInId) "status" else verb}",
        id: String = "child-$verb",
    ): DynamicAppDescriptor {
        val child = DynamicAction(
            id = id, label = "Entries", resourceId = "entries",
            intent = if (kind == LayoutKind.list) ActionIntent.list else ActionIntent.read,
            risk = ActionRisk.readOnly, requiresConfirmation = false, confidence = Confidence.verified,
            binding = DynamicHttpBinding(HttpMethod.GET, path,
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
