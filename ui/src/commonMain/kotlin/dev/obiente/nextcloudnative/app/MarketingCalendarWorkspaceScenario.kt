package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudBottomNavigation
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopWorkspaceKind
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation

@Composable
internal fun MarketingCalendarWorkspaceScenario(
    scenario: MarketingCaptureScenario,
    assets: MarketingCaptureAssets,
) {
    val calendars = marketingCalendarCalendars
    val events = marketingCalendarEvents
    val view = scenario.marketingCalendarView()
    val editor = scenario == MarketingCaptureScenario.CalendarEventEditorMobile ||
        scenario == MarketingCaptureScenario.CalendarEventEditorDesktop
    if (scenario.presentation == NextcloudPresentation.Desktop) {
        NextcloudDesktopShell(
            selected = NextcloudDestination.Apps,
            onSelected = {},
            identity = marketingDesktopIdentity(avatar = assets.avatar),
            activeAppId = "calendar",
            workspaceKind = NextcloudDesktopWorkspaceKind.AppWorkspace,
        ) {
            DesktopGroupwareCalendarWorkspace(
                month = CalendarMonth(2026, 8),
                selectedDate = "20260804",
                view = view,
                calendars = calendars,
                events = events,
                hiddenCalendarHrefs = emptySet(),
                query = "",
                selectedEvent = events.first { event -> event.uid == "design-review" },
                onPrevious = {},
                onNext = {},
                onToday = {},
                onViewChanged = {},
                onQueryChanged = {},
                onCalendarVisibilityChanged = { _, _ -> },
                onSelectDate = {},
                onSelectEvent = {},
                onCreateEvent = {},
                onRefresh = {},
                onEditEvent = {},
                onDeleteEvent = {},
            )
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Calendar", "Your schedule · August 2026", onBack = {})
            MobileGroupwareCalendarWorkspace(
                month = CalendarMonth(2026, 8),
                selectedDate = "20260804",
                view = view,
                calendars = calendars,
                events = events,
                hiddenCalendarHrefs = emptySet(),
                query = "",
                onPrevious = {},
                onNext = {},
                onToday = {},
                onViewChanged = {},
                onQueryChanged = {},
                onCalendarVisibilityChanged = { _, _ -> },
                onSelectDate = {},
                onSelectEvent = {},
                modifier = Modifier.weight(1f),
            )
            NextcloudBottomNavigation(selected = NextcloudDestination.Apps, onSelected = {})
        }
    }
    if (editor) MarketingCalendarEventEditorCapture()
}

internal fun MarketingCaptureScenario.marketingCalendarView(): CalendarWorkspaceView =
    if (surface.contains("agenda", ignoreCase = true) || state.contains("agenda", ignoreCase = true)) {
        CalendarWorkspaceView.Agenda
    } else {
        CalendarWorkspaceView.Month
    }

private fun marketingCalendar(
    id: String,
    name: String,
    writable: Boolean = true,
): GroupwareCalendar = GroupwareCalendar(
    href = "/remote.php/dav/calendars/synthetic/$id/",
    displayName = name,
    color = null,
    writable = writable,
)

private fun marketingCalendarEvent(
    calendarId: String,
    uid: String,
    title: String,
    start: String,
    end: String? = null,
    allDay: Boolean = false,
    location: String? = null,
    description: String? = null,
    recurrenceRule: String? = null,
): GroupwareCalendarEvent = GroupwareCalendarEvent(
    href = "/remote.php/dav/calendars/synthetic/$calendarId/$uid.ics",
    etag = "\"synthetic-$uid\"",
    calendarHref = "/remote.php/dav/calendars/synthetic/$calendarId/",
    uid = uid,
    title = title,
    start = start,
    end = end,
    allDay = allDay,
    location = location,
    description = description,
    recurrenceRule = recurrenceRule,
    rawCalendar = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:$uid\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
)

private val marketingCalendarCalendars = listOf(
    marketingCalendar("personal", "Personal"),
    marketingCalendar("product", "Product team"),
    marketingCalendar("community", "Community"),
    marketingCalendar("birthdays", "Contact birthdays", writable = false),
)

private val marketingCalendarEvents = listOf(
    marketingCalendarEvent(
        "product", "design-review", "Native Calendar design review",
        "20260804T133000Z", "20260804T143000Z",
        location = "Product room · Talk",
        description = "Review the desktop month, week, and agenda workspaces before the release candidate.",
    ),
    marketingCalendarEvent(
        "personal", "dentist", "Dentist appointment",
        "20260804T090000Z", "20260804T094500Z", location = "Health centre",
    ),
    marketingCalendarEvent(
        "product", "standup-3", "Product stand-up",
        "20260803T083000Z", "20260803T090000Z", recurrenceRule = "Every weekday",
    ),
    marketingCalendarEvent(
        "community", "volunteer", "Community garden volunteers",
        "20260805T173000Z", "20260805T190000Z", location = "West garden",
    ),
    marketingCalendarEvent(
        "personal", "focus", "Focus time",
        "20260806T100000Z", "20260806T120000Z",
    ),
    marketingCalendarEvent(
        "product", "release", "Release candidate",
        "20260807", "20260808", allDay = true,
    ),
    marketingCalendarEvent(
        "community", "workshop", "Open-source workshop",
        "20260808T110000Z", "20260808T150000Z", location = "Library studio",
    ),
    marketingCalendarEvent(
        "birthdays", "elena-birthday", "Elena's birthday",
        "20260810", "20260811", allDay = true, recurrenceRule = "Yearly",
    ),
    marketingCalendarEvent(
        "product", "research", "User research synthesis",
        "20260811T140000Z", "20260811T153000Z",
    ),
    marketingCalendarEvent(
        "personal", "train", "Train to Utrecht",
        "20260813T071500Z", "20260813T081000Z", location = "Central station",
    ),
    marketingCalendarEvent(
        "community", "meetup", "Local-first meetup",
        "20260814T180000Z", "20260814T203000Z", location = "Commons hall",
    ),
    marketingCalendarEvent(
        "product", "planning", "Sprint planning",
        "20260817T090000Z", "20260817T103000Z", location = "Product room",
    ),
    marketingCalendarEvent(
        "personal", "holiday", "Summer break",
        "20260820", "20260824", allDay = true,
    ),
    marketingCalendarEvent(
        "community", "board", "Foundation board meeting",
        "20260825T160000Z", "20260825T173000Z", location = "Talk · Board room",
    ),
    marketingCalendarEvent(
        "product", "retro", "Release retrospective",
        "20260828T093000Z", "20260828T103000Z",
    ),
)
