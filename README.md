# Nextcloud Native

[![Build and test](https://github.com/obiente/nc-native/actions/workflows/ci.yml/badge.svg)](https://github.com/obiente/nc-native/actions/workflows/ci.yml)
[![License: AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue.svg)](LICENSE)

An independent, unofficial Obiente project for building native clients that integrate with Nextcloud. It is not affiliated with or endorsed by Nextcloud GmbH.

> **Development preview:** the project is under active development and is not
> yet suitable as the only client for important data.

This project is the shared intelligence layer for an adaptive native Nextcloud
client. It does not render remote web pages. It compiles the metadata and APIs
of an installed Nextcloud app into a small, typed native UI schema.

The intended flow is:

```text
Nextcloud metadata + capabilities + OpenAPI + observed behaviour
                              |
                              v
                     semantic compiler
                              |
                              v
                Nextcloud Native Schema v0.1
                     /                  \
                    v                    v
       Android / iOS UI       Windows / macOS / Linux UI
```

## Design rules

- Deterministic discovery and typed API descriptions come before AI inference.
- AI may propose semantics, but it may not invent endpoints or payload fields.
- Read operations are safe to explore. Inferred writes require confirmation.
- Generated schemas are tied to an exact server app version and are rebuilt
  after an upgrade.
- A verified adapter can enhance an inferred schema without replacing the
  generic runtime.
- All inference and learned adapters remain local unless the user explicitly
  opts into sharing them.

## Current milestone

The current milestone provides:

- `DiscoverySnapshot`, the normalized input gathered from a Nextcloud server.
- `NativeAppSchema`, a platform-neutral component and action grammar.
- `OpenApiCompiler`, a conservative compiler from OpenAPI operations to native
  resources, collection views, detail views, forms and actions.
- `DynamicAppDescriptor` 1.0 and `DynamicDescriptorCompiler`, the validated
  discovery/execution contract for advertised OpenAPI and approved successful
  JSON reads. See [DYNAMIC_APP_DESCRIPTOR.md](DYNAMIC_APP_DESCRIPTOR.md).
- Signed-contract acquisition for apps that do not advertise their schema at
  runtime. The client selects the exact installed App Store release, verifies
  its Nextcloud certificate chain, revocation status and archive signature,
  then compiles the packaged OpenAPI contract without app-specific code. If a
  verified package has no contract, the client may use an explicitly
  lower-trust OpenAPI file from the exact GitHub tag linked by the App Store,
  only after matching its app ID and version; endpoint execution remains
  restricted to the connected server's approved app prefixes.
- `AdapterRegistry`, an explicit extension point for verified app knowledge.
- Representative compiler tests for media, expense and conversation models.
- A real authenticated Compose application with native Files, Photos/Memories,
  Talk, Activity and Notes experiences.
- File list/grid layouts, server previews, a zoomable media viewer, per-person
  Memories galleries, safe UTF-8 file editing, and ETag-protected Markdown
  Notes editing with native edit/preview modes.
- Typed Talk message rendering for text, shared files, recordings, calls,
  system events and shared objects. History reads explicitly avoid read-marker,
  notification and presence mutations.
- A bounded in-memory preview LRU. The persistent encrypted metadata/blob cache
  and offline mutation queue are specified but not implemented yet.
- A session cache for already verified app contracts. Persistent verified
  package caching is still pending.

The platform projects consume the schema and map `NativeComponent` values to
real Compose components. The shared component layer supports Android, iOS,
Windows, macOS and Linux; thin platform launchers own secure storage,
notifications, background work, sharing and calling integrations. No HTML or
CSS is part of the runtime contract.

The Android launcher supports Nextcloud Login Flow v2, stores the resulting app
password with an Android Keystore key, discovers the authenticated user's app
navigation and capabilities, and provides system, light and dark appearance
settings. The Linux desktop launcher stores the app password through Secret
Service. Normal account passwords never enter either app.

The integration work is grounded in official server and app repositories. See
[ADAPTER_ARCHITECTURE.md](ADAPTER_ARCHITECTURE.md) for the public adapter,
permission, conflict, and confirmation boundaries. Strict administrator actions
cannot be authorized with a stored Login Flow app password; those use an
explicit authenticated admin handoff instead of asking the app to store the
account password.

## Development

Requirements:

- JDK 21
- Rust stable
- Android SDK Platform 36 and Build Tools 35.0.0 for Android builds
- `adb` and Avahi tools only when deploying wirelessly to a local device

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your own Android SDK location. Do
not commit `local.properties`; it is ignored because SDK paths are specific to
each contributor's machine.

```bash
cargo test
./gradlew :ui:desktopTest
./gradlew :ui:createDistributable
./gradlew :androidApp:assembleDebug
```

The Gradle wrapper pins the build system and downloads project dependencies, so
contributors do not need a global Gradle installation. CI builds the Rust core,
shared desktop renderer, runnable Linux app image and Android APK on every
push and pull request. Both built applications are uploaded as CI artifacts.

See [COMPATIBILITY.md](COMPATIBILITY.md) for the first real app test matrix and
[PLATFORMS.md](PLATFORMS.md) for the cross-platform boundary.

Contribution guidance is in [CONTRIBUTING.md](CONTRIBUTING.md). Please report
security issues privately as described in [SECURITY.md](SECURITY.md).

## Wireless Android test device

Android wireless-debugging ports change when the service restarts, so the
helper discovers the current trusted mDNS endpoint. With one paired device:

```bash
./tools/adb-connect-wireless.sh
./tools/install-debug-apk.sh
./tools/deploy-android-debug.sh
```

If several paired devices are visible, select one by its `adb devices -l`
model name, for example
`ADB_DEVICE_NAME=YourModel ./tools/deploy-android-debug.sh`.

Wireless debugging remains LAN-only. Do not forward its dynamic port through a
public router.

## Linux and Android test deployment

On Linux, build and open the desktop app and update a paired Android test device
with one command:

```bash
./tools/deploy-local.sh
```

The helper runs the shared desktop tests, creates the Linux desktop app image,
builds the Android debug APK, starts the freshly built desktop app, then
installs and opens the Android app. An already-running desktop process from this
checkout is cleanly restarted so it uses the new build without creating a
duplicate. Desktop account state remains in the system credential store.
Android installation uses `adb install -r`, so application data and the
signed-in session are kept across debug updates.

The generated Linux executable is under
`ui/build/compose/binaries/main/app/NextcloudNative/bin/`. Desktop logs from a
launch performed by the helper are written beside the app image in
`nextcloud-native.log`.

## License

Nextcloud Native is licensed under the
[GNU Affero General Public License, version 3 or later](LICENSE).

“Nextcloud” is a trademark of Nextcloud GmbH. This independent project is not
affiliated with, sponsored by, or endorsed by Nextcloud GmbH.
