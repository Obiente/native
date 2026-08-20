---
title: Move between Nextcloud apps without losing your place
slug: switch-apps
description: Use the Linux and Windows app catalog, pinned and recent desktop shortcuts, and per-app navigation memory while respecting native support boundaries.
category: Workspace
platform: Desktop
device: Desktop
platforms: Linux, Windows
durationMinutes: 5
difficulty: Getting started
lastUpdated: 2026-08-20
captureScenarios: guide-desktop-switch-apps-catalog, guide-desktop-switch-apps-sidebar, guide-desktop-switch-apps-nested
prerequisites: A connected Linux or Windows account with more than one installed Nextcloud app
---

# Move between Nextcloud apps without losing your place

**Last reviewed: 2026-08-20.** The software and published packages may have
changed since this review. Check the [current releases](https://github.com/Obiente/nc-native/releases)
and [compatibility notes](/compatibility/) before using this guide with important data.

Nextcloud Native is designed as one workspace for the apps it can currently discover and render from a connected account. On Linux and Windows, the desktop sidebar stays useful inside apps, and each app remembers supported route and saveable interface state so comparing work across tools does not require starting over. macOS authenticated use is not supported yet.

## 1. Discover the complete app catalog

@capture-alt: Apps workspace with pinned apps, recent work, app categories, installed app cards, search, and a persistent account sidebar
@capture-caption: The catalog provides the complete server inventory while pinned and recent areas surface the tools that matter now.

Open **Apps** to browse everything installed on the connected server. Search by app name or scan categories such as communication, planning, media, and productivity. The catalog shows native support honestly; an app with limited verified capabilities does not pretend unsupported actions are available.

Open a tool to add it to recent work. Everyday apps such as Files, Photos, Talk, and Calendar stay pinned in the desktop sidebar, while the last unpinned app appears under Recent. This keeps the catalog complete without making it the only way to move between tools.

## 2. Switch directly from an active workspace

@capture-alt: Photos folder workspace nested inside Nextcloud Native with Photos selected and Files, Talk, Calendar, and recent Deck shortcuts still visible
@capture-caption: The global sidebar remains available inside Photos, so another app can open directly without discarding the current folder state.

Open a folder, conversation, board, or calendar view, then choose another pinned or recent app in the sidebar. The selected app changes in place while the account, sync status, and global destinations remain visible. The current implementation does not define a complete global keyboard-shortcut set, so use the sidebar or normal focus navigation instead of relying on undocumented key combinations.

Return to the first app. The app restores the last bounded route it knows how to serialize, including supported nested resource identity. Screen-local details such as every scroll offset or transient input are not a blanket guarantee. Navigation memory is keyed with the active account so a saved route from one account is not reused as another account's resource context.

## 3. Work inside a discovered native app

@capture-alt: Native Tables workspace with inventory insights, typed rows, reusable app navigation, and a responsive overview
@capture-caption: A verified dynamic app opens on a useful collection rather than a web page or raw API response, while retaining normal native navigation.

Open an installed app that uses a verified dynamic contract. Nextcloud Native chooses a useful entry point such as a mailbox, table, board, recipe library, or record collection. Parent and child context stay connected, so actions receive the correct resource identity instead of asking you to type internal IDs.

Use the app's own navigation for destinations inside that tool and the global sidebar for switching tools. If the server does not verify a required action or permission, the interface remains read-only and explains why. Returning later restores the last valid nested route without inventing unsupported behavior.
