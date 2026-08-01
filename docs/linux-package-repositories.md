# Linux package repositories

GitHub release attachments remain useful as immutable source artifacts, but
they are not an APT or RPM repository. Native package-manager distribution
requires indexed repository trees, an HTTPS origin, and a dedicated OpenPGP
signing identity.

`tools/build-linux-package-repositories.sh` builds both repository formats from
a directory containing the complete retained set of `.deb` and `.rpm` files:

- an APT archive with per-architecture `Packages` indexes and signed `Release`,
  `Release.gpg`, and `InRelease` metadata;
- an RPM repository with signed packages, `createrepo_c` metadata, and a signed
  `repomd.xml`;
- an exported public certificate, fingerprint, checksums, and example
  `nextcloud-native.sources` and `nextcloud-native.repo` client configuration.

The input may contain more than the UI package. A future native
`nextcloud-native-vfs` host-service package should be built separately, declare
its systemd or D-Bus lifecycle in native package metadata, and be placed beside
the UI packages before the repository indexes are generated. This keeps the
privileged filesystem integration out of the UI process and lets APT or DNF
install, update, and remove both components transactionally.

## Signing identity

Use a dedicated OpenPGP signing-only key held by the protected `prerelease`
GitHub environment. The workflow-facing key should not require interactive
pinentry because `rpmsign` runs non-interactively. Keep the primary key and a
revocation certificate offline, and back them up before the first public
repository is published.

The builder requires the full 40-character fingerprint in
`NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT` and the corresponding secret key in
the active GnuPG home. Public clients receive only the exported certificate.
Never commit the secret key or its backup.

## Build a repository snapshot

Install `apt-ftparchive`, `createrepo_c`, `dpkg-deb`, GnuPG, RPM, and
`rpmsign`, then run:

```bash
export NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT=FULL_40_CHARACTER_FINGERPRINT
tools/build-linux-package-repositories.sh \
  dist \
  linux-repository \
  prerelease \
  https://packages.nc-native.obiente.dev
```

`linux-repository` must not already exist. `dist` must contain at least one
regular DEB and RPM and should also contain all older versions that the public
repository intends to retain. Build into a new versioned directory, verify it,
upload it without changing the active repository, and switch the public
repository pointer only after the complete snapshot is available.

## Client configuration

APT clients install the exported certificate as
`/etc/apt/keyrings/nextcloud-native.asc`, install the generated
`nextcloud-native.sources` file as
`/etc/apt/sources.list.d/nextcloud-native.sources`, and run `apt update`.

DNF clients install the generated `nextcloud-native.repo` file as
`/etc/yum.repos.d/nextcloud-native.repo`. Both package and repository-metadata
signature checking are enabled.

The public origin must serve files byte-for-byte over HTTPS and preserve the
repository paths. Do not use GitHub Pages for the package payloads: the current
site-size and bandwidth limits are too small for retaining multiple native
desktop package versions.
