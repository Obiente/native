---
title: One native app for Files, Talk, Photos, and more
slug: adaptive-native-apps
date: 2026-07-20
description: Nextcloud Native aims to make installed Nextcloud apps feel consistent on your phone and desktop, without opening their web pages.
tags: Nextcloud apps, native Nextcloud client, Files, Talk, Photos
---

# One native app for Files, Talk, Photos, and more

Your Files, Talk chats, Photos, calendar, recipes, budgets, and project boards should
not feel like unrelated websites squeezed onto a phone. Nextcloud Native aims to give
them one consistent home with familiar navigation, search, previews, menus, and
editing.

## What this means in everyday use

- A table opens as a table you can read and edit, not a list of technical fields.
- Deck cards appear on a board.
- Calendar entries appear on a calendar.
- A shared Talk file opens with the same preview and actions as it does in Files.
- New or less common apps can still receive a useful interface.

## Why not build a separate client for every app?

There are too many Nextcloud apps and server versions for a small project to hand-code
every screen. Shared native building blocks let similar information behave similarly,
while focused app improvements remain possible where they genuinely help.

## How it works under the hood

The app first reads the capabilities that your server safely publishes. It then
identifies familiar data such as dates, people, files, rows, columns, and actions, and
chooses a matching native component. It may improve presentation, but it may never
invent an address, permission, or destructive action.

The architecture documentation contains the technical schema and safety boundaries.
The compatibility page records which apps and versions have been tested.

