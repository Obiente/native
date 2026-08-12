---
title: Sync a Linux folder with Nextcloud Native
slug: folder-sync
description: Map a Linux directory to Nextcloud, choose safe sync and deletion rules, understand two-minute background checks, and recover conflicts without data loss.
category: Files and sync
platform: Linux
device: Desktop
platforms: Linux
durationMinutes: 10
difficulty: Advanced
lastUpdated: 2026-08-12
captureScenarios: guide-linux-folder-sync-workspace, guide-linux-folder-sync-locations, guide-linux-folder-sync-rules
prerequisites: A connected Linux account, A local directory you can safely test, Enough local and Nextcloud storage for the first scan
---

# Sync a Linux folder with Nextcloud Native

Linux folder sync keeps a normal local directory connected to one Nextcloud directory while the desktop process is running. It is intended for files used by ordinary desktop applications. It is separate from the Linux virtual filesystem mount, which represents remote placeholders and hydrates content on demand.

## 1. Inspect the folder-sync workspace and current health

@capture-alt: Nextcloud Native Linux folder-sync workspace with multiple pairs, directions, queued operations, recent runs, health, and a selected pair inspector
@capture-caption: The Linux desktop workspace uses a dense pair list and inspector to expose mapping, schedule, rules, conflicts, and failures together.

Open **Folder sync** from the sidebar. Existing pairs show their local and Nextcloud roots, direction, queued work, and last run. Select a pair to inspect its configuration and attention states. The desktop service checks configured pairs about every two minutes while the process is active, and **Sync now** remains available for an immediate requested run.

Closing the main window keeps sync active in the tray by default. Use **Show sync activity** to inspect work, **Open Nextcloud Native** to restore the window, or **Quit** to stop the app and release its services cleanly. You can disable **Keep running when the window closes** in Settings if closing the window should quit instead.

Start-on-login is separate and is disabled until you enable it. Without it, background checks do not begin until you launch Nextcloud Native after signing in to the computer. A stale last-run time, paused state, offline network, or visible failure needs attention even when the local files still look normal.

## 2. Map a local directory to the intended Nextcloud folder

@capture-alt: Nextcloud Native Linux Add sync dialog showing the local directory, remote Nextcloud directory, direction options, and mapping review
@capture-caption: Linux setup keeps both roots and the intended direction visible before the pair can scan or transfer any files.

Choose **Add sync**, select the local directory, then choose the remote root in the native Nextcloud browser. Avoid a directory managed by another sync program, a build cache, or a mounted remote filesystem. Two independent engines acting on the same tree can interpret temporary files, renames, or deletions differently.

Choose **Two-way**, **Device to Nextcloud**, or **Nextcloud to device** according to the actual ownership of the data. Review the complete paths, not only their final folder names. Creating a pair does not itself delete either root, and removing the configuration later also leaves the current local and server files in place.

## 3. Configure safe rules and resolve the first scan

@capture-alt: Nextcloud Native Linux folder-sync rules screen with scope presets, ignore patterns, priority rules, conflict policy, deletion policy, and a file-count preview
@capture-caption: Linux pair rules show the expected scope and destructive policy before the first synchronization is allowed to proceed.

Start with **Ask before changing either copy** or **Keep both copies** for conflicts. Treat deletion policy separately: propagating a deletion can remove the counterpart even when there is no content conflict. Add ignores for generated output, lock files, or temporary application data, and review the estimated file count and size before continuing.

Watch the first scan and queued operations. If a conflict or ambiguous interrupted operation appears, compare both versions and preserve both when uncertain. A successful scan is evidence for that generation only. Keep independent backups of important data while the product remains an alpha, and do not treat bidirectional synchronization as backup history.
