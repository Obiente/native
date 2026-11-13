package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task lists, tasks, and task details at phone, tablet, and desktop widths over synthetic CalDAV. */
class TasksWorkspaceLayoutTest {
    @Test
    fun phoneOpensTaskDetailsInASheetOverTheList() {
        nativeSceneTest(390, 844, content = { Tasks() }) {
            settleUntil(TIMEOUT) { has("Buy stamps") }
            click("Buy stamps")
            settleUntil { has("Close task") }
            assertTrue(has("Post office before noon"), "The sheet shows the task description")
            assertTrue(has("Draft the report"), "The list stays behind the sheet")
            settleAt("Close task")
            click("Close task")
            settleUntil { !has("Close task") }
            assertFalse(has("Post office before noon"))
        }
    }

    @Test
    fun phoneSheetReopensOnTheSameTaskAfterRecreation() {
        val composed = mutableStateOf(true)
        nativeSceneTest(390, 844, content = {
            val holder = rememberSaveableStateHolder()
            if (composed.value) holder.SaveableStateProvider("tasks") { Tasks() }
        }) {
            settleUntil(TIMEOUT) { has("Buy stamps") }
            click("Buy stamps")
            settleUntil { has("Close task") }
            composed.value = false
            settleUntil { !has("Close task") }
            composed.value = true
            settleUntil(TIMEOUT) { has("Close task") && has("Post office before noon") }
            assertTrue(has("Close task"), "The saved selection reopens the same task")
        }
    }

    @Test
    fun tabletShowsTheTaskInspectorBesideTheList() {
        nativeSceneTest(1000, 800, content = { Tasks() }) {
            settleUntil(TIMEOUT) { has("Buy stamps") }
            click("Buy stamps")
            settleUntil { has("Post office before noon") }
            assertFalse(has("Close task"), "Wide layouts use the inspector, not a sheet")
            assertTrue(has("Draft the report"))
            click("Draft the report")
            settleUntil { has("Quarterly numbers") }
            assertFalse(has("Post office before noon"), "Choosing another task replaces the inspector")
        }
    }

    @Test
    fun desktopShowsTaskListsAsASidebarFilter() {
        nativeSceneTest(1280, 800, content = { Desktop { Tasks() } }) {
            settleUntil(TIMEOUT) { has("Buy stamps") && has("Open destination Work") }
            assertTrue(has("Open destination All lists"))
            click("Open destination Work")
            settleUntil { !has("Buy stamps") }
            assertTrue(has("Draft the report"), "The Work list keeps its tasks")
            click("Draft the report")
            settleUntil { has("Quarterly numbers") }
            assertFalse(has("Close task"))
        }
    }

    @Test
    fun narrowDesktopKeepsTheTaskDetailsDialog() {
        nativeSceneTest(700, 800, content = { Desktop { Tasks() } }) {
            settleUntil(TIMEOUT) { has("Buy stamps") }
            click("Buy stamps")
            settleUntil { has("Task details") }
            assertFalse(has("Close task"), "Narrow desktop windows use a dialog, not a phone sheet")
        }
    }

    @Composable
    private fun Tasks() {
        NativeGroupwareTasksScreen(fixtureServices, session, "alice", onBack = {})
    }

    @Composable
    private fun Desktop(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                isDesktop = true,
                usesDenseControls = true,
                supportsAuxiliaryPane = true,
            ),
            content = content,
        )
    }

    private val session = NextcloudSession("https://tasks-layout.invalid", "alice", "synthetic")
    private val home = groupwareCalendarHomeHref("alice")
    private val personal = "${home}personal/"
    private val work = "${home}work/"
    private val tasks = mapOf(
        personal to listOf(Triple("stamps", "Buy stamps", "Post office before noon")),
        work to listOf(Triple("report", "Draft the report", "Quarterly numbers")),
    )

    private val fixtureServices = Proxy.newProxyInstance(
        NextcloudPlatformServices::class.java.classLoader,
        arrayOf(NextcloudPlatformServices::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "SyntheticTasksLayoutServices"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.singleOrNull()
            else -> {
                val operation: suspend () -> Any? = {
                    when (method.name) {
                        "loadDurableMutationRecovery" -> null
                        "executeGroupwareDav" -> execute(args!![1] as GroupwareDavRequest)
                        else -> error("Unexpected synthetic tasks operation: ${method.name}")
                    }
                }
                @Suppress("UNCHECKED_CAST")
                operation.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
            }
        }
    } as NextcloudPlatformServices

    private fun execute(request: GroupwareDavRequest): NextcloudApiResponse {
        val calendar = tasks.keys.firstOrNull { it == request.relativePath }
        val xml = when {
            request.relativePath == home -> taskList(personal, "Personal") + taskList(work, "Work")
            calendar != null && request.method == "PROPFIND" ->
                "<d:response><d:href>$calendar</d:href></d:response>" +
                    tasks.getValue(calendar).joinToString("") { (uid, _, _) ->
                        item("$calendar$uid.ics", "<d:getetag>&quot;etag-$uid&quot;</d:getetag>")
                    }
            calendar != null -> tasks.getValue(calendar).joinToString("") { (uid, title, description) ->
                val content = createGroupwareTaskContent(uid, title, null, false, description)
                item("$calendar$uid.ics", "<d:getetag>&quot;etag-$uid&quot;</d:getetag><c:calendar-data>$content</c:calendar-data>")
            }
            else -> error("Unexpected synthetic CalDAV request: ${request.method} ${request.relativePath}")
        }
        return NextcloudApiResponse(
            207,
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">$xml</d:multistatus>".encodeToByteArray(),
            "application/xml",
            null,
        )
    }

    private fun taskList(href: String, name: String) = item(
        href,
        "<d:displayname>$name</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
            "<d:current-user-privilege-set><d:privilege><d:write-content/></d:privilege></d:current-user-privilege-set>" +
            "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>",
    )

    private fun item(href: String, props: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
