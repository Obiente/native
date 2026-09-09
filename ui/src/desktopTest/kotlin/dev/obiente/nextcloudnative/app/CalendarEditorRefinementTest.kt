package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CalendarEditorRefinementTest {
    @Test
    fun phoneStartsWithTitleAndScheduleAndKeepsRecurrenceCompact() {
        nativeSceneTest(390, 900, content = {
            EventEditorDialog(event(), "20260804", listOf(calendar()), {}, null,
                onSave = { _, _ -> }, inPlace = true)
        }) {
            assertTrue(assertNotNull(node("Title")).boundsInRoot.top < assertNotNull(node("Date")).boundsInRoot.top)
            assertTrue(assertNotNull(node("Ends (UTC)")).boundsInRoot.top < assertNotNull(node("Repeats")).boundsInRoot.top)
            assertTrue(has("Does not repeat"))
            assertFalse(has("Weekly"))
            assertTrue(has("Location"))
            capture("calendar-editor-refined-phone")
        }
    }

    @Test
    fun recurrenceMenuKeepsExactPresetAndCalendarCallbacks() {
        var saved: EventDraft? = null
        var target: GroupwareCalendar? = null
        val second = calendar().copy(href = "/calendars/synthetic/personal/", displayName = "Personal")
        nativeSceneTest(800, 1100, content = {
            EventEditorDialog(event(), "20260804", listOf(calendar(), second), {}, null,
                onSave = { draft, calendar -> saved = draft; target = calendar }, inPlace = true)
        }) {
            click("Repeats")
            assertTrue(has("Weekly"))
            click("Weekly")
            assertFalse(has("Monthly"))
            click("Calendar")
            click("Personal")
            click("Save")
            assertEquals("FREQ=WEEKLY", saved?.recurrenceRule)
            assertEquals(second, target)
            assertEquals("2026-08-04", saved?.date)
        }
    }

    @Test
    fun customRecurrenceStaysEditableAndInvalidRulesCannotSave() {
        var saves = 0
        nativeSceneTest(600, 1100, content = {
            EventEditorDialog(event(), "20260804", listOf(calendar()), {}, null,
                onSave = { _, _ -> saves++ }, inPlace = true)
        }) {
            click("Repeats")
            click("Custom")
            assertTrue(has("Recurrence rule"))
            replaceText("FREQ=WEEKLY;INTERVAL=2", "FREQ=INVALID")
            click("Save")
            assertEquals(0, saves)
            replaceText("FREQ=INVALID", "FREQ=WEEKLY;BYDAY=MO,WE")
            click("Save")
            assertEquals(1, saves)
        }
    }

    @Test
    fun allDaySwitchRestoresTimeDraftAndUsesClockPicker() {
        nativeSceneTest(390, 900, content = {
            EventEditorDialog(event(), "20260804", listOf(calendar()), {}, null,
                onSave = { _, _ -> }, inPlace = true)
        }) {
            replaceText("09:00", "11:30")
            click("All day")
            assertFalse(has("Starts (UTC)"))
            click("All day")
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "11:30" })
            assertTrue(has("Choose Starts (UTC)"))
        }
    }

    @Test
    fun pendingWriteClosesChoicesAndDoesNotReopenThemOnCompletion() {
        val busy = mutableStateOf(false)
        var saves = 0
        nativeSceneTest(600, 900, content = {
            EventEditorDialog(event(), "20260804", listOf(calendar()), {}, null,
                mutationInProgress = busy.value, onSave = { _, _ -> saves++ }, inPlace = true)
        }) {
            click("Repeats")
            assertTrue(has("Monthly"))
            busy.value = true
            settle()
            assertFalse(has("Monthly"))
            click("Repeats")
            click("Save")
            assertEquals(0, saves)
            busy.value = false
            settle()
            assertFalse(has("Monthly"))
        }
    }

    @Test
    fun shortFontScaledEditorKeepsActionsVisibleWhileFormScrolls() {
        var saved: EventDraft? = null
        nativeSceneTest(320, 380, fontScale = 1.5f, content = {
            EventEditorDialog(event(), "20260804", listOf(calendar()), {}, null,
                onSave = { draft, _ -> saved = draft }, inPlace = true)
        }) {
            replaceText("Example planning", "Changed title")
            val scroller = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
            })
            assertTrue(scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10000f))
            settle()
            for (label in listOf("Cancel", "Save")) {
                val bounds = assertNotNull(node(label)).boundsInRoot
                assertTrue(bounds.top >= 0f && bounds.bottom <= 380f && bounds.right <= 320f)
            }
            click("Save")
            assertEquals("Changed title", saved?.title)
            capture("calendar-editor-refined-short-fontscaled")
        }
    }

    @Test
    fun detailDateRangeAndSeriesScopeStayVisible() {
        val occurrence = event().copy(
            title = "Summer break", start = "20260820", end = "20260824", allDay = true,
            recurrenceRule = "FREQ=WEEKLY", isGeneratedOccurrence = true,
        )
        nativeSceneTest(390, 800, content = { CalendarEventDetailsBody(occurrence) }) {
            assertTrue(has("20 August 2026 - 23 August 2026"))
            assertTrue(has("All day"))
            assertTrue(has("This occurrence is read-only to protect the complete recurring series."))
        }
    }

    private fun calendar() = GroupwareCalendar(
        href = "/calendars/synthetic/", displayName = "Example calendar", color = null, writable = true,
    )

    private fun event() = GroupwareCalendarEvent(
        href = "/calendars/synthetic/example.ics", etag = "\"synthetic\"", calendarHref = calendar().href,
        uid = "example", title = "Example planning", start = "20260804T090000Z", end = "20260804T100000Z",
        allDay = false, rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
