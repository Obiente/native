#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for required_command in apt-ftparchive apt-get createrepo_c dpkg-deb gpg rpm rpmbuild rpmsign; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf '%s is required to test Linux package repositories.\n' "$required_command" >&2
        exit 2
    fi
done

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
packages="$temporary/packages"
mkdir -p "$packages"

for package_name in nextcloudnative nextcloud-native-vfs; do
    deb_root="$temporary/deb-$package_name"
    mkdir -p "$deb_root/DEBIAN" "$deb_root/usr/share/$package_name"
    printf 'fixture\n' >"$deb_root/usr/share/$package_name/test.txt"
    cat >"$deb_root/DEBIAN/control" <<EOF
Package: $package_name
Version: 1.2.3-1
Section: net
Priority: optional
Architecture: amd64
Maintainer: Nextcloud Native
Description: Repository integration fixture
EOF
    dpkg-deb --build --root-owner-group \
        "$deb_root" "$packages/${package_name}_1.2.3-1_amd64.deb" >/dev/null
done

rpm_top="$temporary/rpmbuild"
mkdir -p "$rpm_top/BUILD" "$rpm_top/BUILDROOT" "$rpm_top/RPMS" \
    "$rpm_top/SOURCES" "$rpm_top/SPECS" "$rpm_top/SRPMS"
for package_name in nextcloudnative nextcloud-native-vfs; do
    spec="$rpm_top/SPECS/$package_name.spec"
    cat >"$spec" <<EOF
Name: $package_name
Version: 1.2.3
Release: 1
Summary: Repository integration fixture
License: AGPL-3.0-or-later
BuildArch: x86_64

%description
Repository integration fixture.

%install
mkdir -p %{buildroot}/usr/share/$package_name
printf 'fixture\\n' >%{buildroot}/usr/share/$package_name/test.txt

%files
/usr/share/$package_name/test.txt
EOF
    rpmbuild --define "_topdir $rpm_top" -bb "$spec" >/dev/null
done
find "$rpm_top/RPMS" -type f -name '*.rpm' -exec cp -- {} "$packages" \;

export GNUPGHOME="$temporary/gnupg"
mkdir -m 0700 "$GNUPGHOME"
gpg --batch --passphrase '' --quick-generate-key \
    'Nextcloud Native Repository Test <repository-test@invalid.example>' \
    rsa2048 sign 0 >/dev/null 2>&1
fingerprint="$(
    gpg --batch --with-colons --list-secret-keys |
        awk -F: '$1 == "fpr" { print toupper($10); exit }'
)"
export NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT="$fingerprint"

output="$temporary/repository"
"$project_root/tools/build-linux-package-repositories.sh" \
    "$packages" "$output" prerelease https://packages.example.invalid >/dev/null

grep -Fxq 'Package: nextcloudnative' \
    "$output/apt/dists/prerelease/main/binary-amd64/Packages"
grep -Fxq 'Package: nextcloud-native-vfs' \
    "$output/apt/dists/prerelease/main/binary-amd64/Packages"
grep -Fxq 'Architectures: amd64' "$output/apt/dists/prerelease/Release"
gpg --batch --verify \
    "$output/apt/dists/prerelease/Release.gpg" \
    "$output/apt/dists/prerelease/Release" >/dev/null 2>&1
gpg --batch --verify "$output/apt/dists/prerelease/InRelease" >/dev/null 2>&1

apt_state="$temporary/apt-state"
mkdir -p "$apt_state/lists/partial"
cat >"$temporary/repository.list" <<EOF
deb [signed-by=$output/keys/nextcloud-native.asc] file:$output/apt prerelease main
EOF
apt-get \
    -o Debug::NoLocking=1 \
    -o "Dir::Etc::sourcelist=$temporary/repository.list" \
    -o Dir::Etc::sourceparts=- \
    -o "Dir::State::lists=$apt_state/lists" \
    -o APT::Get::List-Cleanup=0 \
    update >/dev/null

grep -Fq 'gpgcheck=1' "$output/nextcloud-native.repo"
grep -Fq 'repo_gpgcheck=1' "$output/nextcloud-native.repo"
gzip --decompress --stdout "$output/rpm/x86_64/repodata/primary.xml.gz" |
    grep -Fq '<name>nextcloudnative</name>'
gzip --decompress --stdout "$output/rpm/x86_64/repodata/primary.xml.gz" |
    grep -Fq '<name>nextcloud-native-vfs</name>'
gpg --batch --verify \
    "$output/rpm/x86_64/repodata/repomd.xml.asc" \
    "$output/rpm/x86_64/repodata/repomd.xml" >/dev/null 2>&1

rpm_database="$temporary/rpm-database"
mkdir -p "$rpm_database"
rpm --define "_dbpath $rpm_database" --import "$output/keys/nextcloud-native.asc"
while IFS= read -r -d '' package; do
    rpm --define "_dbpath $rpm_database" --checksig "$package" |
        grep -Eq 'digests signatures OK|digests OK'
done < <(find "$output/rpm" -type f -name '*.rpm' -print0)

test -s "$output/SHA256SUMS"
if "$project_root/tools/build-linux-package-repositories.sh" \
    "$packages" "$output" prerelease https://packages.example.invalid \
    >"$temporary/reuse-output" 2>&1; then
    printf 'Repository builder accepted an existing output directory.\n' >&2
    exit 1
fi
grep -Fq 'must not already exist' "$temporary/reuse-output"

printf 'Signed APT and RPM repository integration checks passed.\n'
