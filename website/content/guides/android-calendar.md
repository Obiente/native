---
title: Use Nextcloud Calendar on Android
slug: calendar
description: Browse month and agenda views on Android, create CalDAV events, edit writable series safely, and understand read-only calendars and conflicts.
category: Calendar and planning
platform: Android
device: Mobile
platforms: Android
durationMinutes: 8
difficulty: Everyday
lastUpdated: 2026-08-20
captureScenarios: guide-android-calendar-month, guide-android-calendar-agenda, guide-android-calendar-edit
prerequisites: Calendar installed on the connected Nextcloud server, At least one CalDAV calendar, Write permission for creating or changing events
---

# Use Nextcloud Calendar on Android

**Last reviewed: 2026-08-20.** The software and published packages may have
changed since this review. Check the [current releases](https://github.com/Obiente/nc-native/releases)
and [compatibility notes](/compatibility/) before using this guide with important data.

The Android Calendar workspace reads and writes events through CalDAV. It offers touch-first month and agenda views, preserves useful cached content during refresh, and enables mutation controls only when the selected calendar and event provide the required write evidence.

## 1. Browse a month and switch to the agenda

@capture-alt: Nextcloud Native Android Calendar month view with previous and next navigation, Today, visible events, selected date, source labels, refresh, and add action
@capture-caption: The compact Android month view keeps date navigation and event context touch-friendly without stretching the desktop layout onto a phone.

Open **Calendar** from Apps or a pinned shortcut. Use the previous and next controls to change month and **Today** to return to the current date. Select a day to see its events, or switch to **Agenda** for a chronological list. Event source and text remain available so calendar meaning does not depend on color alone.

Refresh keeps the last matching in-memory result visible while CalDAV reloads. If refresh fails, the previous content can remain on screen with a retry message. Treat it as potentially stale until retry succeeds. Android restores the selected month, date, and view through ordinary activity recreation, but you should still confirm server state before an important edit.

## 2. Create an event in a writable calendar

@capture-alt: Nextcloud Native Android Calendar agenda with touch-sized event rows, times, locations, multiple calendar sources, and the create-event control
@capture-caption: Agenda prioritizes readable event order on a phone while the add action remains disabled when no writable calendar is available.

Choose the add action. Enter a title and valid date. For a timed event, enter start and end times; for an all-day event, enable **All day**. Add a location or description when useful, then select the intended calendar if more than one writable source exists. The app supports no recurrence, daily, weekly, monthly, and validated custom recurrence rules.

Save once and wait for the CalDAV result. A successful response triggers a fresh read. If the connection result is unknown, do not create a duplicate immediately. First refresh the target date and calendar to see whether the event exists.

## 3. Edit or delete only an authoritative event or series

@capture-alt: Nextcloud Native Android event detail showing date, time, recurrence, Edit series, Delete series, and the authoritative recurring event context
@capture-caption: Android exposes edit and delete only for writable events with an ETag, and recurring occurrences direct changes to the authoritative series.

Open an event. Editing is available only when its calendar is writable, its current ETag is known, and the row is not a generated recurrence occurrence. A generated occurrence is read-only to protect the series. Open the authoritative series to use **Edit series** or **Delete series**; deleting a repeating event permanently removes the complete series.

Updates and deletes send the current ETag as a precondition. If another client changed the event first, refresh and review that version before applying your intent again. Read-only subscribed calendars remain browsable but do not expose mutation actions.
