#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for required_command in \
    appstreamcli apt-ftparchive apt-get cpio createrepo_c dpkg-deb gpg \
    modifyrepo_c python3 rpm rpm2cpio rpmbuild rpmsign; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf '%s is required to test Linux package repositories.\n' "$required_command" >&2
        exit 2
    fi
done

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
packages="$temporary/packages"
mkdir -p "$packages"
fixture_metadata="$temporary/dev.obiente.nextcloudnative.metainfo.xml"
python3 "$project_root/tools/render-linux-appstream-metadata.py" \
    "$project_root/release/linux/dev.obiente.nextcloudnative.metainfo.xml" \
    "$fixture_metadata" 1.2.3 1.2.3 2026-08-01

older_metadata="$temporary/renamed-z-older.metainfo.xml"
newer_metadata="$temporary/renamed-a-newer.metainfo.xml"
python3 "$project_root/tools/render-linux-appstream-metadata.py" \
    "$project_root/release/linux/dev.obiente.nextcloudnative.metainfo.xml" \
    "$older_metadata" 1.9.0 1.9.0 2026-07-31
python3 "$project_root/tools/render-linux-appstream-metadata.py" \
    "$project_root/release/linux/dev.obiente.nextcloudnative.metainfo.xml" \
    "$newer_metadata" 2.0.0 2.0.0 2026-08-01
python3 - "$older_metadata" "$newer_metadata" <<'PY'
import sys
import xml.etree.ElementTree as ET

for path, summary in zip(sys.argv[1:], ("Older metadata", "Newer metadata"), strict=True):
    tree = ET.parse(path)
    tree.getroot().find("summary").text = summary
    tree.write(path, encoding="UTF-8", xml_declaration=True)
PY
version_catalog="$temporary/version-ordered-catalog.xml"
python3 "$project_root/tools/build-appstream-catalog.py" \
    "$version_catalog" nextcloud-native-test \
    2.0.0 "$newer_metadata" \
    1.9.0 "$older_metadata"
python3 - "$version_catalog" <<'PY'
import sys
import xml.etree.ElementTree as ET

component = ET.parse(sys.argv[1]).getroot().find("component")
assert component is not None
assert component.findtext("summary") == "Newer metadata"
versions = [release.attrib["version"] for release in component.findall("./releases/release")]
assert versions[:2] == [
    "2.0.0",
    "1.9.0",
]
assert versions.index("2.0.0") < versions.index("1.9.0")
PY

for package_name in nextcloudnative nextcloud-native-vfs; do
    deb_root="$temporary/deb-$package_name"
    mkdir -p "$deb_root/DEBIAN" "$deb_root/usr/share/$package_name"
    printf 'fixture\n' >"$deb_root/usr/share/$package_name/test.txt"
    if [[ "$package_name" == nextcloudnative ]]; then
        install -D -m 0644 "$fixture_metadata" \
            "$deb_root/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml"
    fi
    package_architecture=amd64
    if [[ "$package_name" == nextcloud-native-vfs ]]; then
        package_architecture=all
    fi
    cat >"$deb_root/DEBIAN/control" <<EOF
Package: $package_name
Version: 1.2.3-1
Section: net
Priority: optional
Architecture: $package_architecture
Maintainer: Nextcloud Native
Description: Repository integration fixture
EOF
    dpkg-deb --build --root-owner-group \
        "$deb_root" "$packages/${package_name}_1.2.3-1_${package_architecture}.deb" >/dev/null
done

while IFS= read -r -d '' package; do
    package_name="$(dpkg-deb --field "$package" Package)"
    mv -- "$package" "$packages/retained-$package_name.deb"
done < <(find "$packages" -maxdepth 1 -type f -name '*.deb' -print0)

rpm_top="$temporary/rpmbuild"
mkdir -p "$rpm_top/BUILD" "$rpm_top/BUILDROOT" "$rpm_top/RPMS" \
    "$rpm_top/SOURCES" "$rpm_top/SPECS" "$rpm_top/SRPMS"
cp "$fixture_metadata" "$rpm_top/SOURCES/dev.obiente.nextcloudnative.metainfo.xml"
for package_name in nextcloudnative nextcloud-native-vfs; do
    spec="$rpm_top/SPECS/$package_name.spec"
    extra_install=""
    extra_files=""
    if [[ "$package_name" == nextcloudnative ]]; then
        extra_install=$'mkdir -p %{buildroot}/usr/share/metainfo\ninstall -m 0644 %{_sourcedir}/dev.obiente.nextcloudnative.metainfo.xml %{buildroot}/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml'
        extra_files=/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml
    fi
    package_architecture=x86_64
    if [[ "$package_name" == nextcloud-native-vfs ]]; then
        package_architecture=noarch
    fi
    cat >"$spec" <<EOF
Name: $package_name
Version: 1.2.3
Release: 1
Summary: Repository integration fixture
License: AGPL-3.0-or-later
BuildArch: $package_architecture

%description
Repository integration fixture.

%install
mkdir -p %{buildroot}/usr/share/$package_name
printf 'fixture\\n' >%{buildroot}/usr/share/$package_name/test.txt
$extra_install

%files
/usr/share/$package_name/test.txt
$extra_files
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
gpg --batch --pinentry-mode loopback --passphrase '' \
    --quick-add-key "$fingerprint" rsa2048 sign 0 >/dev/null 2>&1
signing_subkey_fingerprint="$(
    gpg --batch --with-colons --list-secret-keys "$fingerprint" |
        awk -F: '$1 == "ssb" { subkey = 1; next }
            subkey && $1 == "fpr" { print toupper($10); exit }'
)"
test -n "$signing_subkey_fingerprint"
export NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT="$fingerprint"

