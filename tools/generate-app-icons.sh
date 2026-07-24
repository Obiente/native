#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_svg="$repository_root/design/app-icon/cloud.svg"
android_res="$repository_root/androidApp/src/main/res"
desktop_res="$repository_root/ui/src/desktopMain/resources"
website_public="$repository_root/website/public"
github_assets="$repository_root/.github"
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

mkdir -p "$website_public" "$github_assets"
cp "$source_svg" "$website_public/cloud.svg"
cp "$source_svg" "$website_public/favicon.svg"

declare -A website_sizes=(
    [favicon-32.png]=32
    [apple-touch-icon.png]=180
    [icon-192.png]=192
    [icon-512.png]=512
)

for filename in "${!website_sizes[@]}"; do
    size="${website_sizes[$filename]}"
    magick "$temporary_directory/icon-master.png" \
        -resize "${size}x${size}" \
        "$website_public/$filename"
done

magick -size 1280x640 "canvas:$background" \
    \( "$temporary_directory/icon-master.png" -resize 320x320 \) \
    -gravity west -geometry +92+0 -composite \
    -font "Noto-Sans-SemiBold" -fill "#F7F5FA" -pointsize 72 \
    -gravity northwest -annotate +480+220 "Nextcloud Native" \
    -font "Noto-Sans-Regular" -fill "#A8A6B0" -pointsize 31 \
    -annotate +484+330 "Your cloud. One native experience." \
    -strip -depth 8 \
    "$temporary_directory/social-preview.png"

cp "$temporary_directory/social-preview.png" "$website_public/social-preview.png"
cp "$temporary_directory/social-preview.png" "$github_assets/social-preview.png"

echo "Rendered application and website icons from $source_svg"
