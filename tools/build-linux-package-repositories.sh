#!/usr/bin/env bash
set -euo pipefail

package_directory="${1:?Package directory is required.}"
output_directory="${2:?Output directory is required.}"
channel="${3:?Repository channel is required.}"
repository_url="${4:?Public repository URL is required.}"
signing_fingerprint="${NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT:-}"

if [[ "$#" -ne 4 ]]; then
    printf 'Usage: %s PACKAGE_DIRECTORY OUTPUT_DIRECTORY CHANNEL REPOSITORY_URL\n' "$0" >&2
    exit 2
fi
if [[ ! -d "$package_directory" ]]; then
    printf 'Package directory does not exist: %s\n' "$package_directory" >&2
    exit 2
fi
if [[ -e "$output_directory" ]]; then
    printf 'Output directory must not already exist: %s\n' "$output_directory" >&2
    exit 2
fi
if [[ ! "$channel" =~ ^(nightly|prerelease|stable)$ ]]; then
    printf 'Repository channel must be nightly, prerelease, or stable.\n' >&2
    exit 2
fi
if [[ ! "$repository_url" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$ ]]; then
    printf 'Repository URL must be an HTTPS URL without query parameters or fragments.\n' >&2
    exit 2
fi
repository_url="${repository_url%/}"
if [[ ! "$signing_fingerprint" =~ ^[A-Fa-f0-9]{40}$ ]]; then
    printf 'NC_LINUX_REPOSITORY_SIGNING_FINGERPRINT must contain a full OpenPGP fingerprint.\n' >&2
    exit 2
fi
signing_fingerprint="${signing_fingerprint^^}"

for required_command in \
    appstreamcli apt-ftparchive cpio createrepo_c dpkg-deb gpg gzip \
    modifyrepo_c python3 rpm rpm2cpio rpmsign; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf '%s is required to build the Linux package repositories.\n' "$required_command" >&2
        exit 2
    fi
done
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT

mapfile -d '' deb_packages < <(
    find "$package_directory" -maxdepth 1 -type f -name '*.deb' -print0 | sort -z
)
mapfile -d '' rpm_packages < <(
    find "$package_directory" -maxdepth 1 -type f -name '*.rpm' -print0 | sort -z
)
if [[ "${#deb_packages[@]}" -eq 0 || "${#rpm_packages[@]}" -eq 0 ]]; then
    printf 'At least one regular DEB and one regular RPM package are required.\n' >&2
    exit 2
fi

available_fingerprints="$(
    gpg --batch --with-colons --list-secret-keys "$signing_fingerprint" 2>/dev/null |
        awk -F: '$1 == "fpr" { print toupper($10) }'
)"
if ! grep -Fxq "$signing_fingerprint" <<<"$available_fingerprints"; then
    printf 'The configured OpenPGP signing key is not available in the active GnuPG home.\n' >&2
    exit 2
fi

mkdir -p "$output_directory/keys"
gpg --batch --armor --export "$signing_fingerprint" \
    >"$output_directory/keys/nextcloud-native.asc"
if [[ ! -s "$output_directory/keys/nextcloud-native.asc" ]]; then
    printf 'The repository signing certificate could not be exported.\n' >&2
    exit 1
fi
printf '%s\n' "$signing_fingerprint" \
    >"$output_directory/keys/nextcloud-native.fingerprint"

