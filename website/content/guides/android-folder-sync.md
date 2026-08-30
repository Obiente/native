---
title: Sync an Android device folder with Nextcloud
slug: folder-sync
description: Create an Android folder pair with the system picker, choose upload, download, or two-way direction, and configure durable background sync safely.
category: Files and sync
platform: Android
device: Mobile
platforms: Android
durationMinutes: 10
difficulty: Advanced
lastUpdated: 2026-08-30
captureScenarios: guide-android-folder-sync-locations, guide-android-folder-sync-rules, guide-android-folder-sync-status
prerequisites: A connected Android account, A device folder you can safely test, Enough local and server storage for the first scan
---

# Sync an Android device folder with Nextcloud

**Last reviewed: 2026-08-30.** The software and published packages may have
changed since this review. Check the [current releases](https://github.com/Obiente/nc-native/releases)
and [compatibility notes](/compatibility/) before using this guide with important data.

Android folder sync connects a folder chosen through the system document-tree picker to one Nextcloud folder. It is different from opening Nextcloud through Android's Files provider and different from pinning a cloud file offline. Start with disposable or independently backed-up data until you have reviewed direction, deletion, and conflict behavior.

## 1. Choose the Android and Nextcloud folders

@capture-alt: Nextcloud Native Android Add sync flow showing the selected device folder, the Nextcloud destination, direction, and a reviewable mapping
@capture-caption: The Android setup flow receives access only to the folder chosen in the system picker and keeps both sides visible before creating the pair.

Open **Folder sync** and choose **Add sync**. Select the local root with Android's system folder picker. Nextcloud Native requests a durable read and write grant only for that chosen tree; broad all-files access is not required for an ordinary pair. Then choose the remote root with the native Nextcloud folder browser.

Review both locations carefully. Do not select a folder already managed by another sync client, an app cache, or an operating-system directory. Setup detects an existing identical mapping, but it cannot prevent a different application from writing the same tree. The pair is not created until you finish the review step.

## 2. Set direction, deletion, conflicts, and background constraints

@capture-alt: Nextcloud Native Android folder-sync review showing both roots, two-way direction, selected-content summary, and the expanded conflict policy at the start of the scrollable safety settings
@capture-caption: Android exposes sync policy before work starts; scroll through the expanded safety settings to review deletion, network, and power choices as well as conflicts.

Choose **Two-way** only when local and remote edits should both propagate. **Device to Nextcloud** never writes remote changes back to the selected Android folder; **Nextcloud to device** never uploads local edits. Choose **Ask before changing either copy** or **Keep both copies** while learning the workflow. Review the deletion policy separately because a propagated deletion is not the same as a content conflict.

Select any ignored patterns or a bounded subset, then choose network and power constraints. Android schedules durable periodic checks through WorkManager, approximately every 15 minutes, but the operating system decides the exact run time. Unmetered-network, battery-not-low, and charging constraints can intentionally delay a run.

## 3. Review the first scan and resolve attention states

@capture-alt: Nextcloud Native Android folder-sync center showing pair direction, queued work, last check, background schedule, conflicts, failures, and recovery actions
@capture-caption: The Android sync center distinguishes queued, active, failed, conflicted, paused, and no-pending-work states instead of reducing them to one progress bar.

Create the pair, then run or wait for the first scan while connected. Review the queued operation count before treating the mapping as safe. No pending work describes the observed queue. Last checked is the scan timestamp, not proof that every file completed synchronization or that the next Android schedule will run at an exact minute.

If both sides changed, open the pair and compare the device and Nextcloud type, size, and modification details before choosing an action. Select a choice to read its consequences, then choose **Review this choice** and confirm. **Keep both copies** is the conservative recovery. When every conflict on the displayed page allows the same choice, open **Apply a choice** to submit that reviewed page as one preflight-validated batch. The app checks every selected generation before any operation starts; if one item changed, none starts and the page returns to review. After that preflight, operations run separately, so a later transfer failure does not roll back an earlier successful transfer. Additional conflicts are loaded as earlier pages are resolved.

If a result is unknown after interruption, let the durable coordinator reconcile it rather than creating a second pair or repeatedly retrying. Use **Settings** in the pair detail to remove a sync. Removing the pair removes its configuration and schedule; the current implementation does not delete local or server files when the pair itself is removed.

The **Review** setup step keeps direction, conflict policy and deletion policy visible before you start. In **Choose what syncs**, a partial checkbox means only some descendants are selected; opening that folder does not expand the sync scope. Removal is under the selected pair's **Settings** tab and still requires confirmation.
