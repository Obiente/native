# Documentation index

Use this index to find the maintained document for a question instead of
copying status or policy into another file.

## Product direction and compatibility

- [Platform strategy](../PLATFORMS.md): shared and platform-specific ownership,
  plus a dated implementation snapshot.
- [Compatibility contract](../COMPATIBILITY.md): dated native implementation
  evidence and the target product-completeness rules.
- [Roadmap](../ROADMAP.md): planned work, order, and acceptance gates.

## Architecture contracts

- [Adapter architecture](../ADAPTER_ARCHITECTURE.md): transport, repositories,
  adapters, persistence, errors, mutations, and required tests.
- [Dynamic App Descriptor](../DYNAMIC_APP_DESCRIPTOR.md): discovered API facts
  and execution policy.
- [Native Schema](../NATIVE_SCHEMA.md): the trust boundary between discovery
  and native presentation.

## Contributing and security

- [Contributing](../CONTRIBUTING.md): setup, verification, test-account safety,
  pull requests, and authorship requirements.
- [AI policy](../AI_POLICY.md): allowed assistance and human responsibility.
- [Security policy](../SECURITY.md): private vulnerability reporting and the
  dated support statement.

## Release engineering

- [Prerelease policy](releases.md): versions, changelog fragments, tags, and
  protected release builds.
- [Prerelease checklist](prerelease-checklist.md): release-blocking safety and
  packaging checks.
- [Windows MSI qualification](windows-release.md): unsigned-package disclosure,
  provenance, and acceptance criteria.
- [Linux package repositories](linux-package-repositories.md): signed APT and
  RPM repository snapshots.

## Website and maintained assets

- [Website contributor guide](../website/README.md): content, captures, builds,
  and deployment configuration.
- [Design assets](../design/README.md): canonical icon sources and asset
  ownership.
- [Marketing capture assets](../ui/src/desktopMain/resources/marketing/README.md):
  provenance for the synthetic organization avatar.

Time-sensitive documents state when they were last reviewed. Treat that date
as context, not a guarantee; follow the linked source of truth before making a
release, compatibility, installation, or security-support claim.
