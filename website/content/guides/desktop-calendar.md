---
title: Use Nextcloud Calendar on Linux and Windows
slug: calendar
description: Navigate the desktop month, week, and agenda views, filter calendars, inspect events, and make ETag-protected CalDAV changes on Linux or Windows.
category: Calendar and planning
platform: Desktop
device: Desktop
platforms: Linux, Windows
durationMinutes: 8
difficulty: Everyday
lastUpdated: 2026-10-31
captureScenarios: guide-desktop-calendar-month, guide-desktop-calendar-sources, guide-desktop-calendar-edit
prerequisites: A connected Linux or Windows account, Calendar installed on Nextcloud, At least one CalDAV calendar
---

# Use Nextcloud Calendar on Linux and Windows

**Last reviewed: 2026-10-31.** The software and published packages may have
changed since this review. Check the [current releases](https://github.com/obiente/native/releases)
and [compatibility notes](/compatibility/) before using this guide with important data.

Navigation instructions and synthetic screenshots reflect the reviewed source implementation; published packages may differ.

The desktop Calendar workspace uses the available window for calendar sources, month or week navigation, event lists, and collapsible event details. At narrower window widths, secondary panels open as dialogs so the calendar grid retains usable space. Linux and Windows share the same CalDAV behavior here; this guide does not apply to the unsupported authenticated macOS target.

## 1. Navigate month, week, and agenda views

@capture-alt: nati.ve desktop Calendar month workspace with source list, date grid, event rows, search, selected event inspector, Today, refresh, and view controls
@capture-caption: The desktop Calendar workspace keeps the calendar grid visible, with optional source and event-detail panels.

Open **Calendar** from the sidebar or Apps. Choose month, week, or agenda according to the task. Previous and next move through the active range, while **Today** returns to the current date. Use search to narrow the events already loaded for the view.

The month view uses a continuous grid. When a day contains more events than fit, choose **more** to open its complete schedule. Week columns scroll independently so busy days do not hide later events. Multi-day events appear on every covered date; an all-day event's end date is exclusive. Agenda uses the same time-first event rows as the phone.

Choose **Calendars** to show or hide sources and **Details** to show or hide the inspector. Selecting an event opens its details without discarding the surrounding schedule. The workspace retains the last matching result while a range refreshes. If the refresh message reports a failure, the visible events may be stale; retry before changing a time-sensitive event.

## 2. Filter sources and inspect event authority

@capture-alt: nati.ve desktop Calendar with multiple named sources, visibility controls, writable and read-only labels, event counts, and selected-day details
@capture-caption: Source controls hide or show calendars without deleting them and disclose which collections can accept a new or changed event.

Choose **Calendars**, then use the named checkboxes to hide or show a calendar. Visibility is a local workspace choice and does not delete the CalDAV collection. Read-only sources remain useful for planning but cannot accept edits. The create action is enabled only when at least one writable calendar is available.

Select an event to inspect its date, time, location, description, and recurrence. An event must belong to a writable source, carry an ETag, and identify the authoritative object before editing is enabled. For a repeating event, editing and deleting ask whether the change is for one occurrence or for the whole series.

Choose **Day schedule** in the inspector to return to the other events on that date. Panel close buttons hide the panel without changing the selected event or calendar visibility.

## 3. Create, update, or delete with conflict protection

@capture-alt: nati.ve desktop event editor with title, recurrence, date, all-day toggle, start and end time, location, description, calendar selection, and Save
@capture-caption: The desktop editor gathers a complete CalDAV event and keeps calendar selection and recurrence consequences visible before submission.

Choose **New event**, complete the title and schedule, then add times or select **All day**. Calendar and clock buttons open date and time pickers; typing remains available. Timed editor fields use the device's time zone, and an event keeps its own time zone when it has one. Use **Repeats** for recurrence, add location and description, and pick the correct writable calendar. Save once and wait for the refreshed view rather than submitting a duplicate after an uncertain network result.

Edits and deletes use the current ETag. If the server rejects a stale version, refresh and compare before trying again. For a repeating event, choose **Only this occurrence** to store an exception for that date, or **Every occurrence** to change the series. Deleting offers **Delete this occurrence** or **Delete every occurrence**.

Choose **Edit event** in the event details to edit within the workspace. The shared form stays at a readable width while the desktop shell remains available. Back returns to the calendar and asks before discarding a changed draft. Creating an event and choosing dates or times still use dialogs.

The phone and desktop editor share validation, recurrence and calendar selectors, and date/time fields. Save and Cancel remain outside the scrolling form. When you edit a repeating event, the editor states whether changes apply to this occurrence only or to every occurrence.

## 4. Guests, reminders, sharing, and the trash bin

**Reminders** in the editor add on-screen reminders before the start, such as 15 minutes or 1 day. Reminders set another way, for example at a fixed time, are kept as they are and listed as kept.

**Guests** invites people by email. Only the organizer can change the guest list, and the first guest makes you the organizer. When the guest list changes, Save reads **Save and notify guests**: Nextcloud sends the invitations, updates, and cancellations by email. If you were invited by someone else, the event details show **Going**, **Maybe**, and **Not going**; your answer is sent to the organizer. For a repeating event without its own exception, the answer applies to every occurrence.

Open a calendar's menu in the calendar list and choose **Share calendar** to share it with people or groups, read-only or with **Can edit**, or to create a public read-only link. Stopping a share or deleting the link asks first; a deleted link stops working for everyone.

**Trash bin**, below the calendar list, lists deleted calendars, events, and tasks for as long as the server keeps them. **Restore** puts an item back; **Delete** and **Empty trash bin** remove items for good after a confirmation. **Show contact birthdays** appears while the birthdays calendar is turned off.

Importing or exporting `.ics` files, free/busy lookup, and the web calendar's own settings are not available in the app.

