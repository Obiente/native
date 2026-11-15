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
    fun phoneMonthShowsEventsInTheirDaysAndOpensADaySchedule() {
        val selectedDate = mutableStateOf("20260804")
        val view = mutableStateOf(CalendarWorkspaceView.Month)
        var opened: String? = null
        val events = listOf(event("one", "20260804T090000Z"), event("two", "20260805T100000Z"))
        nativeSceneTest(360, 800, content = {
            NextcloudAdaptiveShell(NextcloudDestination.Apps, {},
                NextcloudDesktopIdentity("Example", "Example Cloud", availableApps = listOf(NextcloudDesktopSidebarApp("calendar", "Calendar"))),
                activeAppId = "calendar") {
                Column(Modifier.fillMaxSize()) {
                    CalendarPhoneTopBar({}, {}, {}, createEnabled = true)
                    MobileGroupwareCalendarWorkspace(CalendarMonth(2026, 8), selectedDate.value, view.value,
                        listOf(calendar), events, emptySet(), "", {}, {}, {}, { view.value = it }, {}, { _, _ -> },
                        { selectedDate.value = it }, { opened = it.instanceId }, Modifier.weight(1f), todayDate = "20260804")
                }
            }
        }) {
            // Like a wall calendar, every event is visible in its own day without opening it.
            listOf("Event one", "Event two").forEach { title ->
                val bounds = assertNotNull(node(title)).boundsInRoot
                assertTrue(bounds.top > 0 && bounds.bottom < 720, "$title belongs above the shell's bottom navigation")
            }
            val day = assertNotNull(node("4 August 2026, 1 event, today"))
            assertEquals(true, day.config.getOrNull(SemanticsProperties.Selected))
            click("Event two")
            assertEquals(events[1].instanceId, opened)
            assertEquals(CalendarWorkspaceView.Month, view.value, "Opening an event keeps the month")
            capture("calendar-phone-month-refined")
            // Tap the day itself, below its chip; the chip opens its event instead.
            val nextDay = assertNotNull(node("5 August 2026, 1 event")).boundsInRoot
            click(androidx.compose.ui.geometry.Offset(nextDay.center.x, nextDay.bottom - 6f))
            assertEquals("20260805", selectedDate.value)
            assertEquals(CalendarWorkspaceView.Day, view.value, "Tapping a day opens its schedule")
        }
    }

    @Test
    fun phoneWeekIsATimeGridThatIncludesAnEventStartedBeforeTheWeek() {
        val selectedDate = mutableStateOf("20260801")
        val view = mutableStateOf(CalendarWorkspaceView.Week)
        val spanning = event("conference", "20260725").copy(end = "20260803", allDay = true)
        val meeting = event("standup", "20260729T100000Z")
        var created: String? = null
        nativeSceneTest(390, 700, content = {
            MobileGroupwareCalendarWorkspace(CalendarMonth(2026, 8), selectedDate.value, view.value,
                listOf(calendar), listOf(spanning, meeting), emptySet(), "", {}, {}, {}, { view.value = it }, {}, { _, _ -> },
                { selectedDate.value = it }, {}, onCreateAt = { created = it }, todayDate = "20260801")
        }) {
            assertTrue(has("Event conference"), "A spanning event sits in the all-day row")
            val standup = assertNotNull(node("Event standup")).boundsInRoot
            val tenOClock = assertNotNull(node("10:00")).boundsInRoot
            assertTrue(kotlin.math.abs(standup.top - tenOClock.top) < 24f, "A timed event sits at its hour")
            capture("calendar-phone-week-refined")
            click("2 August 2026, 1 event")
            assertEquals("20260802", selectedDate.value)
            assertEquals(CalendarWorkspaceView.Day, view.value)
            repeat(4) { settle() }
            assertTrue(has("Event conference"))
            val day = assertNotNull(node("Schedule for 2 August 2026"))
            val dayTen = assertNotNull(node("10:00")).boundsInRoot
            click(androidx.compose.ui.geometry.Offset(day.boundsInRoot.center.x, dayTen.top + 30f))
            assertEquals(true, created?.startsWith("20260802T10"), "Tapping empty time offers an event starting there: $created")
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
            assertEquals(4, tabs.size, "Month, Week, Day, and Agenda")
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
