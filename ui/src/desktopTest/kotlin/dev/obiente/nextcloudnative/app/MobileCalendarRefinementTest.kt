package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import dev.obiente.nextcloudnative.app.design.NextcloudAdaptiveShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MobileCalendarRefinementTest {
    @Test
    fun fullPhoneShellShowsSelectedDayEventsWithoutScrollingPastTheMonth() {
        val selectedDate = mutableStateOf("20260804")
        var opened: String? = null
        val events = listOf(event("one", "20260804T090000Z"), event("two", "20260805T100000Z"))
        nativeSceneTest(360, 800, content = {
            NextcloudAdaptiveShell(NextcloudDestination.Apps, {},
                NextcloudDesktopIdentity("Example", "Example Cloud", availableApps = listOf(NextcloudDesktopSidebarApp("calendar", "Calendar"))),
                activeAppId = "calendar") {
                Column(Modifier.fillMaxSize()) {
                    CalendarPhoneTopBar({}, {}, {}, createEnabled = true)
                    MobileGroupwareCalendarWorkspace(CalendarMonth(2026, 8), selectedDate.value, CalendarWorkspaceView.Month,
                        listOf(calendar), events, emptySet(), "", {}, {}, {}, {}, {}, { _, _ -> },
                        { selectedDate.value = it }, { opened = it.instanceId }, Modifier.weight(1f), todayDate = "20260804")
                }
            }
        }) {
            val eventBounds = assertNotNull(node("Event one")).boundsInRoot
            assertTrue(eventBounds.top > 0 && eventBounds.bottom < 720, "The first event belongs above the shell's bottom navigation")
            assertTrue(has("4 August 2026, 1 event, Today"))
            val day = assertNotNull(node("4 August 2026, 1 event, Today"))
            assertEquals(true, day.config.getOrNull(SemanticsProperties.Selected))
            click("5 August 2026, 1 event")
            assertEquals("20260805", selectedDate.value)
            assertTrue(has("Event two"))
            assertFalse(has("Event one"))
            click("Event two")
            assertEquals(events[1].instanceId, opened)
            capture("calendar-phone-month-refined")
        }
    }

    @Test
    fun phoneWeekUsesSelectableDaysAndIncludesAnEventStartedBeforeTheWeek() {
        val selectedDate = mutableStateOf("20260801")
        val spanning = event("conference", "20260725").copy(end = "20260803", allDay = true)
        nativeSceneTest(390, 700, content = {
            MobileGroupwareCalendarWorkspace(CalendarMonth(2026, 8), selectedDate.value, CalendarWorkspaceView.Week,
                listOf(calendar), listOf(spanning), emptySet(), "", {}, {}, {}, {}, {}, { _, _ -> },
                { selectedDate.value = it }, {}, todayDate = "20260801")
        }) {
            assertTrue(has("Event conference"))
            click("2 August 2026, 1 event")
            assertEquals("20260802", selectedDate.value)
            assertTrue(has("Event conference"))
            capture("calendar-phone-week-refined")
        }
    }

    @Test
    fun calendarCheckboxesChangeOnlyVisibilityAndSearchCanBeCleared() {
        val hidden = mutableStateOf<Set<String>>(emptySet())
        val query = mutableStateOf("")
        var opened = 0
        nativeSceneTest(390, 844, content = {
            MobileGroupwareCalendarWorkspace(CalendarMonth(2026, 8), "20260804", CalendarWorkspaceView.Agenda,
                listOf(calendar), listOf(event("planning", "20260804T090000Z")), hidden.value, query.value,
                {}, {}, {}, {}, { query.value = it }, { href, visible -> hidden.value = if (visible) hidden.value - href else hidden.value + href },
                {}, { opened++ }, todayDate = "20260804")
        }) {
            click("Choose visible calendars")
            repeat(3) { settle() }
            val toggle = assertNotNull(node("Show Team"))
            assertEquals(Role.Checkbox, toggle.config.getOrNull(SemanticsProperties.Role))
            click("Show Team")
            assertEquals(setOf(calendar.href), hidden.value)
            click("Done")
            repeat(3) { settle() }
            assertFalse(has("Event planning"))
            assertTrue(has("0 of 1 calendars shown"))
            click("0 of 1 calendars shown")
            repeat(3) { settle() }
            click("Show Team")
            click("Done")
            repeat(3) { settle() }
            click("Search events")
            replaceText("", "not found")
            assertTrue(has("No matching events"))
            click("Clear search")
            assertEquals("", query.value)
            assertTrue(has("Event planning"))
            assertEquals(0, opened)
            capture("calendar-phone-search-refined")
        }
    }

    @Test
    fun monthSearchFindsOtherDaysWithoutChangingTheSelectedDateOrView() {
        val query = mutableStateOf("")
        val selectedDate = mutableStateOf("20260804")
        val events = listOf(event("today", "20260804T090000Z"), event("planning", "20260818T100000Z"))
        var opened: String? = null
        nativeSceneTest(390, 844, content = {
            MobileGroupwareCalendarWorkspace(CalendarMonth(2026, 8), selectedDate.value, CalendarWorkspaceView.Month,
                listOf(calendar), events, emptySet(), query.value, {}, {}, {}, {}, { query.value = it }, { _, _ -> },
                { selectedDate.value = it }, { opened = it.instanceId }, todayDate = "20260804")
        }) {
            click("Search events")
            replaceText("", "planning")
            assertTrue(has("Search results"))
            assertTrue(has("18 August 2026"))
            assertTrue(has("Event planning"))
            assertFalse(has("Event today"))
            click("Event planning")
            assertEquals(events[1].instanceId, opened)
            assertEquals("20260804", selectedDate.value)
            click("Close search")
            assertEquals("", query.value)
            assertFalse(has("Search results"))
            assertTrue(has("Event today"))
            val activeTab = nodes().single {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true
            }
            assertEquals(listOf("Month"), activeTab.config[SemanticsProperties.Text].map { it.text })
        }
    }

    @Test
    fun phoneViewControlsHaveTabSelectionSemanticsAndLargeTextCanScroll() {
        val view = mutableStateOf(CalendarWorkspaceView.Week)
        nativeSceneTest(320, 560, fontScale = 1.5f, content = {
            MobileGroupwareCalendarWorkspace(CalendarMonth(2026, 8), "20260804", view.value,
                listOf(calendar), listOf(event("planning", "20260804T090000Z")), emptySet(), "",
                {}, {}, {}, { view.value = it }, {}, { _, _ -> }, {}, {}, todayDate = "20260804")
        }) {
            val tabs = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            assertEquals(3, tabs.size)
            assertEquals(1, tabs.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
            listOf("Previous week", "Next week", "Today", "Choose visible calendars").forEach {
                val bounds = assertNotNull(node(it)).boundsInRoot
                assertTrue(bounds.left >= 0 && bounds.right <= 320 && bounds.top >= 0 && bounds.bottom <= 560)
            }
            val timeLayouts = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(node("09:00")).config[SemanticsActions.GetTextLayoutResult].action!!.invoke(timeLayouts))
            capture("calendar-phone-large-text-refined")
            assertEquals(1, timeLayouts.single().lineCount, "Large text must not split an event time")
            val timeLayout = timeLayouts.single()
            assertFalse(timeLayout.isLineEllipsized(0))
            assertEquals(5, timeLayout.getLineEnd(0), "The complete time must be laid out")
            // Compose rounds its measured IntSize; compare real line extents with that pixel rounding.
            assertTrue(timeLayout.getLineRight(0) <= timeLayout.size.width + 1f,
                "Time exceeds measured width: ${timeLayout.getLineRight(0)} > ${timeLayout.size.width}")
            assertTrue(timeLayout.getLineBottom(0) <= timeLayout.size.height + 1f)
        }
    }

    private val calendar = GroupwareCalendar("/calendars/synthetic/team/", "Team", writable = true)
    private fun event(id: String, start: String) = GroupwareCalendarEvent(
        href = "/calendars/synthetic/team/$id.ics", etag = "\"synthetic\"", calendarHref = calendar.href,
        uid = id, title = "Event $id", start = start, end = "${start.take(8)}T110000Z", allDay = false,
        rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
