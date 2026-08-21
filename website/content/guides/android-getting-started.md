---
title: Get started with Nextcloud Native on Android
slug: getting-started
description: Install the Android alpha, connect your Nextcloud account with Login Flow, find native workspaces, and check permissions and offline access.
category: Start here
platform: Android
device: Mobile
platforms: Android
durationMinutes: 8
difficulty: Getting started
lastUpdated: 2026-08-21
captureScenarios: guide-android-getting-started-home, guide-android-getting-started-files, guide-android-getting-started-calendar
prerequisites: Android 8.0 or newer, Your Nextcloud server address and sign-in details, A current signed APK from the GitHub Releases page
---

# Get started with Nextcloud Native on Android

**Last reviewed: 2026-08-21.** The software and published packages may have
changed since this review. Check the [current releases](https://github.com/Obiente/nc-native/releases)
and [compatibility notes](/compatibility/) before using this guide with important data.

This guide covers the Android application that is available today. Nextcloud Native is alpha software, so keep another copy of important data and read the known limitations for the exact release before relying on sync or backup. iPhone and iPad builds are not available yet.

## 1. Connect your account and confirm the Android app is online

@capture-alt: Nextcloud Native Android Home screen showing the connected account status, quick actions, recent files, upcoming events, photo backup, and conversations
@capture-caption: The Android Home screen confirms that Login Flow completed and summarizes real work from the connected Nextcloud account.

Install the APK attached to the current GitHub prerelease and verify the published
checksum or GitHub attestation. Android 8.0 or newer is required. Open the app,
enter the full `https://` address of your Nextcloud server, and continue to the
server's trusted Login Flow page. Sign in there and approve the app-password
request; do not paste your primary password into Nextcloud Native itself.

After the browser returns to the app, Home should show your account and an online or offline status. An offline status can be temporary, but it means new server data cannot be confirmed. If connection fails, check the complete server address, open the server in a browser to confirm its certificate is trusted, and retry. Do not work around certificate warnings.

## 2. Open Files and decide what must be stored on the phone

@capture-alt: Nextcloud Native Android Files screen with folders, documents, previews, favorites, and explicit offline availability state for cloud content
@capture-caption: Files keeps cloud visibility separate from offline availability, so seeing a file does not imply that its original bytes are already on the device.

Open **Files** from Home or the app navigation. Browse into a folder and use an item's menu for actions such as previewing, sharing, or making a file available offline. A thumbnail or previously viewed preview can be cached even when the original is still online-only. Wait for the available-offline state before testing without a connection.

Nextcloud Native also appears in Android's system Files app while the account is connected. Opening a cloud file there downloads and caches a complete generation. Local edits are retained for guarded writeback; if the server changed first, the app records a conflict instead of silently replacing the newer remote file.

## 3. Check device permissions and open a native workspace

@capture-alt: Nextcloud Native Android Calendar screen with a compact month view, touch-sized navigation, an agenda, source labels, and an add-event action
@capture-caption: Android workspaces use phone-specific navigation and controls; Calendar is one example of a native surface rather than an embedded server web page.

On a phone, open **Settings** to see the settings overview, then open **Notifications & device**. Android system Back returns from a section to the overview before leaving Settings. On a tablet or an unfolded large screen, the section list stays beside the selected settings. Grant only permissions needed for the workflow you choose. Notifications, files and media, or media-library access can remain unavailable until a feature requests them. If Android reports a permission as blocked, use the Settings action to review it in the operating system.

Return to the app list and open a workspace such as Calendar, Photos, Talk, or Notes. Support varies by installed server app and version: some surfaces are complete, while adaptive or read-heavy views may expose fewer actions. The app should explain unsupported behavior rather than opening the server's web interface. Use Android system Back to leave nested content and return to the previous workspace state.

If you need to report a failure, open **Settings**, then **Support**. **Requests** shows the support requests available to the signed-in account. **New report** lets you describe what happened and prepare a bounded diagnostic report, while **Privacy** explains what the report can contain. Draft text stays only in memory while Settings is open. Preparing or previewing a report does not submit it. Review the report, then choose **Send** only when you intend to submit it to support.
