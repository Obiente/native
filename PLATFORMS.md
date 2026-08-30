# Platform strategy

This document defines the platform boundary for Nextcloud Native. It separates
portable product behavior from operating-system integration so shared code does
not erase native security, lifecycle, accessibility, or filesystem semantics.

**Last reviewed: 2026-08-20.** Implementation and release availability may
have changed. The [GitHub Releases page](https://github.com/Obiente/nc-native/releases)
is the source of truth for published artifacts and their limitations.

The target architecture has three portable layers and thin platform products:

1. The Rust semantic compiler produces the same validated native schema.
2. Shared Kotlin repositories own account state, caching, pagination,
   conflicts, and actions.
3. Shared Compose components render semantic workflows without HTML.
4. Platform launchers own lifecycle, layout adaptation, secure storage,
   background work, files, notifications, media, sharing, and packaging.

## Reviewed implementation snapshot

This table describes the repository state at the review date. It is not a
support guarantee. A packaged artifact is not supported for a workflow until
its platform acceptance criteria pass and its limitations are documented.

| Platform | Runtime | Current state | Platform-specific direction |
| --- | --- | --- | --- |
| Android | Compose Multiplatform | Active launcher and signed alpha APK/AAB | Keystore, WorkManager, DocumentsProvider, permissions, notifications, shares, media sessions, camera backup, and calls |
| Linux | Compose Desktop | Primary interactive desktop target; alpha RPM/DEB | Secret Service, desktop file integration, notifications, media keys, portals, and conventional sync roots |
| Windows | Compose Desktop | Unsigned x86-64 MSI with Credential Manager, attested builds, and Cloud Files integration under prerelease qualification | Explorer validation, free trusted signing when available, notifications, media controls, and updates |
| macOS | Compose Desktop | Early DMG packaging artifact; no supported authenticated login yet | Keychain, File Provider/Finder integration, notifications, media controls, and updates |
| iOS / iPadOS | Planned Compose target | No supported launcher is shipped | Keychain, File Provider, background transfer, share extension, notifications, media, and CallKit |

Packaging is not feature parity. A platform becomes supported for a workflow
only after its platform-specific acceptance tests pass and the limitation is
recorded in [COMPATIBILITY.md](COMPATIBILITY.md).

Standard CI builds Android packages and runs Android unit tests, but it does not
run connected-device instrumentation. A package or passing unit-test job is not
evidence that device-specific acceptance criteria have passed.

## Shared boundaries

- Shared modules must not import Android, Apple, Windows, macOS, or Linux APIs.
- Platform services implement interfaces owned by shared domain code.
- Protocol parsing, permission rules, sync policy, retries, conflicts, and
  account identity belong in shared code. Existing duplication should move
  toward that boundary rather than becoming a new platform-specific design.
- Secure credentials remain inside the platform credential store.
- Filesystem providers and sync roots expose shared file and transfer semantics
  while retaining each operating system's native provider model.
- Shared Compose components contain behavior and semantics. Platform layouts
  may arrange them differently.

Office uses native document selection. Android embeds only user-requested
Direct Editing sessions, not the Nextcloud dashboard or app navigation; desktop
opens editing sessions in the system browser. Preview and Edit are separate
actions, with editing gated by advertised MIME support and current permissions.
This does not provide an automatic web fallback for other apps.
See [ADR 0001](docs/architecture-decisions/0001-android-office-web-integration.md)
for the authentication, provider selection, and retry boundaries. Device-level
Office acceptance remains separate from compiling or packaging this integration.

## Mobile product rules

- Correct safe-area insets, system back, touch targets, and permission flows.
- State restoration across rotation, activity recreation, and process death.
- Durable background work that is honest about Android/iOS scheduling limits.
- Progressive layouts for large phones, tablets, foldables, and external
  displays.
- Native share/open-with, notifications, media sessions, filesystem providers,
  camera/media discovery, and calling surfaces.

## Desktop product rules

- Resizable master-detail and multi-pane workspaces where the workflow benefits.
- Keyboard navigation, pointer selection, context menus, drag-and-drop, and
  accessibility focus.
- Dense tables, persistent inspectors, multi-selection, and broad content views
  instead of phone cards stretched across a window.
- Conventional sync roots or native virtual-file providers according to the
  operating system.
- Secret storage, notifications, system media controls, file associations,
  updates, and native packaging.

## Platform delivery rule

Shared code is valuable only when it preserves correct native behavior. Move a
rule into shared code when it is domain policy. Keep an implementation in a
platform source set when it depends on lifecycle, security, scheduling,
filesystem, media, notification, windowing, or accessibility APIs unique to
that operating system.

The dependency order and acceptance gates are defined in
[ROADMAP.md](ROADMAP.md), especially the platform productization milestone.
