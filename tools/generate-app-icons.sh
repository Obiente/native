#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_svg="$repository_root/design/app-icon/cloud.svg"
android_res="$repository_root/androidApp/src/main/res"
desktop_res="$repository_root/ui/src/desktopMain/resources"
background="#0D0F13"
svg_density=4096

command -v magick >/dev/null || {
    echo "ImageMagick 7 is required to regenerate application icons." >&2
    exit 1
}

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

magick -size 1024x1024 "canvas:$background" \
    \( -density "$svg_density" -background none "$source_svg" -resize 620x620 \) \
    -gravity center \
    -composite \
    -strip -depth 8 \
    "$temporary_directory/icon-master.png"

declare -A android_sizes=(
    [mipmap-mdpi]=48
    [mipmap-hdpi]=72
    [mipmap-xhdpi]=96
    [mipmap-xxhdpi]=144
    [mipmap-xxxhdpi]=192
)

for density in "${!android_sizes[@]}"; do
    magick "$temporary_directory/icon-master.png" \
        -resize "${android_sizes[$density]}x${android_sizes[$density]}" \
        "$android_res/$density/ic_launcher.png"
done

magick "$temporary_directory/icon-master.png" -resize 512x512 \
    "$desktop_res/nextcloud-native.png"
magick "$temporary_directory/icon-master.png" \
    -define icon:auto-resize=256,128,64,48,32,16 \
    "$desktop_res/nextcloud-native.ico"

echo "Rendered application icons from $source_svg"
