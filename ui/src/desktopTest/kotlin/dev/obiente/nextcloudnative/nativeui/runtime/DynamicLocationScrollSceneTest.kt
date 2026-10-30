package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.NativeSceneTestDriver
import dev.obiente.nextcloudnative.app.nativeSceneTest
import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.*

class DynamicLocationScrollSceneTest {
    private val projects = ResourceSpec("projects", "Projects", Confidence.high, listOf(
        FieldSpec("name", "Name", FieldKind.string, false, false)))
    private val tasks = ResourceSpec("tasks", "Tasks", Confidence.high, listOf(
        FieldSpec("name", "Name", FieldKind.string, false, false)))
    private val projectList = ActionSpec("projects.list", "Projects", "projects",
        ApiBinding(HttpMethod.GET, "/synthetic/projects", "projects.list"), ActionIntent.list,
        ActionRisk.readOnly, false, Confidence.high)
    private val taskList = ActionSpec("tasks.list", "Tasks", "tasks",
        ApiBinding(HttpMethod.GET, "/synthetic/projects/{projectId}/tasks", "tasks.list"), ActionIntent.list,
        ActionRisk.readOnly, false, Confidence.high)
    private val projectsView = ViewSpec("projects", "Projects", "projects", NativeComponent.collectionList,
        projectList.id, Confidence.high)
    private val tasksView = ViewSpec("tasks", "Tasks", "tasks", NativeComponent.collectionList,
        taskList.id, Confidence.high)
    private val schema = NativeAppSchema("test", AppIdentity("planner", "Planner", "1.0.0"), Confidence.high,
        resources = listOf(projects, tasks), actions = listOf(projectList, taskList), views = listOf(projectsView, tasksView))
    private val projectRecords = (1..60).map { NativeRecord("project-$it", mapOf("name" to "Project $it")) }
    private val taskRecords = (1..60).map { NativeRecord("task-$it", mapOf("name" to "Task $it")) }

    /** Mirrors the host's history: a location is a view plus the record it was opened for. */
    private data class Location(val view: ViewSpec, val parent: NativeRecord?)

    @Test
    fun drillInStartsAtTheTopAndBackRestoresTheParentPosition() {
        val location = mutableStateOf(Location(projectsView, null))
        nativeSceneTest(390, 844, content = {
            val current = location.value
            GenericNativeAppScreen(
                schema = schema,
                view = current.view,
                state = NativeScreenState.Ready(if (current.view == projectsView) projectRecords else taskRecords),
                actionExecutor = NativeActionExecutor { error("Navigation must not execute a mutation") },
                selectedRecordId = current.parent?.id,
                selectedRecordResourceId = current.parent?.let { projects.id },
                onSelectRecord = { record -> location.value = Location(tasksView, record) },
            )
        }) {
            assertTrue(has("Project 1"))
            scrollList(2_400f)
            settleUntil { !has("Project 1") }
            val parentTop = topmostTitle("Project ")
            assertNotEquals("Project 1", parentTop, "The parent list must have scrolled before drilling in")

            location.value = Location(tasksView, projectRecords[29])
            settleUntil { has("Task 1") }
            assertTrue(has("Task 1"), "A child collection opens at its own top, not at the parent's offset")

            location.value = Location(projectsView, null)
            settleUntil { has(parentTop) }
            assertEquals(parentTop, topmostTitle("Project "), "Back returns to where the parent list was")
            assertFalse(has("Project 1"))
        }
    }

    @Test
    fun theSameChildViewKeepsSeparatePositionsForDifferentParents() {
        val location = mutableStateOf(Location(tasksView, projectRecords[0]))
        nativeSceneTest(390, 844, content = {
            val current = location.value
            GenericNativeAppScreen(
                schema = schema,
                view = current.view,
                state = NativeScreenState.Ready(taskRecords),
                actionExecutor = NativeActionExecutor { error("Navigation must not execute a mutation") },
                selectedRecordId = current.parent?.id,
                selectedRecordResourceId = current.parent?.let { projects.id },
            )
        }) {
            scrollList(2_400f)
            settleUntil { !has("Task 1") }
            location.value = Location(tasksView, projectRecords[1])
            settleUntil { has("Task 1") }
            assertTrue(has("Task 1"), "Another project's tasks open at their own top")
        }
    }

    private fun NativeSceneTestDriver.scrollList(pixels: Float) {
        val scroller = assertNotNull(nodes().firstOrNull {
            it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null &&
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
        }, "The collection must be scrollable")
        assertTrue(scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, pixels))
    }

    private fun NativeSceneTestDriver.topmostTitle(prefix: String): String = nodes()
        .mapNotNull { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            text?.takeIf { it.startsWith(prefix) && node.boundsInRoot.bottom > 0f }?.let { it to node.boundsInRoot.top }
        }
        .minByOrNull { (_, top) -> top }
        ?.first
        ?: fail("No visible $prefix rows")
}