apt_root="$output_directory/apt"
declare -A apt_architectures=()
for package in "${deb_packages[@]}"; do
    package_name="$(dpkg-deb --field "$package" Package)"
    package_version="$(dpkg-deb --field "$package" Version)"
    package_architecture="$(dpkg-deb --field "$package" Architecture)"
    if [[ ! "$package_name" =~ ^[a-z0-9][a-z0-9+.-]*$ ]] ||
        [[ -z "$package_version" ]] ||
        [[ ! "$package_architecture" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
        printf 'DEB metadata is invalid in %s.\n' "$package" >&2
        exit 1
    fi
    package_filename="$(basename "$package")"
    if [[ ! "$package_filename" =~ ^[A-Za-z0-9][A-Za-z0-9+._~-]*\.deb$ ]]; then
        printf 'DEB filename is not repository-safe: %s\n' "$package_filename" >&2
        exit 1
    fi
    pool_directory="$apt_root/pool/main/${package_name:0:1}/$package_name"
    mkdir -p "$pool_directory"
    cp -- "$package" "$pool_directory/$package_filename"
    if [[ "$package_architecture" != all ]]; then
        apt_architectures["$package_architecture"]=1
    fi
done

apt_architecture_names=()
if [[ "${#apt_architectures[@]}" -gt 0 ]]; then
    mapfile -t apt_architecture_names < <(printf '%s\n' "${!apt_architectures[@]}" | sort)
fi
if [[ "${#apt_architecture_names[@]}" -eq 0 ]]; then
    printf 'At least one architecture-specific DEB package is required.\n' >&2
    exit 1
fi
for architecture in "${apt_architecture_names[@]}"; do
    index_directory="$apt_root/dists/$channel/main/binary-$architecture"
    mkdir -p "$index_directory"
    (
        cd "$apt_root"
        apt-ftparchive --arch "$architecture" packages pool
    ) >"$index_directory/Packages"
    gzip --best --no-name --keep "$index_directory/Packages"

    mapfile -d '' architecture_packages < <(
        find "$apt_root/pool/main/n/nextcloudnative" -maxdepth 1 -type f -name '*.deb' -print0 |
            sort -zV
    )
    catalog_arguments=()
    package_index=0
    for package in "${architecture_packages[@]}"; do
        package_architecture="$(dpkg-deb --field "$package" Architecture)"
        if [[ "$package_architecture" != "$architecture" && "$package_architecture" != all ]]; then
            continue
        fi
        package_version="$(dpkg-deb --field "$package" Version)"
        appstream_version="${package_version%-*}"
        package_root="$temporary/deb-$architecture-$package_index"
        dpkg-deb --extract "$package" "$package_root"
        metadata="$package_root/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml"
        if [[ ! -f "$metadata" ]]; then
            printf 'DEB package is missing Nextcloud Native AppStream metadata: %s\n' \
                "$package" >&2
            exit 1
        fi
        catalog_arguments+=("$appstream_version" "$metadata")
        package_index=$((package_index + 1))
    done
    if [[ "${#catalog_arguments[@]}" -eq 0 ]]; then
        printf 'No Nextcloud Native DEB package is available for %s.\n' "$architecture" >&2
        exit 1
    fi
    catalog_xml="$temporary/apt-$architecture.xml"
    python3 "$project_root/tools/build-appstream-catalog.py" \
        "$catalog_xml" "nextcloud-native-$channel" "${catalog_arguments[@]}"
    appstreamcli validate --no-net "$catalog_xml"
    dep11_directory="$apt_root/dists/$channel/main/dep11"
    mkdir -p "$dep11_directory"
    appstreamcli convert --format=yaml \
        "$catalog_xml" "$dep11_directory/Components-$architecture.yml"
    gzip --best --no-name "$dep11_directory/Components-$architecture.yml"
done

release_directory="$apt_root/dists/$channel"
architectures="$(IFS=' '; echo "${apt_architecture_names[*]}")"
(
    cd "$apt_root"
    apt-ftparchive \
        -o "APT::FTPArchive::Release::Origin=Nextcloud Native" \
        -o "APT::FTPArchive::Release::Label=Nextcloud Native" \
        -o "APT::FTPArchive::Release::Suite=$channel" \
        -o "APT::FTPArchive::Release::Codename=$channel" \
        -o "APT::FTPArchive::Release::Architectures=$architectures" \
        -o "APT::FTPArchive::Release::Components=main" \
        -o "APT::FTPArchive::Release::Description=Native Nextcloud client packages" \
        -o "APT::FTPArchive::Release::ValidTime=604800" \
        release "dists/$channel"
) >"$release_directory/Release"
gpg --batch --yes --local-user "$signing_fingerprint" --digest-algo SHA256 \
    --armor --detach-sign --output "$release_directory/Release.gpg" \
    "$release_directory/Release"
gpg --batch --yes --local-user "$signing_fingerprint" --digest-algo SHA256 \
    --clearsign --output "$release_directory/InRelease" "$release_directory/Release"

cat >"$output_directory/nextcloud-native.sources" <<EOF
Types: deb
URIs: $repository_url/apt
Suites: $channel
Components: main
Architectures: $architectures
Signed-By: /etc/apt/keyrings/nextcloud-native.asc
EOF

rpm_root="$output_directory/rpm"
declare -A rpm_architectures=()
declare -A rpm_package_architectures=()
for package in "${rpm_packages[@]}"; do
    package_name="$(rpm -qp --queryformat '%{NAME}' "$package")"
    package_architecture="$(rpm -qp --queryformat '%{ARCH}' "$package")"
    if [[ ! "$package_name" =~ ^[A-Za-z0-9][A-Za-z0-9+._-]*$ ]] ||
        [[ ! "$package_architecture" =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]]; then
        printf 'RPM metadata is invalid in %s.\n' "$package" >&2
        exit 1
    fi
    package_filename="$(basename "$package")"
    if [[ ! "$package_filename" =~ ^[A-Za-z0-9][A-Za-z0-9+._~-]*\.rpm$ ]]; then
        printf 'RPM filename is not repository-safe: %s\n' "$package_filename" >&2
        exit 1
    fi
    rpm_package_architectures["$package"]="$package_architecture"
    if [[ "$package_architecture" != noarch ]]; then
        rpm_architectures["$package_architecture"]=1
    fi
done

rpm_architecture_names=()
if [[ "${#rpm_architectures[@]}" -gt 0 ]]; then
    mapfile -t rpm_architecture_names < <(printf '%s\n' "${!rpm_architectures[@]}" | sort)
fi
if [[ "${#rpm_architecture_names[@]}" -eq 0 ]]; then
    printf 'At least one architecture-specific RPM package is required.\n' >&2
    exit 1
fi
for architecture in "${rpm_architecture_names[@]}"; do
    architecture_root="$rpm_root/$architecture"
    mkdir -p "$architecture_root/Packages"
    for package in "${rpm_packages[@]}"; do
        package_architecture="${rpm_package_architectures[$package]}"
        if [[ "$package_architecture" != "$architecture" && "$package_architecture" != noarch ]]; then
            continue
        fi
        package_output="$architecture_root/Packages/$(basename "$package")"
        cp -- "$package" "$package_output"
        rpmsign \
            --define "__gpg $(command -v gpg)" \
            --define "_openpgp_sign_id $signing_fingerprint" \
            --define "_gpg_name $signing_fingerprint" \
            --define "_gpg_path ${GNUPGHOME:-$HOME/.gnupg}" \
            --addsign "$package_output"
    done
    createrepo_c \
        --checksum sha256 \
        --general-compress-type=gz \
        --simple-md-filenames \
        "$architecture_root"
    mapfile -d '' architecture_packages < <(
        find "$architecture_root/Packages" -maxdepth 1 -type f -name '*.rpm' -print0 |
            sort -zV
    )
    catalog_arguments=()
    package_index=0
    for package in "${architecture_packages[@]}"; do
        if [[ "$(rpm -qp --queryformat '%{NAME}' "$package")" != nextcloudnative ]]; then
            continue
        fi
        package_version="$(rpm -qp --queryformat '%{VERSION}' "$package")"
        metadata="$temporary/rpm-$architecture-$package_index.metainfo.xml"
        rpm2cpio "$package" |
            cpio -i --quiet --to-stdout \
                ./usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml \
                >"$metadata"
        if [[ ! -s "$metadata" ]]; then
            printf 'RPM package is missing Nextcloud Native AppStream metadata: %s\n' \
                "$package" >&2
            exit 1
        fi
        catalog_arguments+=("$package_version" "$metadata")
        package_index=$((package_index + 1))
    done
    if [[ "${#catalog_arguments[@]}" -eq 0 ]]; then
        printf 'No Nextcloud Native RPM package is available for %s.\n' "$architecture" >&2
        exit 1
    fi
    catalog_xml="$temporary/rpm-$architecture.xml"
    python3 "$project_root/tools/build-appstream-catalog.py" \
        "$catalog_xml" "nextcloud-native-$channel" "${catalog_arguments[@]}"
    appstreamcli validate --no-net "$catalog_xml"
    modifyrepo_c \
        --compress-type=gz \
        --mdtype=appstream \
        "$catalog_xml" \
        "$architecture_root/repodata"
    gpg --batch --yes --local-user "$signing_fingerprint" --digest-algo SHA256 \
        --armor --detach-sign --output "$architecture_root/repodata/repomd.xml.asc" \
        "$architecture_root/repodata/repomd.xml"
done

cat >"$output_directory/nextcloud-native.repo" <<EOF
[nextcloud-native-$channel]
name=Nextcloud Native ($channel)
baseurl=$repository_url/rpm/\$basearch
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=$repository_url/keys/nextcloud-native.asc
metadata_expire=6h
EOF

(
    cd "$output_directory"
    find . -type f ! -name SHA256SUMS -print0 |
        sort -z |
        xargs -0 sha256sum >SHA256SUMS
)

printf 'Built signed APT and RPM repositories for %s in %s.\n' \
    "$channel" "$output_directory"
