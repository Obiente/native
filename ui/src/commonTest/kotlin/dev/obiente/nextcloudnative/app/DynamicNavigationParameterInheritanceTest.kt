package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DynamicNavigationParameterInheritanceTest {
    @Test
    fun exactCollectionDetailBeatsEarlierSingularRandomResource() {
        fun action(
            id: String,
            resourceId: String,
            path: String,
            requiredPathParameters: List<String> = emptyList(),
        ) = ActionSpec(
            id = id,
            label = "Read album",
            resourceId = resourceId,
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = path,
                operationId = id,
                pathParameterNames = requiredPathParameters,
                requiredPathParameterNames = requiredPathParameters,
            ),
            intent = ActionIntent.read,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            confidence = Confidence.high,
        )
        val randomAction = action("random-album", "album", "/apps/music/api/random/album")
        val selectedAction = action(
            id = "read-album",
            resourceId = "albums",
            path = "/apps/music/api/albums/{id}",
            requiredPathParameters = listOf("id"),
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("music", "Music", "1"),
            confidence = Confidence.high,
            resources = listOf(
                ResourceSpec("album", "Album", Confidence.high, emptyList()),
                ResourceSpec("albums", "Albums", Confidence.high, emptyList()),
            ),
            views = listOf(
                ViewSpec("album.detail", "Album", "album", NativeComponent.detail, randomAction.id, Confidence.high),
                ViewSpec("albums.detail", "Album", "albums", NativeComponent.detail, selectedAction.id, Confidence.high),
            ),
            actions = listOf(randomAction, selectedAction),
        )

        assertEquals("albums.detail", schema.bestDynamicDetailView("albums")?.id)
        assertEquals("album.detail", schema.bestDynamicDetailView("album")?.id)
    }

    @Test
    fun `bare parent identity does not leak into a selected child`() {
        val inherited = inheritDynamicParentParameters(
            selectedPathParameterValues = mapOf("accountId" to "1", "id" to "parent-id"),
            runtimeValues = mapOf("id" to "parent-id", "accountId" to "1", "name" to "Account"),
        )

        assertEquals("1", inherited["accountId"])
        assertFalse("id" in inherited)
        assertFalse("name" in inherited)
    }

    @Test
    fun `record without destination keeps current collection parent binding`() {
        assertEquals(
            mapOf("id" to "shared-trip"),
            resolveDynamicRecordSelectionParameters(
                currentViewId = "bills.collection",
                nextViewId = "bills.collection",
                currentParameters = mapOf("id" to "shared-trip"),
                explicitTargetParameters = null,
                fallbackTargetParameters = emptyMap(),
            ),
        )
    }

    @Test
    fun `record destination replaces collection parent binding`() {
        assertEquals(
            mapOf("billId" to "11"),
            resolveDynamicRecordSelectionParameters(
                currentViewId = "bills.collection",
                nextViewId = "bill.detail",
                currentParameters = mapOf("id" to "shared-trip"),
                explicitTargetParameters = mapOf("billId" to "11"),
                fallbackTargetParameters = emptyMap(),
            ),
        )
    }

    @Test
    fun `same resource record uses generic native detail surface`() {
        assertEquals(
            true,
            shouldShowDynamicRecordFallbackDetail(
                viewResourceId = "bills",
                viewComponent = NativeComponent.collectionList,
                selectedRecord = NativeRecord("11", emptyMap()),
                selectedRecordResourceId = "bills",
            ),
        )
        assertEquals(
            false,
            shouldShowDynamicRecordFallbackDetail(
                viewResourceId = "bills",
                viewComponent = NativeComponent.collectionList,
                selectedRecord = NativeRecord("project", emptyMap()),
                selectedRecordResourceId = "projects",
            ),
        )
    }

    @Test
    fun `message body is the primary native content while unrelated resources remain neutral`() {
        val body = DynamicNavigationDestination(
            layoutId = "body.detail",
            label = "Body",
            resourceId = "body",
            actionId = "messages-body",
            pathParameterValues = mapOf("id" to "42"),
        )
        val thread = DynamicNavigationDestination(
            layoutId = "thread.detail",
            label = "Thread",
            resourceId = "thread",
            actionId = "messages-thread",
            pathParameterValues = mapOf("id" to "42"),
        )

        assertEquals(body, primaryDynamicContentDestination("messages", listOf(thread, body)))
        assertEquals(null, primaryDynamicContentDestination("accounts", listOf(body)))
    }

    @Test
    fun `selecting a board opens its lane hierarchy before technical overview views`() {
        val permissions = DynamicNavigationDestination(
            layoutId = "permissions.detail",
            label = "Permissions",
            resourceId = "permissions",
            actionId = "board-permissions",
            pathParameterValues = mapOf("boardId" to "7"),
        )
        val stacks = DynamicNavigationDestination(
            layoutId = "stacks.board",
            label = "Stacks",
            resourceId = "stacks",
            actionId = "board-stacks",
            pathParameterValues = mapOf("boardId" to "7"),
        )

        assertEquals(stacks, primaryDynamicContentDestination("boards", listOf(permissions, stacks)))
        assertEquals(null, primaryDynamicContentDestination("projects", listOf(stacks)))
    }

    @Test
    fun `selecting a financial project opens its transaction collection`() {
        val members = DynamicNavigationDestination(
            layoutId = "members.list",
            label = "Members",
            resourceId = "members",
            actionId = "project-members",
            pathParameterValues = mapOf("projectId" to "trip"),
        )
        val bills = DynamicNavigationDestination(
            layoutId = "bills.list",
            label = "Bills",
            resourceId = "bills",
            actionId = "project-bills",
            pathParameterValues = mapOf("projectId" to "trip"),
        )

        assertEquals(bills, primaryDynamicContentDestination("projects", listOf(members, bills)))
        assertEquals(bills, primaryDynamicContentDestination("budget", listOf(members, bills)))
        assertEquals(null, primaryDynamicContentDestination("account", listOf(members, bills)))
    }
}
