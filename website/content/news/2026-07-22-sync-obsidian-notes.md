---
title: Syncing an Obsidian folder with Nextcloud
slug: sync-obsidian-notes
date: 2026-07-22
description: The goal is reliable two-way folder sync for Markdown notes, with clear conflicts and no need to keep files inside a hidden app folder.
tags: Obsidian Nextcloud sync, Markdown notes, Android folder sync, offline files
---

# Syncing an Obsidian folder with Nextcloud

Many people want the same Obsidian notes on their phone and computer without paying
for another cloud. A proper Nextcloud files client should make that possible while
keeping the notes available to Obsidian as normal Android files.

## The experience we are building

You choose an existing notes folder on your phone, choose its destination in
Nextcloud, and select two-way sync. Nextcloud Native shows what is waiting, what
finished, and what needs your attention. You do not type internal folder IDs or move
your notes into a private app directory.

## Conflicts should be understandable

If the same note changes on both devices, the app should preserve both versions and
ask what to keep. It should never silently overwrite the only copy. Failed transfers
belong in a scalable transfer view with retry and clear error information.

## How it works under the hood

Each side is scanned with revision information. Transfers use those expected
revisions so a file that changes mid-sync is not overwritten by stale work. The
coordinator keeps a durable record of pending work and decisions instead of treating
every refresh as a brand-new sync.

## Current status

Folder pairing, guarded transfer planning, and conflict foundations are under active
development. Background scheduling, a native destination picker, multiple accounts,
and large real-world folder testing are still required before this can replace a
dedicated sync tool.
