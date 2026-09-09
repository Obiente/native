# Linux package repositories

This is a maintainer guide for producing signed APT and RPM repository
snapshots. It does not claim that a public repository is currently available.

**Last reviewed: 2026-08-20.** Distribution endpoints and release channels may
have changed. Check the [latest releases](https://github.com/obiente/native/releases)
before publishing or configuring a client.

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

The input may contain more than one package. Each package must declare its own
lifecycle and dependencies in native package metadata before it is added to a
repository snapshot.

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

The examples use `https://packages.example.org` as a placeholder. Replace it
with the deployed package origin; the website domain does not establish a
package repository endpoint. APT `Origin` and `Label` retain `Nextcloud Native`
as repository identities so existing clients do not require release-info-change
acceptance solely for the product rename.

## Build a repository snapshot

Install AppStream, `apt-ftparchive`, `cpio`, `createrepo_c`, `dpkg-deb`,
GnuPG, RPM, and `rpmsign`, then run:

```bash
export NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT=FULL_40_CHARACTER_FINGERPRINT
tools/build-linux-package-repositories.sh \
  dist \
  linux-repository \
  prerelease \
  https://packages.example.org
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
`https://github.com/obiente/native/releases`. That GitHub-hosted value is the
independently authenticated expected fingerprint. Download the certificate to
a temporary file, inspect it locally, and compare the complete value before
installing a clean export of it:

```bash
expected_fingerprint=FULL_FINGERPRINT_FROM_THE_GITHUB_RELEASE
expected_fingerprint="${expected_fingerprint^^}"
[[ "$expected_fingerprint" =~ ^[A-F0-9]{40}$ ]]
verification_home="$(mktemp -d)"
chmod 700 "$verification_home"
trap 'rm -r -- "$verification_home"' EXIT
curl --fail --proto '=https' --tlsv1.2 \
  --output nextcloud-native.asc \
  https://packages.example.org/keys/nextcloud-native.asc
GNUPGHOME="$verification_home" gpg --batch --import nextcloud-native.asc
mapfile -t actual_fingerprints < <(
  GNUPGHOME="$verification_home" gpg --batch --with-colons --list-keys |
    awk -F: '$1 == "pub" { primary = 1; next }
      primary && $1 == "fpr" { print toupper($10); primary = 0 }'
)
test "${#actual_fingerprints[@]}" -eq 1
test "${actual_fingerprints[0]}" = "$expected_fingerprint"
GNUPGHOME="$verification_home" gpg --batch --armor \
  --export "$expected_fingerprint" >nextcloud-native-verified.asc
test -s nextcloud-native-verified.asc
rm -r -- "$verification_home"
trap - EXIT
```

Stop if the certificate count or fingerprint differs. APT clients then install
`nextcloud-native-verified.asc` as `/etc/apt/keyrings/nextcloud-native.asc`, install the generated
`nextcloud-native.sources` file as
`/etc/apt/sources.list.d/nextcloud-native.sources`, and run `apt update`.

DNF clients install the same clean verified certificate as
`/etc/pki/rpm-gpg/NEXTCLOUD-NATIVE-REPOSITORY`, then install the generated
`nextcloud-native.repo` file as `/etc/yum.repos.d/nextcloud-native.repo`. The
generated configuration references that local certificate. Both package and
repository-metadata signature checking are enabled.

The public origin must serve files byte-for-byte over HTTPS and preserve the
repository paths. Hosting selection, retention, and publication are operational
decisions; this guide does not designate a currently supported public package
origin. The checked-in
[`tools/build-linux-package-repositories.sh`](../tools/build-linux-package-repositories.sh)
is the source of truth for the generated repository layout.
