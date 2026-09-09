package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.NativeSceneTestDriver
import dev.obiente.nextcloudnative.app.nativeSceneTest
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedSegmentedConsumerInteractionTest {
    @Test
    fun compactChoresNavigationKeepsTheHostDraftGuardAndExactDestinationId() {
        val navigation = NativeInlineEditorNavigation()
        val selected = mutableStateOf(CHORES_ID)
        val navigated = mutableListOf<String>()
        nativeSceneTest(390, 800, content = {
            CompositionLocalProvider(LocalNativeInlineEditorNavigation provides navigation) {
                rememberNativeInlineEditorCloseRequest(enabled = true, dirty = true, submissionBlocked = false, onClose = {})
                ChoresFixture(selected.value, onNavigate = { id ->
                    navigation.navigate { navigated += id; selected.value = id }
                })
            }
        }) {
            assertEquals(Role.Tab, segment("All chores", Role.Tab).config.getOrNull(SemanticsProperties.Role))
            click("All chores")
            assertTrue(navigated.isEmpty())
            assertFalse(has("Discard unsaved changes?"), "The selected section must remain a no-op")
            click("History")
            assertTrue(has("Discard unsaved changes?"))
            assertTrue(navigated.isEmpty())
            click("Keep editing")
            assertEquals(true, segment("All chores", Role.Tab).config.getOrNull(SemanticsProperties.Selected))
            click("History")
            click("Discard changes")
            assertEquals(listOf(HISTORY_ID), navigated)
            assertEquals(true, segment("History", Role.Tab).config.getOrNull(SemanticsProperties.Selected))
            capture("shared-segmented-chores-phone")
        }
    }

    @Test
    fun reorderedChoresOptionsDoNotNavigateOrInferADestinationForMissingSelection() {
        val selected = mutableStateOf(CHORES_ID)
        val reversed = mutableStateOf(false)
        val navigated = mutableListOf<String>()
        nativeSceneTest(700, 720, content = {
            ChoresFixture(selected.value, reversed.value) { navigated += it }
        }) {
            reversed.value = true
            settle()
            assertEquals(true, segment("All chores", Role.Tab).config.getOrNull(SemanticsProperties.Selected))
            assertTrue(navigated.isEmpty())
            selected.value = "missing-section"
            settle()
            assertEquals(false, segment("All chores", Role.Tab).config.getOrNull(SemanticsProperties.Selected))
            assertEquals(false, segment("History", Role.Tab).config.getOrNull(SemanticsProperties.Selected))
            assertTrue(navigated.isEmpty())
            click("History")
            assertEquals(listOf(HISTORY_ID), navigated)
        }
    }

    @Test
    fun categoryRadioFilterChangesOnlyLocalRowsAndKeepsHierarchyControlsSeparate() {
        var executed = 0
        var selectedRecord: String? = null
        nativeSceneTest(1000, 850, content = {
            GenericNativeAppScreen(
                schema = categorySchema, view = categoryView, state = NativeScreenState.Ready(categoryRecords),
                actionExecutor = NativeActionExecutor { executed++; NativeActionExecutionResult.Success() },
                onSelectRecord = { selectedRecord = it.id },
            )
        }) {
            assertEquals(true, segment("All 3", Role.RadioButton).config.getOrNull(SemanticsProperties.Selected))
            assertTrue(has("Groceries"))
            click("Income 1")
            assertEquals(true, segment("Income 1", Role.RadioButton).config.getOrNull(SemanticsProperties.Selected))
            assertFalse(has("Groceries"))
            assertTrue(has("Salary"))
            click("Collapse all")
            assertTrue(has("Expand all"))
            assertEquals(true, segment("Income 1", Role.RadioButton).config.getOrNull(SemanticsProperties.Selected))
            click("All 3")
            assertTrue(has("Household"))
            assertFalse(has("Groceries"))
            click("Expand all")
            assertTrue(has("Groceries"))
            click("Groceries")
            assertEquals("category:grocery-42", selectedRecord)
            assertEquals(0, executed, "Filtering and expanding must not execute a remote action")
            capture("shared-segmented-category-filter")
        }
    }

    @Test
    fun categoryCountRefreshPreservesTheSelectedFilter() {
        val records = mutableStateOf(categoryRecords)
        var executed = 0
        nativeSceneTest(1000, 850, content = {
            GenericNativeAppScreen(
                schema = categorySchema, view = categoryView, state = NativeScreenState.Ready(records.value),
                actionExecutor = NativeActionExecutor { executed++; NativeActionExecutionResult.Success() },
            )
        }) {
            click("Expenses 2")
            records.value = categoryRecords + NativeRecord("category:travel", mapOf("name" to "Travel", "type" to "expense"))
            settle()
            assertEquals(true, segment("Expenses 3", Role.RadioButton).config.getOrNull(SemanticsProperties.Selected))
            assertTrue(has("Travel"))
            assertFalse(has("Salary"))
            assertEquals(0, executed)
        }
    }

    private fun NativeSceneTestDriver.segment(label: String, role: Role): SemanticsNode {
        val text = assertNotNull(node(label), "Missing segment $label")
        return assertNotNull(nodes().lastOrNull {
            it.config.getOrNull(SemanticsProperties.Role) == role && it.boundsInRoot.contains(text.boundsInRoot.center)
        }, "$label must expose $role semantics")
    }

    @Composable
    private fun ChoresFixture(selected: String, reversed: Boolean = false, onNavigate: (String) -> Unit) {
        val options = listOf(
            NativeWorkspaceNavigationItem(CHORES_ID, "All chores", selected == CHORES_ID),
            NativeWorkspaceNavigationItem(HISTORY_ID, "History", selected == HISTORY_ID),
        ).let { if (reversed) it.reversed() else it }
        NativeChoresWorkspaceSurface(
            presentation = NativeChoresPresentation(NativeChoresWorkspaceKind.Chores, "Chores", "Team overview", NativeChoresContent.Ready(emptyList())),
            onSelectRecord = null, recordActions = { emptyList() }, navigationItems = options,
            onNavigate = onNavigate, createLabel = null, onCreate = null,
        )
    }

    private val categoryResource = ResourceSpec(
        id = "budget-categories", name = "Categories", confidence = Confidence.verified,
        fields = listOf("name", "type", "parentId").map { FieldSpec(it, it, FieldKind.string, required = false, readOnly = true) },
    )
    private val categoryRead = ActionSpec(
        id = "read-categories", label = "Read categories", resourceId = categoryResource.id,
        binding = ApiBinding(HttpMethod.GET, "/synthetic/categories", "readCategories"), intent = ActionIntent.list,
        risk = ActionRisk.readOnly, requiresConfirmation = false, confidence = Confidence.verified,
    )
    private val categoryView = ViewSpec(
        id = "category-list", title = "Budget categories", resourceId = categoryResource.id,
        component = NativeComponent.collectionList, sourceActionId = categoryRead.id, confidence = Confidence.verified,
    )
    private val categorySchema = NativeAppSchema(
        schemaVersion = "1.0", app = AppIdentity("synthetic-budget", "Budget", "1.0"), confidence = Confidence.verified,
        resources = listOf(categoryResource), views = listOf(categoryView), actions = listOf(categoryRead),
    )
    private val categoryRecords = listOf(
        NativeRecord("category:household", mapOf("name" to "Household", "type" to "expense")),
        NativeRecord("category:grocery-42", mapOf("name" to "Groceries", "type" to "expense", "parentId" to "category:household")),
        NativeRecord("category:salary", mapOf("name" to "Salary", "type" to "income")),
    )

    private companion object {
        const val CHORES_ID = "chores:list/team-42"
        const val HISTORY_ID = "work:history/team-42"
    }
}
