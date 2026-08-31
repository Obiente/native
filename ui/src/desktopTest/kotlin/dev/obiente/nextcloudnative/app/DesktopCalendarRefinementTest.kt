package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopCalendarRefinementTest {
    @Test
    fun monthOverflowOpensTheCompleteDayAndClearsThePreviousInspectorSelection() {
        val events = (1..8).map(::event)
        val selected = mutableStateOf<GroupwareCalendarEvent?>(events.first())
        val date = mutableStateOf("20260804")
        var selectedInstance: String? = null
        nativeSceneTest(1200, 900, content = {
            CalendarFixture(
                events = events, selectedEvent = selected.value, selectedDate = date.value,
                onSelectDate = { date.value = it },
                onSelectEvent = { selected.value = it; selectedInstance = it?.instanceId },
            )
        }) {
            click("Show all 8 events on 4 August 2026")
            assertEquals("20260804", date.value)
            assertEquals(null, selected.value)
            assertTrue(has("Day schedule"))
            assertFalse(has("Edit event"))
            repeat(6) {
                if (!has("Planning item 8")) scrollInspector()
            }
            assertTrue(has("Planning item 8"), "Every overflow item must be reachable in the day schedule")
            click("Planning item 8")
            assertEquals(events.last().instanceId, selectedInstance)
            assertTrue(has("Event details"))
            capture("calendar-desktop-overflow-day")
        }
    }

    @Test
    fun weekColumnsScrollToLaterEventsAndKeepExactOccurrenceIdentity() {
        val events = (1..20).map(::event)
        var selected: GroupwareCalendarEvent? = null
        nativeSceneTest(1200, 540, content = {
            CalendarFixture(events = events, view = CalendarWorkspaceView.Week, onSelectEvent = { selected = it })
        }) {
            val description = "Events on 4 August 2026"
            val column = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true &&
                    it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            })
            assertTrue(column.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10000f))
            settle()
            assertTrue(has("Planning item 20"))
            click("Planning item 20")
            assertEquals(events.last().instanceId, selected?.instanceId)
            capture("calendar-desktop-scrollable-week")
        }
    }

    @Test
    fun calendarFiltersExposeCheckedStateAndTheirOwnTarget() {
        val hidden = mutableStateOf(emptySet<String>())
        var changedHref: String? = null
        nativeSceneTest(1440, 900, content = {
            CalendarFixture(hidden = hidden.value, onVisibilityChanged = { href, visible ->
                changedHref = href
                hidden.value = if (visible) hidden.value - href else hidden.value + href
            })
        }) {
            val checkbox = assertNotNull(node("Show Example calendar"))
            assertEquals(Role.Checkbox, checkbox.config.getOrNull(SemanticsProperties.Role))
            assertEquals(ToggleableState.On, checkbox.config.getOrNull(SemanticsProperties.ToggleableState))
            click("Show Example calendar")
            assertEquals(calendar().href, changedHref)
            assertEquals(ToggleableState.Off, assertNotNull(node("Show Example calendar")).config.getOrNull(SemanticsProperties.ToggleableState))
            click("Show Example calendar")
            assertTrue(hidden.value.isEmpty())
        }
    }

    @Test
    fun paneCloseControlsPreserveSelectionAndReturnCanvasSpace() {
        val selected = event(1)
        var selections = 0
        nativeSceneTest(1440, 900, content = {
            CalendarFixture(selectedEvent = selected, onSelectEvent = { selections++ })
        }) {
            val narrowWidth = assertNotNull(node("Mon")).boundsInRoot.width
            click("Hide details panel")
            assertFalse(has("Event details"))
            click("Hide calendars panel")
            assertFalse(has("My calendars"))
            assertTrue(assertNotNull(node("Mon")).boundsInRoot.width > narrowWidth + 30)
            click("Details")
            assertTrue(has("Planning item 1"))
            assertEquals(0, selections, "Closing panels is presentation state, not event navigation")
        }
    }

    @Test
    fun todayAndSelectedDayExposeSeparateLabelsAndSelection() {
        nativeSceneTest(1200, 900, content = {
            CalendarFixture(selectedDate = "20260805")
        }) {
            val today = assertNotNull(node("4 August 2026, 1 event, today"))
            val selected = assertNotNull(node("5 August 2026, 0 events"))
            assertEquals(false, today.config.getOrNull(SemanticsProperties.Selected))
            assertEquals(true, selected.config.getOrNull(SemanticsProperties.Selected))
        }
    }

    @Test
    fun narrowLargeFontToolbarKeepsItsPeriodAndPrimaryActionsReadable() {
        for (width in listOf(560, 760)) {
            nativeSceneTest(width, 900, fontScale = 1.5f, content = { CalendarFixture() }) {
                capture("calendar-desktop-narrow-large-font-$width")
                listOf("Calendar", "August 2026", "Previous month", "Next month", "Today", "Refresh calendars", "New event").forEach { label ->
                    val control = assertNotNull(node(label), "Missing desktop toolbar control: $label")
                    val bounds = control.boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width, "$label must remain within the $width dp workspace")
                    assertTrue(bounds.width > 0 && bounds.height > 0, "$label must not collapse when the toolbar is narrow")
                    val textLayout = mutableListOf<TextLayoutResult>()
                    control.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(textLayout)
                    textLayout.forEach { assertCompleteTextWithinRoundedBounds(it, label) }
                }
                val headerScroll = assertNotNull(nodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null &&
                        it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
                })
                assertTrue(headerScroll.config[SemanticsActions.ScrollBy].action!!.invoke(10000f, 0f))
                settle()
                val search = assertNotNull(node("Search events")).boundsInRoot
                assertTrue(search.left >= 0 && search.right <= width, "Search must be reachable in the overflow toolbar")
            }
        }
    }

    @Test
    fun largeFontMonthOverflowStaysInsideItsOwnDayCell() {
        nativeSceneTest(760, 900, fontScale = 1.5f, content = { CalendarFixture(events = (1..20).map(::event)) }) {
            val day = assertNotNull(node("4 August 2026, 20 events, today")).boundsInRoot
            val overflow = assertNotNull(node("Show all 20 events on 4 August 2026")).boundsInRoot
            assertTrue(overflow.top >= day.top && overflow.bottom <= day.bottom, "Overflow action must not spill into the next date")
            assertTrue(overflow.left >= day.left && overflow.right <= day.right)
            capture("calendar-desktop-large-font-month-overflow")
        }
    }

    @Test
    fun ordinaryDesktopWeekShowsEveryDayBesideTheInspector() {
        nativeSceneTest(823, 680, content = {
            DesktopCalendarWeek(
                calendarWeekDates("20260804"), "20260804", "20260804", emptyMap(), emptyMap(), {}, { _, _ -> },
            )
        }) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { day ->
                val bounds = assertNotNull(node(day)).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.left >= 0 && bounds.right <= 823, "$day must be visible without horizontal scrolling")
            }
            assertFalse(has("Scroll week"))
            capture("calendar-desktop-seven-day-week")
        }
    }

    @Test
    fun narrowLargeFontWeekOffersPointerControlsToReachTheLastDay() {
        var selections = 0
        nativeSceneTest(560, 680, fontScale = 1.5f, content = {
            DesktopCalendarWeek(
                calendarWeekDates("20260804"), "20260804", "20260804", emptyMap(), emptyMap(),
                { selections++ }, { _, _ -> selections++ },
            )
        }) {
            assertTrue(has("Scroll week"))
            repeat(2) { click("Show later days") }
            val sunday = assertNotNull(node("Sun")).boundsInRoot
            assertTrue(sunday.width > 0 && sunday.left >= 0 && sunday.right <= 560)
            repeat(2) { click("Show earlier days") }
            val monday = assertNotNull(node("Mon")).boundsInRoot
            assertTrue(monday.width > 0 && monday.left >= 0 && monday.right <= 560)
            assertEquals(0, selections, "Scrolling the week must not select dates or events")
            capture("calendar-desktop-week-overflow-controls")
        }
    }

    @Test
    fun ordinaryWeekCardsKeepShortWordsTogether() {
        val appointment = event(9).copy(title = "Dentist appointment")
        nativeSceneTest(823, 680, content = {
            DesktopCalendarWeek(
                calendarWeekDates("20260804"), "20260804", "20260804",
                mapOf("20260804" to listOf(appointment)), mapOf(calendar().href to calendar()), {}, { _, _ -> },
            )
        }) {
            val text = assertNotNull(node("Dentist appointment"))
            val layout = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layout))
            assertTrue(layout.isNotEmpty())
            assertTrue(layout.all { it.lineCount <= 2 }, "A short appointment title must not leave a single letter on a third line")
            layout.forEach { assertCompleteTextWithinRoundedBounds(it, "Dentist appointment") }
            capture("calendar-desktop-week-card-word-wrapping")
        }
    }

    private fun assertCompleteTextWithinRoundedBounds(layout: TextLayoutResult, label: String) {
        assertTrue(layout.lineCount > 0, "$label must render text")
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line), "$label must not be ellipsized")
            assertTrue(layout.getLineLeft(line) >= -1f, "$label must not overflow its left edge")
            assertTrue(layout.getLineRight(line) <= layout.size.width + 1f,
                "$label must fit its measured width, allowing only integer-pixel rounding")
        }
        val lastLine = layout.lineCount - 1
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(lastLine), "$label must retain every character")
        assertTrue(layout.getLineBottom(lastLine) <= layout.size.height + 1f,
            "$label must fit its measured height, allowing only integer-pixel rounding")
    }

    private suspend fun NativeSceneTestDriver.scrollInspector() {
        val scroll = assertNotNull(nodes().lastOrNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null })
        assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 400f))
        settle()
    }

    @Composable
    private fun CalendarFixture(
        events: List<GroupwareCalendarEvent> = listOf(event(1)),
        selectedEvent: GroupwareCalendarEvent? = null,
        selectedDate: String = "20260804",
        view: CalendarWorkspaceView = CalendarWorkspaceView.Month,
        hidden: Set<String> = emptySet(),
        onVisibilityChanged: (String, Boolean) -> Unit = { _, _ -> },
        onSelectDate: (String) -> Unit = {},
        onSelectEvent: (GroupwareCalendarEvent?) -> Unit = {},
    ) {
        DesktopGroupwareCalendarWorkspace(
            month = CalendarMonth(2026, 8), selectedDate = selectedDate, view = view,
            calendars = listOf(calendar()), events = events, hiddenCalendarHrefs = hidden,
            query = "", selectedEvent = selectedEvent, onPrevious = {}, onNext = {}, onToday = {},
            onViewChanged = {}, onQueryChanged = {}, onCalendarVisibilityChanged = onVisibilityChanged,
            onSelectDate = onSelectDate, onSelectEvent = onSelectEvent, onCreateEvent = {}, onRefresh = {},
            onEditEvent = {}, onDeleteEvent = {}, todayDate = "20260804",
        )
    }

    private fun calendar() = GroupwareCalendar(
        href = "/calendars/synthetic/", displayName = "Example calendar", color = null, writable = true,
    )

    private fun event(index: Int) = GroupwareCalendarEvent(
        href = "/calendars/synthetic/item-$index.ics", etag = "\"synthetic-$index\"", calendarHref = calendar().href,
        uid = "item-$index", title = "Planning item $index",
        start = "20260804T${index.toString().padStart(2, '0')}0000Z",
        end = "20260804T${(index + 1).toString().padStart(2, '0')}0000Z",
        allDay = false, rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
