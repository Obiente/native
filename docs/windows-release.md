# Windows MSI release qualification

This document defines the qualification and integrity requirements for the
per-user x86-64 Windows MSI.

**Last reviewed: 2026-08-20.** Package availability, signing, and qualification
state may have changed. Check the [latest releases](https://github.com/obiente/native/releases)
and their release notes before installing or publishing an MSI. The
[Publish prerelease workflow](../.github/workflows/prerelease.yml) and
[`tools/verify-windows-package.ps1`](../tools/verify-windows-package.ps1) define
the current automated release checks.

## Current trust state

At the review date, Windows MSIs are published without an Authenticode
signature. A
self-signed certificate is not used because it would not be trusted by normal
Windows installations and asking users to install a custom root certificate
would weaken their security boundary.

Microsoft Defender SmartScreen may therefore warn before installation. After
confirming that the MSI came from the project's GitHub release, a user on an
unmanaged device can choose `More info > Run anyway`. Organization-managed
devices may block unsigned installers through policy.

Release notes must disclose this limitation whenever a Windows MSI is
available. The project must not tell users to disable SmartScreen, Defender, or
another system security control.

## Free integrity and provenance

An unsigned MSI is release-eligible only when all of these controls pass:

1. GitHub Actions builds it from the exact protected source revision used for
   the release.
2. `tools/verify-windows-package.ps1` verifies `ProductName`,
   `ProductVersion`, `Manufacturer`, the stable MSI `UpgradeCode`, and the
   intentionally unsigned signature state.
3. GitHub creates a keyless Sigstore build-provenance attestation for the exact
   MSI before it is uploaded as a workflow artifact.
4. The immutable release includes the MSI digest in `SHA256SUMS`.
5. A published release asset is never replaced in place. A corrected build
   receives a new version, tag, URL, checksum, and attestation.

Verify a downloaded MSI's provenance with GitHub CLI:

```powershell
gh attestation verify .\NextcloudNative-<version>.msi `
  --repo obiente/native
```

Verify its release checksum with PowerShell:

```powershell
Get-FileHash .\NextcloudNative-<version>.msi -Algorithm SHA256
```

GitHub provenance and checksums detect replacement and identify the source
workflow, but they are not an Authenticode publisher signature and do not
remove SmartScreen warnings.

## Automated gates

The Windows CI job runs on a GitHub-hosted Windows runner when desktop or
packaging inputs change. It must:

1. run the complete desktop test suite, including a real current-user Windows
   Credential Manager round trip;
2. build the MSI on Windows rather than cross-package it;
3. verify product, package version, manufacturer, upgrade identity, non-empty
   content, and the explicitly unsigned signature state;
4. exercise WinGet manifest generation against the verified MSI;
5. upload the exact verified MSI as the Windows workflow artifact.

Nightly and curated prerelease workflows repeat MSI verification and create the
provenance attestation before upload. No Windows private key, PFX, certificate
password, or paid signing-service credential is required.

## Installation and upgrade acceptance

Before calling a Windows prerelease supported, test the exact published MSI on
a clean supported x86-64 Windows installation and as an upgrade from the
previous published package:

- the disclosed SmartScreen path allows installation on an unmanaged test
  device without disabling security controls;
- the MSI installs per user and Windows identifies the publisher as unknown;
- Start menu and desktop shortcuts launch the packaged app;
- Login Flow credentials survive relaunch in Windows Credential Manager and
  account removal clears them;
- the Cloud Files sync root appears in Explorer;
- directory enumeration, placeholder hydration, pinning, dehydration, rename,
  delete, and guarded writeback work against disposable synthetic data;
- process termination during hydration and writeback recovers without silent
  overwrite or abandoned unbounded staging data;
- folder rename updates descendant placeholder identities;
- upgrade preserves account, sync, cache, and recovery state and starts only
  the new version;
- account removal disconnects and unregisters its sync root;
- recovery accepts an empty saved provider GUID only with the exact Windows
  user, account root context, checksum, and permitted path; foreign identities
  and damaged or unsupported context remain rejected;
- uninstall leaves no active provider process or unusable registered sync
  root;
- silent installation and uninstall work for package-manager validation.

Explorer and writeback qualification uses disposable synthetic accounts. Do
not use personal file trees or include server, account, path, or credential
details in logs and public artifacts.

Run `cargo test --locked --bin nextcloud-native-shell-registrar` for deterministic
registration ownership coverage. On a Windows test checkout on NTFS, also run
`cargo test --locked --bin nextcloud-native-shell-registrar persisted_root_can_be_owned_and_unregistered -- --ignored`.
That explicit integration test creates and removes only its disposable empty
Explorer root under the checkout's ignored `target` directory. It checks saved
registration ownership, rejection of a different requested path, and
unregistration. It does not validate personal-account recovery or writeback.

## Short placeholder identity regression

Root enumeration accepts the registered root context when Windows omits the item
identity, only for a matching nonzero root file ID. Account and normalized path
checks still apply. Enumeration reconciles existing children before completing
the callback, avoiding duplicate placeholder submissions.

Item identities use a version 3 envelope padded to at least 256 bytes, including
the checksum. This is an application compatibility measure: short directory
identities reproduced `0x8007016b` on a local Windows installation. It is not a
documented CFAPI minimum. Readers accept versions 1, 2, and 3; the registered
root context remains byte-identical version 2 so an upgrade does not replace a
healthy registration. Older binaries cannot decode version 3 item identities.

**Last reviewed: 2026-09-06.** Local Windows tests passed creation, directory listing, hydration,
restart, and recovery from actual corrupt version 2 placeholders while preserving
a synthetic local file. This evidence does not qualify personal-account
writeback or a published upgrade; check the [releases](https://github.com/obiente/native/releases)
for available artifacts. On a Windows NTFS test checkout, run:

```powershell
.\gradlew.bat :ui:createDistributable
$env:NATIVE_WINDOWS_TEST_LAUNCHER = (Resolve-Path 'ui/build/compose/binaries/main/app/NextcloudNative/NextcloudNative.exe').Path
.\gradlew.bat :ui:desktopTest --tests '*WindowsCloud*'
```

The opt-in native tests use the packaged registrar, disposable roots, and an
in-memory backend that rejects server mutations. Without the launcher variable,
these integration tests are skipped. The legacy-corruption case also skips on
Windows versions where the old encoding no longer reproduces the failure.

## WinGet delivery

`tools/create-winget-manifests.ps1` generates a three-file WinGet manifest from
the verified MSI. It reads the MSI product and upgrade identities directly,
calculates the SHA-256 digest, and uses an immutable versioned GitHub release
URL.

The generated manifest is a candidate, not an automatic publication. Submit it
to `microsoft/winget-pkgs` only for a published release that installs and
uninstalls silently and has completed the Windows acceptance checks. WinGet may
reject an unsigned installer during automated or manual validation.

## Signing evolution

Free SignPath Foundation signing can be added later if the project is accepted.
A paid or exportable PFX workflow is intentionally not configured. When a
publicly trusted signing service is adopted, update the verifier, deep-sign
project-owned executable content before packaging, sign the MSI last, retain
RFC 3161 timestamps, and remove the unsigned-install disclosure only after the
published artifact verifies successfully on a clean Windows installation.

## In-app update handoff

After the user chooses **Update and restart**, the Windows updater downloads and
verifies the MSI, waits for the app to exit, then invokes Windows Installer with
`/quiet /norestart`. No installer wizard is required. The handoff waits for the
installer to finish, releases its single-update gate, and relaunches nati.ve.
An installation failure relaunches the existing app with an update-failure
notice when its launcher remains available. Windows policy failures remain
failures; the updater does not fall back to elevation or disable security checks.

The installer is instructed not to reboot Windows. A reboot-required result is
accepted as installer completion, without automatically restarting the OS.
The app's existing update/version checks remain authoritative for the running
version after relaunch. The manual first-install MSI remains interactive.

This is the source behavior for in-app Windows updates, not a claim that an
artifact containing it has been published. An older installed updater still uses
its original handoff for the first upgrade to a build containing this change.
Automatic update checks continue to notify the user; this change does not opt
users into unattended downloads or close their app on a schedule.

Regression tests execute the generated handoff script with synthetic installer
success, failure, and reboot-required results. They verify quiet arguments,
quoted package paths, parent-exit ordering, gate release, and relaunch behavior
without modifying an installed application. MSI behavior uses the documented
[Windows Installer command-line options](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/msiexec).
