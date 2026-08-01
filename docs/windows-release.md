# Windows release qualification

Nextcloud Native currently produces one x86-64 MSI for Windows. A Windows build
is release-qualified only after the installer, credentials, Cloud Files sync
root, upgrade, recovery, and signature gates below pass on supported Windows
versions.

## Protected signing configuration

Nightly and curated prerelease workflows read these secrets from the protected
`prerelease` environment:

- `WINDOWS_SIGNING_CERTIFICATE_BASE64`: the complete PFX encoded as base64;
- `WINDOWS_SIGNING_CERTIFICATE_PASSWORD`: the PFX password;
- `WINDOWS_SIGNING_CERTIFICATE_SHA256`: the lowercase SHA-256 digest of the
  public signing certificate, with or without colons.

The certificate must be an Authenticode code-signing certificate that chains
to a root trusted by supported Windows installations. Test and self-signed
certificates are not suitable for public releases.

The release workflow verifies the protected certificate identity, signs the
MSI with SHA-256, obtains an RFC 3161 SHA-256 timestamp, and verifies the final
Authenticode signature before uploading the package. Signing material is
created only in the ephemeral runner's temporary directory and removed after
the signing step.

## Automated gates

The Windows CI job runs on `windows-latest` when desktop or packaging inputs
change. It must:

1. run the complete desktop test suite, including a real current-user Windows
   Credential Manager round trip;
2. build the MSI on Windows rather than cross-package it;
3. verify `ProductName`, `ProductVersion`, `Manufacturer`, and the stable MSI
   `UpgradeCode`;
4. upload the exact verified MSI as the Windows workflow artifact.

Nightly and curated release jobs add the protected Authenticode signing gate.
An unsigned or incorrectly signed MSI must not become a Windows release asset.

## Real Windows acceptance

Before calling a Windows prerelease supported, test the exact signed artifact
on a clean supported x86-64 Windows installation and as an upgrade from the
previous published package:

- the MSI reports the expected publisher and installs per user;
- the Start menu and desktop shortcuts launch the packaged app;
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
  root.

Explorer and writeback qualification uses disposable synthetic accounts. Do
not use personal file trees or include server, account, path, or credential
details in logs and public artifacts.
