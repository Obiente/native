---
title: Building a media backup users can trust
slug: media-sync-foundations
date: 2026-07-24
description: Why Nextcloud Native separates verified backup receipts, transfer state, and storage reclaim decisions.
tags: media backup, Android, sync, local first
---

# Building a media backup users can trust

Uploading a photo is not the same as proving that it is safe to remove from a phone.
Nextcloud Native is building those concerns as separate layers.

The transfer engine records which exact local revision was uploaded and which remote
revision was verified. The interface can then distinguish pending, uploading, backed
up, changed, failed, and cloud-only media without guessing from a filename.

This foundation also keeps storage decisions explicit. A future “free up space”
action can only be offered when the current local bytes still match a verified remote
receipt. Android remains responsible for presenting the final deletion confirmation.

The work is still in developer-preview territory. The public roadmap tracks the
remaining transfer-center, folder-preview, multi-account, and background execution
work before media backup can be considered release-ready.

