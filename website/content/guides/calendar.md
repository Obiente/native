---
title: Plan your work with Calendar
slug: calendar
description: Navigate month and agenda views, create complete events, choose calendars, and update schedules without losing the surrounding workspace.
category: Calendar and planning
platforms: Android, Desktop
durationMinutes: 7
difficulty: Everyday
lastUpdated: 2026-08-03
captureScenarios: guide-calendar-month, guide-calendar-mobile, guide-calendar-planning
prerequisites: Calendar installed on the connected Nextcloud server, At least one writable calendar
---

# Plan your work with Calendar

The native Calendar workspace keeps your schedule visible while new data loads, supports compact phone navigation and a multi-pane desktop layout, and sends changes through your Nextcloud CalDAV calendars.

## 1. Read the month and choose a calendar

@capture-alt: Desktop Calendar workspace with calendar sources, a populated month grid, selected-day events, event details, and persistent Nextcloud navigation
@capture-caption: Desktop Calendar uses the available width for sources, the schedule, and a useful inspector instead of stretching a phone view.

Open **Calendar** from the pinned sidebar or Apps. Use the left source list to show or hide calendars without deleting them. Choose **Today** to return to the current date, or use the previous and next controls to move by month. Existing content remains visible while the new range refreshes.

Select a day to review its events and choose an event for details. Colors help distinguish sources, but names and labels remain available so status never depends on color alone. Read-only calendars can be viewed but do not expose editing actions.

## 2. Use the compact mobile agenda

@capture-alt: Mobile Calendar workspace with a compact month picker, agenda items, source colors, dates, times, and an add-event action
@capture-caption: On a phone, Calendar keeps the month compact and gives the agenda enough room for touch-sized event rows and clear time information.

On a phone, move through the compact month grid and review the agenda below it. Tap an event to open its details. Use system Back to return to the same date and view instead of restarting at the current month. The selected calendar and view survive ordinary activity recreation.

When the connection is slow, the current schedule stays in place with a small progress indicator. Avoid repeating a create or edit action while its result is unknown; wait for the app to confirm the updated event or explain the failure.

## 3. Create and update an event safely

@capture-alt: Desktop Calendar planning workspace with multiple calendars, scheduled product reviews, community events, birthdays, and event details
@capture-caption: A populated planning view makes calendar choice, recurring work, and event context visible before an edit is submitted.

Choose **New event**, enter a clear title, and select the correct writable calendar. Add the start and end date or time, location, description, and recurrence when needed. Review the time zone before saving an event for people in another region. All-day events should describe work that is not tied to a particular hour.

Open an existing event to edit or delete it. Updates use the latest server version so a stale window cannot silently replace somebody else's newer change. If Nextcloud reports a conflict, refresh the event, review the current details, and apply your change again deliberately.
