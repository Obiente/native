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
- AppStream catalogs for APT DEP-11 and RPM repository metadata, allowing
  software centers to load the application name, icon, screenshots, and
  version-specific release details before installation;
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

Install AppStream, `apt-ftparchive`, `cpio`, `createrepo_c`, `dpkg-deb`,
GnuPG, RPM, and `rpmsign`, then run:

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

Do not trust a certificate fetched only from the package origin. Before a
channel is made public, its full 40-character signing fingerprint must also be
published in the corresponding release at
`https://github.com/Obiente/nc-native/releases`. That GitHub-hosted value is the
independently authenticated expected fingerprint. Download the certificate to
a temporary file, inspect it locally, and compare the complete value before
installing it:

```bash
expected_fingerprint=FULL_FINGERPRINT_FROM_THE_GITHUB_RELEASE
curl --fail --proto '=https' --tlsv1.2 \
  --output nextcloud-native.asc \
  https://packages.nc-native.obiente.dev/keys/nextcloud-native.asc
actual_fingerprint="$(
  gpg --batch --show-keys --with-colons nextcloud-native.asc |
    awk -F: '$1 == "fpr" { print toupper($10); exit }'
)"
test "$actual_fingerprint" = "$expected_fingerprint"
```

Stop if the values differ. APT clients then install the verified certificate
as `/etc/apt/keyrings/nextcloud-native.asc`, install the generated
`nextcloud-native.sources` file as
`/etc/apt/sources.list.d/nextcloud-native.sources`, and run `apt update`.

DNF clients install the same verified certificate as
`/etc/pki/rpm-gpg/NEXTCLOUD-NATIVE-REPOSITORY`, then install the generated
`nextcloud-native.repo` file as `/etc/yum.repos.d/nextcloud-native.repo`. The
generated configuration references that local certificate. Both package and
repository-metadata signature checking are enabled.

The public origin must serve files byte-for-byte over HTTPS and preserve the
repository paths. Do not use GitHub Pages for the package payloads: the current
site-size and bandwidth limits are too small for retaining multiple native
desktop package versions.
