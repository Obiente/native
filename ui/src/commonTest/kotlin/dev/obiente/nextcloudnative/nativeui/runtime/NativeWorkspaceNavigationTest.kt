package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeWorkspaceNavigationTest {
    private fun category(id: String, parent: String? = null) =
        NativeRecord(id, mapOf("name" to id)) to NativeCategoryPresentation(
            name = id, kind = NativeCategoryKind.Expense, parentId = parent,
            transactionCount = null, shared = false, writable = true, sharedBy = null,
            mutedFromReports = false,
        )

    @Test
    fun collapsedCategoryChildrenStayHiddenInsteadOfReturningAsRoots() {
        val rows = listOf(category("parent"), category("child", "parent"), category("grandchild", "child"))
        assertEquals(listOf("parent"), nativeCategoryRowsForDisplay(rows, emptySet(), false).map { it.record.id })
        assertEquals(listOf("parent", "child"), nativeCategoryRowsForDisplay(rows, setOf("parent"), false).map { it.record.id })
        assertEquals(listOf(0, 1, 2), nativeCategoryRowsForDisplay(rows, setOf("parent", "child"), false).map { it.depth })
    }

    @Test
    fun missingParentsAndCyclesDoNotDiscardRecords() {
        val rows = listOf(category("orphan", "missing"), category("a", "b"), category("b", "a"))
        val displayed = nativeCategoryRowsForDisplay(rows, emptySet(), false)
        assertEquals(setOf("orphan", "a", "b"), displayed.map { it.record.id }.toSet())
        assertEquals(3, displayed.size)
    }

    @Test
    fun deeplyNestedCollapsedCategoriesDoNotOverflowTheCallStack() {
        val rows = (0 until 10_000).map { index ->
            category(index.toString(), (index - 1).takeIf { it >= 0 }?.toString())
        }
        assertEquals(listOf("0"), nativeCategoryRowsForDisplay(rows, emptySet(), false).map { it.record.id })
    }

    private fun mailItem(resourceId: String, id: String, kind: NativeMailboxItemKind, title: String, accountId: String? = null): NativeMailWorkspaceItem {
        val resource = ResourceSpec(resourceId, resourceId, confidence = Confidence.verified)
        val record = NativeRecord(id, listOfNotNull(accountId?.let { "accountId" to it }).toMap())
        val presentation = NativeMailboxPresentation(kind, title, null, null, null, false, null, null, null, false, 0)
        return NativeMailWorkspaceItem(resource, record, presentation)
    }

    @Test
    fun mailboxChooserKeepsSameFolderIdInDifferentAccountsDistinct() {
        val a = mailItem("accounts", "a", NativeMailboxItemKind.Account, "Personal")
        val b = mailItem("accounts", "b", NativeMailboxItemKind.Account, "Work")
        val inboxA = mailItem("mailboxes", "1", NativeMailboxItemKind.Folder, "Inbox", "a")
        val inboxB = mailItem("mailboxes", "1", NativeMailboxItemKind.Folder, "Inbox", "b")
        val plan = NativeMailWorkspacePlan(listOf(a, b), listOf(inboxA, inboxB), emptyList(), emptyList(), inboxB, null, emptyList())
        assertEquals(listOf(a, b, inboxA, inboxB), nativeMailNavigationDestinations(plan))
        assertEquals("Personal", nativeMailNavigationAccountLabel(plan, inboxA))
        assertEquals("Work", nativeMailNavigationAccountLabel(plan, inboxB))
        assertNull(nativeMailNavigationAccountLabel(plan, inboxB.copy(record = inboxB.record.copy(values = emptyMap()))))
    }

    @Test
    fun singleLoadedTableRowStillAllowsSearchingLaterPages() {
        assertTrue(genericCollectionSearchAvailable(NativeScreenState.Ready(emptyList()), 1, GenericNativeSurface.Table, false))
    }
}
