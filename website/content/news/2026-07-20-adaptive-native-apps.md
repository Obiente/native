---
title: One native app for Files, Talk, Photos, and more
slug: adaptive-native-apps
date: 2026-07-20
lastUpdated: 2026-08-20
description: How verified contracts and reusable views let current Android, Linux, and Windows builds render Nextcloud apps without wrapping their web pages.
tags: Nextcloud apps, native Nextcloud client, Android, Linux, Windows, Files, Talk, Photos
captureScenario: tables-insights-desktop
imageAlt: Nextcloud Native showing an inventory Insights dashboard with total quantity, low-stock metrics, and category bars
imageCaption: The production adaptive Compose renderer turns verified inventory data into responsive quantity metrics and category insights.
---

# One native app for Files, Talk, Photos, and more

**Historical article, reviewed 2026-08-20.** This post records product direction
at publication. It is not a platform support list, and parts of the design remain
planned. Check the [current releases](https://github.com/Obiente/nc-native/releases)
and [compatibility notes](/compatibility/) for the current state.

A Nextcloud can hold much more than files. It can be your photo library, group chat,
calendar, address book, recipe collection, music library, project board, shared
budget, and note archive. On the web those tools live together. On a phone, people
often have to combine several unrelated apps, accept missing features, or return to a
small web page that was never designed as a mobile workspace.

Nextcloud Native is being built to make the server feel like one product without
placing every web page inside an app. Current Android, Linux, and Windows builds
interpret verified information and actions from installed apps, then render native
components. Coverage and write support vary by app and server version.

## A cloud made of more than files

Switching between apps creates more friction than another icon on the home screen
might suggest. A file shared in Talk may preview differently from the same file in
Files. Search may stop at the edge of one app. Back behavior, menus, caching, dark
mode, and accessibility can all change from screen to screen.

It is also difficult for smaller Nextcloud apps to justify a complete Android, iOS,
and desktop client of their own. People then have to choose between a capable web
interface and a native app with only a fraction of the available actions.

A shared native workspace can solve both problems. Files, people, dates, media,
tables, messages, and editable records are concepts that appear across many apps.
They behave consistently without erasing the parts that make each app useful.

## From API-shaped screens to reusable native views

The first experiments proved that Nextcloud Native could discover API data, but they
also exposed a bad failure mode: technically correct screens that felt like an API
viewer. A list of every returned field is useful for debugging. It is not a useful
mailbox, recipe, budget, calendar, or Kanban board.

The client starts with reusable semantic components instead:

- rows and columns can become an editable table;
- dated items can become a timeline or calendar;
- messages, senders, attachments, and reactions can become a conversation;
- images and videos can become a gallery with previews and media actions;
- records with totals and categories can become a compact dashboard;
- files keep the same preview, share, move, offline, and sync behavior wherever they
  appear.

Small app-aware hints are allowed when they materially improve the experience. The
important boundary is that an adapter must verify an endpoint and its permissions. It
may never invent an action because a field happened to have a promising name.

## Following a file across the workspace

Imagine a colleague shares a photograph and a document in Talk. You open the
conversation from the native Conversations area, read the message, and open the
document. The file preview is the same component used by Files, so download state,
sharing, offline availability, and the overflow menu are in familiar places.

You return to Home, open Photos & Memories, and find the photograph in its album. A
global search can later find the conversation, the file, or another app record without
requiring you to remember which Nextcloud app created it.

On desktop, the persistent navigation leaves room for a list and detail pane. On
mobile, the same destinations become touch-sized rows and bottom navigation. The
shared rules stay the same, but the desktop is not merely a stretched phone screen.

## One workspace, specialized native experiences

The product plan puts Files, Photos and Memories, Talk, Activity, Notes, and
dynamically discovered app data in one workspace. Current builds implement parts of
that list at different levels. Mail uses mailbox views, Calendar uses CalDAV views,
and the shared renderer has table, board, recipe, finance, and music presentations.
The presence of a presentation does not guarantee every server action is supported.
Authenticated iPhone, iPad, and macOS clients are not currently supported.

These experiences share context-aware forms that already know the current item and
parent, consistent overflow menus, global Nextcloud search, and account-scoped cached
startup. The same file preview and share sheet work from Files, a Talk attachment,
Mail, or another app that references a file.

## Reusable components make unfamiliar apps useful

The component system is designed for open-source Nextcloud apps and multiple server
versions. When a new verified data shape appears, one improved component can benefit
other apps with the same shape. Specialized adapters add app-specific knowledge
without creating a separate client for every app.

## Verified contracts come before inference

Discovery begins with server capabilities, app metadata, documented APIs, and safe
reads. Those inputs become a typed, platform-neutral contract containing resources,
fields, relations, actions, identifiers, and permission evidence. The renderer then
chooses from a bounded set of native layouts.

Semantic inference can improve labels, grouping, and presentation, but execution
remains deterministic. An edit or destructive action is only enabled when the
contract provides a verified method, path, parameters, and permission. Learned
presentation knowledge remains local to the device. The architecture and native
schema documents describe those boundaries in detail.
