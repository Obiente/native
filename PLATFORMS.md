# Platform strategy

The project has two portable layers and thin launchers:

1. The Rust semantic compiler builds the same typed schema on every platform.
2. The shared Compose component library renders that schema without HTML.
3. Platform launchers own only lifecycle and operating-system integrations.

## Supported build direction

| Platform | Shared runtime | Platform-specific responsibilities |
| --- | --- | --- |
| Android | Compose + semantic schema | Keystore, background upload, shares, notifications, camera and calls |
| iOS | Compose + semantic schema | Keychain, background transfer, share extension, notifications and CallKit |
| Windows | Compose Desktop | Credential Manager, file integration, notifications and packaging |
| macOS | Compose Desktop | Keychain, Finder integration, notifications and packaging |
| Linux | Compose Desktop | Secret Service, file integration, notifications and packaging |

The current build enables the desktop JVM target because it can be compiled and
tested on this Linux workstation without downloading an Android SDK. All UI code
lives in `commonMain`, so adding Android and iOS targets must not require moving
or duplicating components.

No shared module may import Android, Apple, Windows or Linux APIs. Those belong
behind platform interfaces in their respective source sets.

