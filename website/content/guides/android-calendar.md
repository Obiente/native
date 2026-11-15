---
title: Use Nextcloud Calendar on Android
slug: calendar
description: Browse month, week, and agenda views on Android, create CalDAV events, edit writable series safely, and understand read-only calendars and conflicts.
category: Calendar and planning
platform: Android
device: Mobile
platforms: Android
durationMinutes: 8
difficulty: Everyday
lastUpdated: 2026-10-31
captureScenarios: guide-android-calendar-month, guide-android-calendar-agenda, guide-android-calendar-edit
prerequisites: Calendar installed on the connected Nextcloud server, At least one CalDAV calendar, Write permission for creating or changing events
---

# Use Nextcloud Calendar on Android

**Last reviewed: 2026-10-31.** The software and published packages may have
changed since this review. Check the [current releases](https://github.com/obiente/native/releases)
and [compatibility notes](/compatibility/) before using this guide with important data.

Navigation instructions and synthetic screenshots reflect the reviewed source implementation; published APKs may differ.

The Android Calendar workspace reads and writes events through CalDAV. It offers touch-first month, week, and agenda views, preserves useful cached content during refresh, and enables mutation controls only when the selected calendar and event provide the required write evidence.

## 1. Browse a month and switch to the agenda

@capture-alt: nati.ve Android Calendar month view with previous and next navigation, Today, visible events, selected date, source labels, refresh, and add action
@capture-caption: The compact Android month view keeps date navigation and event context touch-friendly without stretching the desktop layout onto a phone.

Open **Calendar** from Apps or a pinned shortcut. Use the previous and next controls to change month and **Today** to return to the current date. The selected date is filled; today's date has an outline when it is not selected. Select a day to see time-first event rows below the grid. **Week** shows seven selectable days and the selected day's schedule. **Agenda** lists the loaded events chronologically. Multi-day events appear on every covered date, with an all-day event's exclusive end date left out.

Use search to narrow loaded events. The calendar filter opens **My calendars**, where named checkboxes control which calendars appear. Hiding a calendar changes only this view; it does not delete events. Calendar names remain available in event rows so meaning does not depend on color alone.

Refresh keeps the last matching in-memory result visible while CalDAV reloads. If refresh fails, the previous content can remain on screen with a retry message. Treat it as potentially stale until retry succeeds. Android restores the selected month, date, and view through ordinary activity recreation, but you should still confirm server state before an important edit.

## 2. Create an event in a writable calendar

@capture-alt: nati.ve Android Calendar agenda with touch-sized event rows, times, locations, multiple calendar sources, and the create-event control
@capture-caption: Agenda prioritizes readable event order on a phone while the add action remains disabled when no writable calendar is available.

Choose the add action in the Calendar header. Enter a title, then complete **Schedule**. Choose the date using the calendar button or type YYYY-MM-DD. For a timed event, choose start and end times with the clock buttons or type them; editor times are shown in the device's time zone, and an event keeps its own time zone when it has one. For an all-day event, enable **All day**. Open **Repeats** to choose no recurrence, daily, weekly, monthly, or a validated custom rule. Add a location or description when useful, then select the intended calendar if more than one writable source exists.

Save once and wait for the CalDAV result. A successful response triggers a fresh read. If the connection result is unknown, do not create a duplicate immediately. First refresh the target date and calendar to see whether the event exists.

## 3. Edit or delete an event, an occurrence, or a series

@capture-alt: nati.ve Android event detail showing date, time, recurrence, edit and delete actions, and the recurring event context
@capture-caption: Android exposes edit and delete only for writable events with an ETag.

Open an event. Editing is available only when its calendar is writable and its current ETag is known. For a repeating event, **Edit** asks whether to change **Only this occurrence** or **Every occurrence**. Changing one occurrence stores an exception for that date and leaves the rest of the series as it was. **Delete** on a repeating event offers **Delete this occurrence**, which removes only that date, or **Delete every occurrence**.

Updates and deletes send the current ETag as a precondition. If another client changed the event first, refresh and review that version before applying your intent again. Read-only subscribed calendars remain browsable but do not expose mutation actions.

Open an event to view its details on a page. **Edit** changes that page into the shared editor, without opening another modal. Back returns to the event details and asks before discarding a changed draft. Creating an event and choosing dates or times still use dialogs.

The phone and desktop editor share validation, recurrence and calendar selectors, and date/time fields. Save and Cancel remain outside the scrolling form. When you edit a repeating event, the editor states whether changes apply to this occurrence only or to every occurrence.

### Guests, reminders, sharing, and the trash bin

**Reminders** in the editor add on-screen reminders before the start, such as 15 minutes or 1 day. Reminders set another way, for example at a fixed time, are kept as they are and listed as kept.

**Guests** invites people by email. Only the organizer can change the guest list, and the first guest makes you the organizer. When the guest list changes, Save reads **Save and notify guests**: Nextcloud sends the invitations, updates, and cancellations by email. If you were invited by someone else, the event details show **Going**, **Maybe**, and **Not going**; your answer is sent to the organizer. For a repeating event without its own exception, the answer applies to every occurrence.

Open a calendar's menu in the calendar list and choose **Share calendar** to share it with people or groups, read-only or with **Can edit**, or to create a public read-only link. Stopping a share or deleting the link asks first; a deleted link stops working for everyone.

**Trash bin**, below the calendar list, lists deleted calendars, events, and tasks for as long as the server keeps them. **Restore** puts an item back; **Delete** and **Empty trash bin** remove items for good after a confirmation. **Show contact birthdays** appears while the birthdays calendar is turned off.

Importing or exporting `.ics` files, free/busy lookup, and the web calendar's own settings are not available in the app.

