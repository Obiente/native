---
title: Get started with nati.ve on Linux or Windows
slug: getting-started
description: Choose the correct Linux or Windows alpha package, connect with Login Flow, learn desktop navigation, and review OS-specific integration limits.
category: Start here
platform: Desktop
device: Desktop
platforms: Linux, Windows
durationMinutes: 9
difficulty: Getting started
lastUpdated: 2026-08-30
captureScenarios: guide-desktop-getting-started-home, guide-desktop-getting-started-apps, guide-desktop-getting-started-settings
prerequisites: A supported x86-64 Linux or Windows computer, Your Nextcloud server address and sign-in details, The package and release notes for the current alpha
---

# Get started with nati.ve on Linux or Windows

**Last reviewed: 2026-08-30.** The software and published packages may have
changed since this review. Check the [current releases](https://github.com/obiente/native/releases)
and [compatibility notes](/compatibility/) before using this guide with important data.

Navigation instructions and screenshots reflect the reviewed source implementation;
published packages may differ.

The supported authenticated desktop targets in the current alpha are Linux and Windows. macOS DMG artifacts only prove early packaging and do not yet provide supported Keychain-backed login, so this guide must not be used to treat macOS as ready. Keep another copy of important files while testing any prerelease.

## 1. Install the package for your operating system and connect

@capture-alt: nati.ve desktop Home workspace with connected account status, quick actions, recent files, upcoming events, storage, and persistent navigation
@capture-caption: Home is the first post-login check on Linux and Windows and keeps account status visible beside useful cross-app summaries.

Download the exact package attached to a GitHub prerelease: DEB or RPM for the matching Linux distribution, or the x86-64 MSI for Windows. Read that release's known limitations. Windows MSIs are currently not Authenticode-signed, so verify the GitHub attestation and SHA-256 checksum when provided; never disable SmartScreen or Defender. Organization-managed Windows devices may refuse the installer by policy.

Open nati.ve, enter the complete `https://` server address, and finish sign-in on the server's Login Flow page. The app stores the generated app password in Linux Secret Service or Windows Credential Manager. Home should then show the account status. A macOS package must not be used for an account until supported Keychain login is implemented.

## 2. Use desktop navigation and installed app workspaces

@capture-alt: nati.ve desktop Apps workspace with pinned tools, recent work, categories, search, installed app cards, and the persistent sidebar
@capture-caption: The desktop app catalog exposes installed Nextcloud apps and support boundaries while the sidebar keeps common and recent workspaces close.

The left sidebar contains Home, Folder sync, Activity, Apps, pinned workspaces, and the most recent unpinned app. Settings and account access sit at the bottom. Collapse the sidebar to give the workspace more room; the compact rail keeps labeled app shortcuts. Open **Apps** to search the server's installed app list. Native support is capability-driven: a familiar app name does not guarantee every web action exists, and adaptive surfaces stay read-only when a verified write contract or target identity is missing.

Desktop layouts may use multiple panes, selection, context menus, and denser content. Open an item with its primary action; use the overflow or pointer context menu for secondary actions. nati.ve remembers supported navigation state for each app, so switching from a folder, board, or calendar and back should return to that app rather than reset the whole workspace.

## 3. Review settings and choose the correct file integration

@capture-alt: nati.ve desktop Settings workspace with account, appearance, sync and storage, desktop app, updates, support, help, and administration sections
@capture-caption: Desktop Settings separates account and app preferences from Linux and Windows integrations whose availability depends on the current operating system.

Open **Settings** to choose the theme, review the connected server, configure start-on-login, inspect update options, and enter **Sync & storage**. At normal desktop widths, the section list remains visible beside the selected section. In a compact window, Settings uses an overview and one section at a time; Back returns to the overview. Linux supports normal folder pairs and a filesystem mount. Windows provides Cloud Files placeholders in File Explorer. These integrations share safety rules but are not interchangeable, so follow the Linux folder-sync or Windows Cloud Files guide for exact behavior.

**Keep running in background** is enabled by default. Closing the window therefore keeps sync and virtual files active in the tray; use **Open nati.ve** to restore the window, **Show sync activity** to inspect work, or **Quit** to stop the app cleanly. Start-on-login is a separate setting and is disabled until you enable it. Background folder-pair checks run while the desktop process is active.

When the **Folder sync** workspace is available, the client checks the local selection before hashing local file content and stops if it contains more than 100,000 selected entries. A larger remote tree or combined change plan can reach the limit later. Automatic checks wait longer after repeated item failures and leave an item failed after five attempts. Use the pair action or the tray's **Sync now** action only after reviewing the failure. These rules apply to ordinary folder pairs, not to Windows Cloud Files placeholders in File Explorer.

Before relying on any pair, review its direction, deletion policy, conflicts, and latest successful run. If you need to report a failure, open **Settings**, then **Support**. **Requests** shows the support requests available to the signed-in account and points to safe retry or discard actions when an earlier send still needs recovery. If reply delivery is uncertain, refresh Requests before retrying; when delivery is confirmed, clear the retained draft before writing another reply. **New report** lets you add reproduction steps and prepare a bounded diagnostic report, while **Privacy** explains what it can contain. The report includes a one-time heap, non-heap, direct-buffer, mapped-buffer, uptime, thread, garbage-collection, and event-history snapshot where the JVM exposes those counters. It excludes server URLs, account names, file names, paths, credentials, response bodies, and file contents. Draft text stays in memory until the app closes and is not saved to disk. Preparing, previewing, or exporting a report does not submit it. Review the report and choose **Send** only when you intend to submit it to support.