output="$temporary/repository"
"$project_root/tools/build-linux-package-repositories.sh" \
    "$packages" "$output" prerelease https://packages.example.invalid >/dev/null

grep -Fxq 'Package: nextcloudnative' \
    "$output/apt/dists/prerelease/main/binary-amd64/Packages"
grep -Fxq 'Package: nextcloud-native-vfs' \
    "$output/apt/dists/prerelease/main/binary-amd64/Packages"
grep -Fq 'Filename: pool/main/n/nextcloudnative/retained-nextcloudnative.deb' \
    "$output/apt/dists/prerelease/main/binary-amd64/Packages"
grep -Fxq 'Architectures: amd64' "$output/apt/dists/prerelease/Release"
grep -Eq '^Valid-Until: .+ \+0000$' "$output/apt/dists/prerelease/Release"
python3 - "$output/apt/dists/prerelease/Release" <<'PY'
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import pathlib
import sys

fields = {}
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if ": " in line:
        key, value = line.split(": ", 1)
        fields[key] = value
valid_until = parsedate_to_datetime(fields["Valid-Until"])
remaining = valid_until - datetime.now(timezone.utc)
assert 6 * 24 * 60 * 60 < remaining.total_seconds() <= 7 * 24 * 60 * 60
PY
grep -Fq 'main/dep11/Components-amd64.yml.gz' \
    "$output/apt/dists/prerelease/Release"
gzip --decompress --stdout \
    "$output/apt/dists/prerelease/main/dep11/Components-amd64.yml.gz" |
    grep -Fq 'dev.obiente.nextcloudnative'
gzip --decompress --stdout \
    "$output/apt/dists/prerelease/main/dep11/Components-amd64.yml.gz" |
    grep -Fq '1.2.3'
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
grep -Fq 'baseurl=https://packages.example.invalid/rpm/prerelease/$basearch' \
    "$output/nextcloud-native.repo"
grep -Fq 'gpgkey=file:///etc/pki/rpm-gpg/NEXTCLOUD-NATIVE-REPOSITORY' \
    "$output/nextcloud-native.repo"
gzip --decompress --stdout "$output/rpm/prerelease/x86_64/repodata/primary.xml.gz" |
    grep -Fq '<name>nextcloudnative</name>'
gzip --decompress --stdout "$output/rpm/prerelease/x86_64/repodata/primary.xml.gz" |
    grep -Fq '<name>nextcloud-native-vfs</name>'
if [[ -e "$output/rpm/prerelease/noarch" || -e "$output/rpm/x86_64" ]]; then
    printf 'Architecture-independent RPMs must be published in concrete repositories.\n' >&2
    exit 1
fi
grep -Fq 'type="appstream"' "$output/rpm/prerelease/x86_64/repodata/repomd.xml"
rpm_appstream_href="$(python3 - "$output/rpm/prerelease/x86_64/repodata/repomd.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

namespace = {"repo": "http://linux.duke.edu/metadata/repo"}
root = ET.parse(sys.argv[1]).getroot()
entries = root.findall("repo:data[@type='appstream']/repo:location", namespace)
assert len(entries) == 1
print(entries[0].attrib["href"])
PY
)"
[[ "$rpm_appstream_href" =~ ^repodata/[A-Za-z0-9._-]+\.gz$ ]]
rpm_appstream_catalog="$output/rpm/prerelease/x86_64/$rpm_appstream_href"
gzip --decompress --stdout "$rpm_appstream_catalog" |
    grep -Fq '<id>dev.obiente.nextcloudnative</id>'
gzip --decompress --stdout "$rpm_appstream_catalog" |
    grep -Fq '<release version="1.2.3"'
gpg --batch --verify \
    "$output/rpm/prerelease/x86_64/repodata/repomd.xml.asc" \
    "$output/rpm/prerelease/x86_64/repodata/repomd.xml" >/dev/null 2>&1

rpm_database="$temporary/rpm-database"
mkdir -p "$rpm_database"
rpm --define "_dbpath $rpm_database" --import "$output/keys/nextcloud-native.asc"
while IFS= read -r -d '' package; do
    rpm --define "_dbpath $rpm_database" --checksig "$package" |
        grep -Fq 'signatures OK'
done < <(find "$output/rpm" -type f -name '*.rpm' -print0)

test -s "$output/SHA256SUMS"
nightly_output="$temporary/nightly-repository"
"$project_root/tools/build-linux-package-repositories.sh" \
    "$packages" "$nightly_output" nightly https://packages.example.invalid >/dev/null
test -d "$nightly_output/rpm/nightly/x86_64/repodata"
if [[ -e "$nightly_output/rpm/prerelease" ]]; then
    printf 'RPM repository output leaked across release channels.\n' >&2
    exit 1
fi
grep -Fq 'baseurl=https://packages.example.invalid/rpm/nightly/$basearch' \
    "$nightly_output/nextcloud-native.repo"
subkey_output="$temporary/subkey-repository"
if NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT="$signing_subkey_fingerprint" \
    "$project_root/tools/build-linux-package-repositories.sh" \
    "$packages" "$subkey_output" prerelease https://packages.example.invalid \
    >"$temporary/subkey-output" 2>&1; then
    printf 'Repository builder accepted a signing subkey fingerprint.\n' >&2
    exit 1
fi
grep -Fq 'must identify the primary signing certificate' "$temporary/subkey-output"
if "$project_root/tools/build-linux-package-repositories.sh" \
    "$packages" "$output" prerelease https://packages.example.invalid \
    >"$temporary/reuse-output" 2>&1; then
    printf 'Repository builder accepted an existing output directory.\n' >&2
    exit 1
fi
grep -Fq 'must not already exist' "$temporary/reuse-output"

printf 'Signed APT and RPM repository integration checks passed.\n'
