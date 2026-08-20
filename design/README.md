# Design assets

This directory contains the checked-in source for Nextcloud Native's
application icon. Maintained synthetic product captures live under
`website/public/screenshots/`. Temporary comparisons, review exports, and
visual QA output are local artifacts and must not be committed.

The production glyph is `NextcloudIcons.Cloud`, the existing outlined Material
cloud used beside "Nextcloud Native" in the desktop workspace. The matching
source asset is `design/app-icon/cloud.svg`, derived from the Apache-2.0
licensed Material Icons asset shipped by the pinned Compose icon dependency.
Its charcoal background and lavender foreground use the dark theme's
`Charcoal` and `Lavender` tokens.

Android adaptive launchers use a centered vector drawable so the glyph remains
sharp at every density and launcher mask. `tools/generate-app-icons.sh` renders
the checked-in SVG at high resolution for legacy Android launcher PNGs and the
desktop PNG/ICO packages. The SVG remains the authoritative source.
