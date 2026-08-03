---
title: Keep important files available offline
slug: offline-files
description: Understand cloud-only, cached, pinned, and synchronized files, then control device storage without risking drafts or active transfers.
category: Files and sync
platforms: Android, Desktop
durationMinutes: 7
difficulty: Everyday
lastUpdated: 2026-08-03
captureScenarios: guide-offline-files-browse, guide-offline-files-storage, guide-offline-files-transfers
prerequisites: A connected Nextcloud account, Files already stored in Nextcloud
---

# Keep important files available offline

Nextcloud Native distinguishes files you can see from files that are actually stored on the device. Pinning the right projects gives you reliable offline access without turning the complete server into an unbounded local copy.

## 1. Choose what needs to travel with you

@capture-alt: Native Files workspace showing folders, previews, sync state, storage state, selection controls, and the persistent desktop sidebar
@capture-caption: Files exposes availability and transfer state beside normal file actions so cloud visibility is not confused with offline storage.

Open **Files** and browse to the project or folder you need. Use the item menu and choose **Keep offline**. Pin a folder when its contents should remain available together; pin individual files when storage is limited and only a few documents matter. Wait for the offline indicator to finish before disconnecting from the network.

Viewed, cached, pinned, uploaded, and synchronized are different states. A preview may be cached while the original remains cloud-only. A pinned item is protected from automatic cleanup, while an ordinary cached item can be reclaimed when the device needs space.

## 2. Review storage protection

@capture-alt: Virtual file storage overview showing cache use, reclaimable space, protected pins, free device capacity, and automatic cleanup rules
@capture-caption: Storage management separates reclaimable cache from protected offline files, edits, conflicts, and transfers.

Open **Settings**, then **Sync and offline** to see how much storage is cached, reclaimable, or protected. Automatic cleanup removes eligible cached copies, not files from Nextcloud. Pinned files, local drafts, conflict copies, active transfers, and files currently in use are stronger retention classes and remain protected.

Choose **Free up space** only after reviewing the amount marked reclaimable. If you need to remove an offline pin, use the file's menu and choose the matching availability action. Removing a pin allows future cleanup; it does not delete the server file.

## 3. Confirm transfers before going offline

@capture-alt: Desktop transfer center showing completed, active, failed, and retained file transfer history with status and recovery actions
@capture-caption: The transfer center keeps completed work and failures distinguishable so offline readiness can be checked before leaving a connection.

Open the transfer center from Settings or a transfer status action. Confirm that required downloads are complete and review any failed items. A failed transfer retains enough information for a safe retry; it does not mark a partial file as complete. Resolve quota, permission, or connection problems before retrying.

When you reconnect, Nextcloud Native refreshes pinned content and sends allowed local changes. If the same file changed on both sides, the app creates a visible conflict rather than silently replacing one version. Review the conflict when you are back online and can compare both copies.
