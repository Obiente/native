<p align="center">
  <img src="design/app-icon/cloud.svg" width="112" height="112" alt="Nextcloud Native cloud icon">
</p>

<h1 align="center">Nextcloud Native</h1>

<p align="center">
  One coherent, native workspace for your whole Nextcloud.
</p>

<p align="center">
  <a href="https://nc-native.obiente.dev">Website</a> ·
  <a href="https://nc-native.obiente.dev/roadmap/">Roadmap</a> ·
  <a href="https://github.com/Obiente/nc-native/releases">Testing releases</a> ·
  <a href="https://github.com/orgs/Obiente/projects/4">GitHub Project</a>
</p>

[![Build and test](https://github.com/Obiente/nc-native/actions/workflows/ci.yml/badge.svg)](https://github.com/Obiente/nc-native/actions/workflows/ci.yml)
[![License: AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue.svg)](LICENSE)

Nextcloud Native is an independent Obiente project for building a fast,
consistent client across Nextcloud Files and installed Nextcloud apps. It
turns verified APIs and data relationships into real native interfaces instead
of embedding remote web pages or exposing raw API responses.

The goal is larger than putting many apps behind one icon. Nextcloud should
feel like one operating-system service:

- files available through native browsers, pickers, sharing, offline access,
  and dependable synchronization;
- photos, videos, RAW files, albums, people, backups, and edits presented as a
  complete media library;
- Talk messages, attachments, notifications, media, and eventually calls using
  platform integrations;
- calendars, contacts, tasks, mail, notes, music, recipes, boards, tables,
  budgets, and other installed apps sharing one interaction language;
- administration, settings, and app management exposed only when permissions
  and authentication make them safe;
- useful native support for previously unseen apps through typed contracts and
  reusable semantic components.

> **Alpha software:** Nextcloud Native is under active development. Current
> prereleases are for testing and contribution, not yet a replacement for every
> production workflow or the only copy of important data.

Nextcloud Native is unofficial and is not affiliated with, sponsored by, or
endorsed by Nextcloud GmbH.

## Why this project exists

The Nextcloud ecosystem has excellent server apps, but their mobile and desktop
experiences vary. Some have separate clients, some rely on the browser, some
expose only part of their server functionality, and each uses different
navigation and interaction patterns.

Nextcloud Native provides a shared native product layer without reducing every
app to the same generic screen. A table should behave like a table, a deck like
a board, a mailbox like mail, a recipe like something a person can cook from,
and an expense project like a financial workspace. The common layer supplies
identity, permissions, caching, actions, search, settings, navigation, and
platform behavior. Semantic components supply the workflow.

## How adaptive native apps work

```text
connected Nextcloud account
    |
    +-- capabilities and installed-app versions
    +-- official OCS, DAV, OpenAPI, and app contracts
    +-- exact signed App Store packages and verified source fallbacks
    +-- approved successful read observations
    |
    v
validated typed resources, relationships, permissions, and actions
    |
    v
reusable semantic models
    |
    +-- files, media, people, mail, calendar, board, table, recipe ...
    |
    v
platform-adapted native UI for Android and desktop
```

Discovery is deterministic before it is heuristic. The runtime can infer field
roles, relationships, component families, labels, and useful entry points, but
it may not invent an endpoint, payload, permission, resource ID, or retry
guarantee.

Small verified adapters are welcome when they provide meaningful behavior that
cannot be inferred safely. The important distinction is that an adapter
enhances the same typed runtime rather than creating an isolated second app.

See [DYNAMIC_APP_DESCRIPTOR.md](DYNAMIC_APP_DESCRIPTOR.md),
[NATIVE_SCHEMA.md](NATIVE_SCHEMA.md), and
[ADAPTER_ARCHITECTURE.md](ADAPTER_ARCHITECTURE.md) for the trust and execution
boundaries.

## What works in the current alpha

The repository already contains runnable Android and Linux desktop
applications with:

- Nextcloud Login Flow v2 with Android Keystore and Linux Secret Service
  credential storage;
- authenticated native Files browsing, list/grid layouts, previews, sharing
  foundations, text editing, and media viewing;
- Photos and Memories collections, albums, tags, people, favorites, RAW/JPEG
  grouping, zoom-gated original loading, and non-destructive edit foundations;
- native Talk history and typed message cards for text, files, recordings,
  calls, system events, and shared objects;
- Notes with folders, ETag-aware writes, and native Markdown edit/preview;
- Activity, global search, Dashboard, user status, and app navigation;
- native semantic flows for Mail, Music, Cookbook, Calendar, Contacts, Tasks,
  Tables, Deck, Cospend, Budget, Office metadata, and administration inventory
  at different levels of completeness;
- signed-contract acquisition from exact Nextcloud App Store releases plus a
  guarded exact-source fallback for apps that do not advertise a contract;
- shape-driven tables, boards, forms, settings, summaries, charts, collection
  browsers, and detail inspectors that can be reused by unfamiliar apps;
- dark/light/system appearance and responsive shared Compose components;
- deterministic mock services, visible isolated Android emulators, and
  synthetic screenshot generation from the real Compose UI.

This is a meaningful application baseline, not a completeness claim. Some app
surfaces remain read-heavy, some actions still need stronger context binding,
contract discovery and persistent caching need further work, and native UX
quality varies by workflow. The public Project tracks those gaps.

## The next phase

The project is moving from a broad native prototype to a dependable daily
client.

| Workstream | What this phase delivers |
| --- | --- |
| Shared foundation | Account-scoped typed transport, SQLite/SQLDelight metadata, stale-while-revalidate repositories, verified contract caching, durable errors, and one identity for the same object across apps |
| Files | Complete browsing and actions, native previews/editors, shares, versions, trash, large folders, resumable transfers, conflicts, and multi-account isolation |
| Sync and offline | Visible upload history, selective offline roots, crash-safe journals and tombstones, Android DocumentsProvider, desktop sync roots, and an Obsidian-grade two-way profile |
| Photos and media | Truthful camera/media backup state, folder preview and destination picking, storage reclaim without hiding media unnecessarily, originals, RAW/video/Live Photos, people workflows, albums, and non-destructive editing |
| DAV and groupware | CardDAV and CalDAV synchronization, native Contacts, Calendar, Tasks, recurrence, sync tokens, and optional operating-system account bridges |
| Talk | Complete messages and attachments first, then push, notifications, system media integration, signaling, and separately gated WebRTC calling |
| Dynamic apps | Faster persistent contract acquisition, relationship-aware navigation, context-bound forms/actions, semantic layouts, and small verified adapters where they genuinely improve the workflow |
| Desktop product | Resizable multi-pane workspaces, keyboard and pointer UX, dense tables, persistent inspectors, desktop file integration, notifications, and update/packaging quality |
| Administration | Native read-only inventory and diagnostics, safe preflight and lifecycle plans, settings generated from verified schemas, and explicit browser/authentication handoff for strict operations |

The dependency gates and data-safety criteria are in
[ROADMAP.md](ROADMAP.md). Live status, priorities, and completed work are in the
[GitHub Project](https://github.com/orgs/Obiente/projects/4).

## Platform status

| Platform | Current state |
| --- | --- |
| Android | Active application target, routine emulator and physical-device QA, signed APK/AAB prereleases |
| Linux | Primary interactive desktop development target, distributable plus RPM/DEB prereleases |
| Windows | Early MSI packaging artifact; native Credential Manager login storage and supported authenticated use are not implemented yet |
| macOS | Early DMG packaging artifact; native Keychain login storage and supported authenticated use are not implemented yet |
| iOS / iPadOS | Planned platform target; no supported launcher is shipped yet |

Android and desktop already share domain models, semantic components, and
product rules. The next-phase architecture moves duplicated transport and
state into shared repositories without requiring the same layout everywhere.
Mobile prioritizes touch, lifecycle, background work, and compact navigation.
Desktop prioritizes multi-pane workflows, keyboard/pointer control, density,
resize behavior, and operating-system file integration.

See [PLATFORMS.md](PLATFORMS.md) for the platform boundary and
[COMPATIBILITY.md](COMPATIBILITY.md) for verified server/app coverage.

## Product and safety principles

- No automatic WebView fallback for installed apps.
- No endpoint, payload, permission, or ID invented by AI or UI inference.
- Reads do not imply writes. Every write needs verified provenance,
  permissions, target identity, validation, conflict behavior, retry policy,
  confirmation, and postcondition recovery.
- Unknown non-idempotent outcomes are visible and reconciled, not blindly
  retried.
- Cached, viewed, available offline, uploaded, and synchronized are distinct
  states.
- Originals are preserved by default. Edits create a new file unless the user
  explicitly chooses a guarded replacement.
- A Login Flow app password is never treated as the primary account password.
- Credentials, share tokens, server URLs, account identifiers, filenames,
  messages, contacts, and other private data stay out of logs, fixtures,
  screenshots, issues, and public artifacts.
- Real-account QA is explicitly authorized and enforced read-only. Write tests
  use disposable synthetic data on an isolated server.
- Previously visited content should appear from cache immediately and refresh
  without throwing away useful state.
- Unsupported behavior is explained honestly instead of presented as a broken
  action.

## Try a prerelease

Testing builds are published on the
[GitHub Releases page](https://github.com/Obiente/nc-native/releases).
Releases remain below `1.0.0` and are marked as prereleases until the published
product, data-loss, security, and platform gates pass.

Android release artifacts are signed with the project's protected release key.
Desktop packages are provided per successful platform build. Windows and macOS
packages currently prove packaging only and do not yet have their required
native credential-store login integration. Read each release's known
limitations before installing over an existing test build.

## Build from source

Requirements:

- JDK 21;
- Rust stable;
- Android SDK Platform 36 and Build Tools 35.0.0 for Android builds.

Use `ANDROID_HOME` or `ANDROID_SDK_ROOT` for your own SDK installation. Do not
commit `local.properties` or a home-directory path.

```bash
git clone https://github.com/Obiente/nc-native.git
cd nc-native

cargo test --locked
./gradlew --no-daemon :ui:desktopTest
./gradlew --no-daemon :ui:createDistributable
./gradlew --no-daemon :androidApp:assembleDebug
bash tools/check-repository.sh
```

The Gradle wrapper pins Gradle itself, and the project build targets JDK 21.
Build and test jobs are path-filtered where appropriate so documentation-only
changes do not rebuild unrelated application targets.

For the complete local test matrix, isolated visible Android emulators, optional
read-only session reuse, and device deployment, read
[CONTRIBUTING.md](CONTRIBUTING.md).

## Repository map

| Path | Purpose |
| --- | --- |
| `src/` and `tests/` | Rust semantic compiler, schema model, inference, and contract tests |
| `contractAcquisition/` | Exact-version signed package and source contract acquisition |
| `ui/` | Shared Compose domain UI plus Android/desktop implementations |
| `androidApp/` | Android launcher and platform integrations |
| `website/` | Self-hosted project site, news, public roadmap, and synthetic real-UI screenshots |
| `tools/` | Repository checks, deployment, emulators, screenshots, and release validation |
| `docs/` | Release notes and operational documentation |

Architecture and protocol references:

- [Product and engineering roadmap](ROADMAP.md)
- [Adapter and transport architecture](ADAPTER_ARCHITECTURE.md)
- [Dynamic App Descriptor](DYNAMIC_APP_DESCRIPTOR.md)
- [Native Schema](NATIVE_SCHEMA.md)
- [Platform strategy](PLATFORMS.md)
- [Compatibility matrix](COMPATIBILITY.md)
- [Changelog](CHANGELOG.md)
- [Security policy](SECURITY.md)

## Contributing

Contributions are welcome, especially:

- reusable semantic components and relationship inference;
- Files, DAV, sync, media, Talk, and platform integration work;
- protocol research backed by official upstream sources;
- versioned compatibility fixtures and mock-server tests;
- accessibility, responsive desktop/mobile UX, and performance improvements;
- translations, documentation, packaging, and deterministic visual QA.

Start with an issue or a focused item in the
[public Project](https://github.com/orgs/Obiente/projects/4). A small
app-specific adapter is appropriate when it provides verified behavior that
cannot be inferred safely. It should still reuse shared models, actions, state,
and components.

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md) before
changing implementation behavior. AI-assisted contributions must remain
human-led and accountable; disclosure is appreciated but optional. See
[AI_POLICY.md](AI_POLICY.md).

Report security issues privately through
[GitHub private vulnerability reporting](https://github.com/Obiente/nc-native/security/advisories/new).

## License and trademark

Nextcloud Native is licensed under the
[GNU Affero General Public License, version 3 or later](LICENSE).

"Nextcloud" is a trademark of Nextcloud GmbH. This independent Obiente project
is not affiliated with, sponsored by, or endorsed by Nextcloud GmbH.
