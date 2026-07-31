#!/usr/bin/env bash
set -euo pipefail

package_directory="${1:?RPM package directory is required.}"
arguments_file="${2:?jpackage arguments file is required.}"
resource_directory="${3:?jpackage resource directory is required.}"
generated_resources="${4:?Generated jpackage resources are required.}"
jpackage="${5:?jpackage executable is required.}"
app_image="${6:?jpackage application image is required.}"

[[ -d "$package_directory" ]]
[[ -f "$arguments_file" ]]
[[ -d "$generated_resources" ]]
[[ -x "$jpackage" ]]
[[ -x "$app_image/bin/NextcloudNative" ]]
[[ -f "$app_image/lib/app/.jpackage.xml" ]]
grep -Fxq -- '--type' "$arguments_file"
grep -Fxq -- '"rpm"' "$arguments_file"
grep -Fxq -- '--resource-dir' "$arguments_file"
grep -Fxq -- "\"$resource_directory\"" "$arguments_file"
grep -Fxq -- '--dest' "$arguments_file"
grep -Fxq -- "\"$package_directory\"" "$arguments_file"

argument_value() {
    local option="$1"
    awk -v option="$option" '
        $0 == option {
            if (getline <= 0) exit 2
            sub(/^"/, "")
            sub(/"$/, "")
            print
            found = 1
            exit
        }
        END { if (!found) exit 1 }
    ' "$arguments_file"
}

name="$(argument_value --name)"
description="$(argument_value --description)"
copyright="$(argument_value --copyright)"
version="$(argument_value --app-version)"
vendor="$(argument_value --vendor)"
license="$(argument_value --license-file)"
icon="$(argument_value --icon)"
category="$(argument_value --linux-app-category)"
rpm_license="$(argument_value --linux-rpm-license-type)"
[[ "$name" == "NextcloudNative" ]]
[[ -f "$license" && -f "$icon" ]]

mapfile -d '' packages < <(find "$package_directory" -maxdepth 1 -type f -name '*.rpm' -print0)
if [[ "${#packages[@]}" -ne 1 ]]; then
    printf 'Expected exactly one RPM package in %s, found %s.\n' \
        "$package_directory" "${#packages[@]}" >&2
    exit 1
fi

rm -rf -- "$resource_directory"
install -d -m 0755 "$resource_directory"
cp -a -- "$generated_resources"/. "$resource_directory"/
rm -- "${packages[0]}"
"$jpackage" \
    --type rpm \
    --app-image "$app_image" \
    --resource-dir "$resource_directory" \
    --dest "$package_directory" \
    --name "$name" \
    --description "$description" \
    --copyright "$copyright" \
    --app-version "$version" \
    --vendor "$vendor" \
    --license-file "$license" \
    --icon "$icon" \
    --linux-shortcut \
    --linux-app-category "$category" \
    --linux-rpm-license-type "$rpm_license"

mapfile -d '' rebuilt < <(find "$package_directory" -maxdepth 1 -type f -name '*.rpm' -print0)
if [[ "${#rebuilt[@]}" -ne 1 ]]; then
    printf 'Expected one rebuilt RPM package in %s, found %s.\n' \
        "$package_directory" "${#rebuilt[@]}" >&2
    exit 1
fi
