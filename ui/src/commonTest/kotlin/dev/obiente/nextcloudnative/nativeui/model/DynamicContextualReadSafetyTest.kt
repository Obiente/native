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
                    listOf(false, true).forEach { commandInId ->
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

    @Test
    fun middleCommandsCannotBecomeRootOrContextualReads() {
        listOf(LayoutKind.list, LayoutKind.detail).forEach { kind ->
            listOf(false, true).forEach { linked ->
                listOf("getResetItem", "getToggleItem", "serviceClearCacheItem", "items_scan_child",
                    "getResetItemStatusItem", "getItemResetPreviewItem", "getResetStatusReset",
                    "getResetHistoryReset", "getItemResetHistoryItem").forEach { id ->
                    val descriptor = descriptor(kind, linked, "items", path = "/apps/example/items/{itemId}", id = id)
                    val action = descriptor.actions.single()
                    assertTrue(action.looksLikeStateChangingGet(), id)
                    assertTrue(!action.hasPositiveRootReadEvidence(), id)
                    assertTrue(descriptor.planDynamicNavigation(context).contextualChildDestinations.isEmpty(), id)
                    assertNull(descriptor.preferredSemanticContextualChild(context), id)
                    assertTrue(descriptor.explainDynamicChildNavigation(context).isEmpty(), id)
                }
            }
        }
    }

    @Test
    fun actualReadProducingSuffixesKeepCommandStatusAndOutputReadsNavigable() {
        listOf("getResetStatus", "api-reset-item-preview", "getStatusResetItemStatus",
            "getResetItemExport", "getResetItemDownload", "readRunHistory").forEach { id ->
            val descriptor = descriptor(LayoutKind.detail, true, "items", path = "/apps/example/items/{itemId}", id = id)
            assertTrue(!descriptor.actions.single().looksLikeStateChangingGet(), id)
            assertEquals(listOf(id), descriptor.planDynamicNavigation(context).contextualChildDestinations
                .map(DynamicNavigationDestination::actionId), id)
        }
    }

    @Test
    fun readSuffixesCannotOverrideCommandPrefixesOrCommandPaths() {
        listOf(
            "/apps/example/items/{itemId}" to "resetItemStatus",
            "/apps/example/items/{itemId}" to "getItemStatusReset",
            "/apps/example/items/reset/{itemId}" to "getResetStatus",
        ).forEach { (path, id) ->
            assertTrue(descriptor(LayoutKind.detail, true, "items", path = path, id = id)
                .actions.single().looksLikeStateChangingGet(), "$path/$id")
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
