package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicNavigationParameterInheritanceTest {
    @Test
    fun `workspace records open a section menu while operational variants stay direct`() {
        fun destination(layoutId: String, resourceId: String = layoutId) = DynamicNavigationDestination(
            layoutId = layoutId,
            label = layoutId,
            resourceId = resourceId,
            actionId = "read-$layoutId",
        )

        assertFalse(shouldShowDynamicContextDestinationMenu(emptyList()))
        assertFalse(shouldShowDynamicContextDestinationMenu(listOf(destination("items"))))
        assertFalse(
            shouldShowDynamicContextDestinationMenu(
                listOf(
                    destination("items"),
                    destination("archive-items", "archivedItems"),
                    destination("trash-items", "trashedItems"),
                    destination("roles"),
                ),
            ),
        )
        assertTrue(
            shouldShowDynamicContextDestinationMenu(
                listOf(
                    destination("notes"),
                    destination("photos"),
                ),
            ),
        )
        assertTrue(
            shouldShowDynamicContextDestinationMenu(
                listOf(
                    destination("lists"),
                    destination("notes"),
                    destination("photos"),
                    destination("preferences"),
                ),
            ),
        )
    }

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
        assertFalse(
            shouldOpenDynamicContextDestinationMenu(
                destinations = listOf(thread, body),
                primaryContentTarget = body,
                preferredCollectionChild = null,
            ),
        )
    }

    @Test
    fun `mailbox message collection opens directly while stats remain enrichment`() {
        val messages = DynamicNavigationDestination(
            layoutId = "messages.mailbox",
            label = "Messages",
            resourceId = "messages",
            actionId = "route.messages.index",
            pathParameterValues = mapOf("mailboxId" to "9"),
        )
        val stats = DynamicNavigationDestination(
            layoutId = "mailbox.stats",
            label = "Mailbox stats",
            resourceId = "mailboxStats",
            actionId = "route.mailboxes.stats",
            pathParameterValues = mapOf("id" to "9"),
        )
        val deliveryStatus = DynamicNavigationDestination(
            layoutId = "mailbox.delivery-status",
            label = "Delivery status",
            resourceId = "deliveryStatus",
            actionId = "route.mailboxes.delivery-status",
            pathParameterValues = mapOf("id" to "9"),
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("mail", "Mail", "1"),
            confidence = Confidence.verified,
            resources = listOf(
                ResourceSpec("mailboxes", "Mailboxes", Confidence.verified),
                ResourceSpec(
                    "mailboxStats",
                    "Mailbox stats",
                    Confidence.verified,
                    fields = listOf(
                        FieldSpec("total", "Total", FieldKind.integer, false, true),
                        FieldSpec("unread", "Unread", FieldKind.integer, false, true),
                    ),
                ),
                ResourceSpec(
                    "deliveryStatus",
                    "Delivery status",
                    Confidence.verified,
                    fields = listOf(
                        FieldSpec("state", "State", FieldKind.string, false, true),
                        FieldSpec("detail", "Detail", FieldKind.string, false, true),
                    ),
                ),
            ),
        )

        assertTrue(isDynamicMailboxCollectionSummaryDestination(schema, "mailboxes", stats))
        assertFalse(isDynamicMailboxCollectionSummaryDestination(schema, "budgets", stats))
        assertFalse(isDynamicMailboxCollectionSummaryDestination(schema, "mailboxes", deliveryStatus))
        assertFalse(
            shouldOpenDynamicContextDestinationMenu(
                destinations = listOf(messages, stats),
                primaryContentTarget = null,
                preferredCollectionChild = messages,
            ),
        )
    }

    @Test
    fun `mail detail retains the adjacent mailbox pagination scope`() {
        val mailboxSnapshot = DynamicNavigationSnapshot(
            viewId = "messages.collection",
            resourceId = "messages",
            record = NativeRecord("inbox", mapOf("id" to "9", "accountId" to "account-a")),
            recordResourceId = "mailboxes",
            pathParameterValues = mapOf("mailboxId" to "9"),
        )
        val detail = ViewSpec(
            id = "message.detail",
            title = "Message",
            resourceId = "messageBody",
            component = NativeComponent.detail,
            sourceActionId = "message.read",
            confidence = Confidence.verified,
        )
        val newerDetailSnapshot = DynamicNavigationSnapshot(
            viewId = detail.id,
            resourceId = detail.resourceId,
            record = NativeRecord("message-1", mapOf("mailboxId" to "9")),
            recordResourceId = "messages",
            pathParameterValues = mapOf("id" to "message-1"),
        )

        assertEquals(
            mailboxSnapshot,
            retainedMailPaginationSnapshot(
                hasMailWorkspaceSemantics = true,
                paginationViewId = "messages.collection",
                selectedView = detail,
                selectedRecordResourceId = "messages",
                navigationHistory = listOf(mailboxSnapshot, newerDetailSnapshot),
            ),
        )
        assertNull(
            retainedMailPaginationSnapshot(
                hasMailWorkspaceSemantics = false,
                paginationViewId = "messages.collection",
                selectedView = detail,
                selectedRecordResourceId = "messages",
                navigationHistory = listOf(mailboxSnapshot, newerDetailSnapshot),
            ),
        )
        assertNull(
            retainedMailPaginationSnapshot(
                hasMailWorkspaceSemantics = true,
                paginationViewId = "other.collection",
                selectedView = detail,
                selectedRecordResourceId = "messages",
                navigationHistory = listOf(mailboxSnapshot, newerDetailSnapshot),
            ),
        )
        val collection = ViewSpec(
            id = mailboxSnapshot.viewId,
            title = "Messages",
            resourceId = mailboxSnapshot.resourceId,
            component = NativeComponent.collectionList,
            sourceActionId = "messages.list",
            confidence = Confidence.verified,
        )
        assertEquals(
            nativeMailCollectionScopeKey(
                hasMailWorkspaceSemantics = true,
                selectedView = collection,
                selectedRecordResourceId = mailboxSnapshot.recordResourceId,
                selectedRecord = mailboxSnapshot.record,
                selectedPathParameterValues = mailboxSnapshot.pathParameterValues,
                navigationHistory = emptyList(),
            ),
            nativeMailCollectionScopeKey(
                hasMailWorkspaceSemantics = true,
                selectedView = detail,
                selectedRecordResourceId = "messages",
                selectedRecord = newerDetailSnapshot.record,
                selectedPathParameterValues = newerDetailSnapshot.pathParameterValues,
                navigationHistory = listOf(mailboxSnapshot, newerDetailSnapshot),
            ),
        )
        assertNotEquals(
            nativeMailCollectionScopeKey(
                hasMailWorkspaceSemantics = true,
                selectedView = collection,
                selectedRecordResourceId = mailboxSnapshot.recordResourceId,
                selectedRecord = mailboxSnapshot.record,
                selectedPathParameterValues = mailboxSnapshot.pathParameterValues,
                navigationHistory = emptyList(),
            ),
            nativeMailCollectionScopeKey(
                hasMailWorkspaceSemantics = true,
                selectedView = collection,
                selectedRecordResourceId = mailboxSnapshot.recordResourceId,
                selectedRecord = NativeRecord(
                    "inbox",
                    mapOf("id" to "9", "accountId" to "account-b"),
                ),
                selectedPathParameterValues = mailboxSnapshot.pathParameterValues,
                navigationHistory = emptyList(),
            ),
        )
    }

    @Test
    fun `partial refresh keeps a complete stale collection`() {
        val cached = listOf(
            NativeRecord("message-3", emptyMap()),
            NativeRecord("message-2", emptyMap()),
            NativeRecord("message-1", emptyMap()),
        )
        val sparse = listOf(NativeRecord("message-3", emptyMap()))

        assertEquals(
            cached,
            preferredDynamicPartialRefreshRecords(sparse, cached, "Could not load every message."),
        )
        assertEquals(
            sparse,
            preferredDynamicPartialRefreshRecords(sparse, cached, partialFailureMessage = null),
        )
        assertEquals(
            sparse,
            preferredDynamicPartialRefreshRecords(
                freshRecords = sparse,
                staleRecords = null,
                partialFailureMessage = "Partial",
            ),
        )
    }

    @Test
    fun `mailbox summary state is cleared before a different mailbox summary loads`() {
        assertTrue(
            shouldRetainDynamicMailboxSummaryState(
                retainingAdjacentMailbox = true,
                loadAttempt = 2,
                completedLoadAttempt = 2,
            ),
        )
        assertFalse(
            shouldRetainDynamicMailboxSummaryState(
                retainingAdjacentMailbox = true,
                loadAttempt = 3,
                completedLoadAttempt = 2,
            ),
        )
        val previousSummary = NativeRecord("inbox-summary", mapOf("total" to "120", "unread" to "8"))
        val nextSummary = NativeRecord("sent-summary", mapOf("total" to "42", "unread" to "0"))
        val message = NativeRecord("message-1", mapOf("subject" to "Synthetic message"))

        val cleared = replaceDynamicMailboxCollectionSummaries(
            recordsByResourceId = mapOf(
                "mailboxStats" to listOf(previousSummary),
                "messages" to listOf(message),
            ),
            summaryResourceIds = setOf("mailboxStats"),
            loadedSummaries = emptyMap(),
        )
        val reloaded = replaceDynamicMailboxCollectionSummaries(
            recordsByResourceId = cleared,
            summaryResourceIds = setOf("mailboxStats"),
            loadedSummaries = mapOf(
                "mailboxStats" to listOf(nextSummary),
                "unrelated" to listOf(previousSummary),
            ),
        )

        assertFalse("mailboxStats" in cleared)
        assertEquals(listOf(message), cleared["messages"])
        assertEquals(listOf(nextSummary), reloaded["mailboxStats"])
        assertFalse("unrelated" in reloaded)

        val failed = reconcileDynamicMailboxCollectionSummaries(
            recordsByResourceId = mapOf("mailboxStats" to listOf(previousSummary)),
            summaryResourceIds = setOf("mailboxStats"),
            results = listOf(
                DynamicMailboxCollectionSummaryResult("mailboxStats", failed = true),
            ),
        )
        assertFalse("mailboxStats" in failed.recordsByResourceId)
        assertEquals(
            "Could not load mailbox counts. The mailbox is still available.",
            failed.errorMessage,
        )
    }

    @Test
    fun `mailbox summary state clears when the next mailbox has no resolved summary route`() {
        val previousSummary = NativeRecord("inbox-summary", mapOf("total" to "120", "unread" to "8"))
        val records = mapOf(
            "messages" to listOf(NativeRecord("message-1", mapOf("subject" to "Hello"))),
            "mailboxStats" to listOf(previousSummary),
        )

        val preparation = prepareDynamicMailboxCollectionSummaries(
            recordsByResourceId = records,
            previouslyTrackedResourceIds = setOf("mailboxStats"),
            currentResourceIds = emptySet(),
        )

        assertEquals(setOf("messages"), preparation.recordsByResourceId.keys)
        assertTrue(preparation.trackedResourceIds.isEmpty())
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
