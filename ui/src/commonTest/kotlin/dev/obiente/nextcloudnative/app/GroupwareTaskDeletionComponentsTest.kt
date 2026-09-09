package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupwareTaskDeletionComponentsTest {
    private val content = createGroupwareTaskContent("one", "Task", null, false)
    private val calendar = "/remote.php/dav/calendars/person/tasks/"
    private val task = requireNotNull(parseGroupwareTask(calendar, "${calendar}one", "v1", content))

    @Test
    fun siblingDataComponentsPreventWholeObjectDeletionInEitherOrder() {
        listOf("VEVENT", "VJOURNAL", "VFREEBUSY", "VTODO", "X-OTHER").forEach { name ->
            val sibling = "BEGIN:$name\r\nUID:other\r\nEND:$name\r\n"
            listOf(
                content.replace("BEGIN:VTODO", sibling + "BEGIN:VTODO"),
                content.replace("END:VCALENDAR", sibling + "END:VCALENDAR"),
            ).forEach { mixed -> assertFalse(isGroupwareTaskObjectDeleteSafe(task.copy(rawCalendar = mixed)), name) }
        }
    }

    @Test
    fun supportingTimezonesAndOwnedAlarmsRemainDeletable() {
        val timezone = "BEGIN:VTIMEZONE\r\nTZID:Example\r\nBEGIN:STANDARD\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
        val withAlarm = content.replace("END:VTODO", "BEGIN:VALARM\r\nACTION:DISPLAY\r\nEND:VALARM\r\nEND:VTODO")
        listOf(content, withAlarm, withAlarm.replace("BEGIN:VTODO", timezone + "BEGIN:VTODO"),
            withAlarm.replace("END:VCALENDAR", timezone + "END:VCALENDAR")).forEach { valid ->
            assertTrue(isGroupwareTaskObjectDeleteSafe(task.copy(rawCalendar = valid)))
            assertTrue(isGroupwareTaskObjectDeleteSafe(task.copy(rawCalendar = valid.lowercase())))
        }
    }

    @Test
    fun incompleteMismatchedAndNestedCalendarObjectsAreNotDeleteSafe() {
        listOf(
            "", content + content, content.replace("END:VTODO", ""),
            content.replace("END:VTODO", "END:VEVENT"), content.replace("END:VCALENDAR", ""),
            content.replace("BEGIN:VCALENDAR\r\n", ""),
            content.replace("END:VTODO", "BEGIN:VEVENT\r\nEND:VTODO\r\nEND:VEVENT"),
            content.replace("END:VTODO", "BEGIN:VEVENT\r\nEND:VEVENT\r\nEND:VTODO"),
            content.replace("BEGIN:VTODO", "BEGIN:VTIMEZONE\r\nBEGIN:VTODO")
                .replace("END:VTODO", "END:VTODO\r\nEND:VTIMEZONE"),
        ).forEach { invalid -> assertFalse(isGroupwareTaskObjectDeleteSafe(task.copy(rawCalendar = invalid))) }
    }
}
