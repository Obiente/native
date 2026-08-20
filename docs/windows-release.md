# Windows MSI release qualification

This document defines the qualification and integrity requirements for the
per-user x86-64 Windows MSI.

**Last reviewed: 2026-08-20.** Package availability, signing, and qualification
state may have changed. Check the [latest releases](https://github.com/Obiente/nc-native/releases)
and their release notes before installing or publishing an MSI.

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
  --repo Obiente/nc-native
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
- uninstall leaves no active provider process or unusable registered sync
  root;
- silent installation and uninstall work for package-manager validation.

Explorer and writeback qualification uses disposable synthetic accounts. Do
not use personal file trees or include server, account, path, or credential
details in logs and public artifacts.

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
