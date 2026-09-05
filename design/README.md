# nati.ve identity

nati.ve is the product name. Use lowercase spelling, including the dot.
The tagline is "Your cloud, natively." The repository is
[obiente/native](https://github.com/obiente/native) and the website is
[nati.ve](https://nati.ve).

## Editable vector masters

- [Symbol](app-icon/native-mark.svg): charcoal arch with a mint dot.
- [Wordmark](brand/wordmark.svg): editable text and vector symbol.
- [Repository banner](brand/banner.svg): charcoal, mint and blue.

These SVG files are the authoritative masters. Their named elements remain
editable. The text wordmark uses Inter with Arial fallback; the banner wordmark
uses paths outlined from the bundled Inter at weight 750, so its exports do not
depend on installed fonts. The website uses SVG and
Compose renders the same path geometry as an ImageVector. Android adaptive
launchers use a vector drawable. PNG/ICO exports are only for platforms and
social preview consumers that require raster images. Product screenshots
remain raster captures of the real UI.

Regenerate all derived assets after changing the masters:

```sh
npm ci --prefix website
node tools/generate-native-icons.mjs
```

The generator uses the locked resvg dependency and exports the native vector,
Android vector and legacy launchers, desktop icons, website icons and social
preview images. Edit the SVG master rather than generated path data.
The generated [social preview vector](brand/social-preview.svg) frames the banner
at 1280 by 640, matching the website's Open Graph dimensions. Rasterization
disables system fonts; preserve the banner's wordmark as vector paths.

## Shared visual language

Use neutral surfaces, lightweight dividers, mint selections and blue file
icons. Status also needs text or an icon; color alone never implies sync.
Bright mint buttons use dark text. The app keeps readable system typography;
the website uses bundled Inter. Shared semantic colors are in
`NextcloudTheme.kt`; typography, spacing and corners in `NextcloudTokens.kt`.

| Role | Light | Dark |
| --- | --- | --- |
| Background | `#F5F7F8` | `#101418` |
| Foreground | `#101820` | `#F5F7F8` |
| Primary | `#087D62` | `#52E0B4` |
| Selection | `#DFF7EF` | `#153E34` |
| File icon | `#2469BE` | `#75B4FF` |

The brand mark is distinct from functional cloud and sync icons. Public
screenshots come from `tools/capture-marketing-screenshots.sh`, with synthetic
fixtures. Concept images must never substitute for product screenshots.
On Windows, stop the Vite preview watcher before capturing: an open directory
watch can prevent the renderer from replacing the screenshot directory.

## App interaction conventions

Desktop file rows and tiles select on a single click and open on a double
click. Touch opens on a tap; long press and the visible action button expose
the file menu. Selection is exposed to accessibility services. Metadata
columns yield to filenames when the browser pane narrows, including when
Details is open. Details scrolls independently and has a close control.

Search inputs include a clear action and expand with text scaling. On phones,
the search scope sits outside the input to preserve typing space. Home quick
actions are action buttons, not selection filters. Keep offline status text
visible and retain authoritative sharing and download capability checks.

## Upgrade and domain continuity

Preserve `dev.obiente.nextcloudnative`, its development suffix, executable
filenames, storage paths, credential keys and installer identities. These are
compatibility contracts for existing installations. Historical release notes
retain the name used at publication.

Website canonical metadata uses nati.ve. Deployment must provision TLS and
route the new domain to the existing website, including download and GitHub
proxy endpoints. Retain the old domain for installed clients and old links.
The frozen news-feed-v1 contract retains its legacy URLs; new clients accept
both canonical article/image origins. Update manifest fields and asset names
remain unchanged. DNS, deployment, GitHub social-preview settings and release
publication are separate operational steps.
New clients load the feed and images directly from nati.ve. The legacy feed
and screenshot endpoints remain available without a redirect for installed
clients; other old website URLs redirect permanently.

Release scripts use the renamed repository for GitHub API operations, while
`tools/release-repository.sh` retains `Obiente/nc-native` in v1 manifest URLs
for installed clients. New Android and desktop clients accept both narrowly
scoped repository URL prefixes without changing the original download URLs.
Network requests map those legacy release URLs to the renamed GitHub repository
before following the existing restricted release-asset redirect.
Already-installed builds that reject GitHub's repository-rename 301 need a
manual upgrade from the canonical release page; new metadata cannot change
the downloader code in those builds.

New release titles and generated notes use nati.ve. APT Origin/Label values,
calendar PRODID values, and installed filesystem identities retain their legacy
strings for compatibility. Package-host examples are placeholders until a
separate package origin is deployed.
