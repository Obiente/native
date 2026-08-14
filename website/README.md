# Nextcloud Native website

The project homepage is a provider-neutral Vue site. Its production build
prerenders the homepage and every selected repository Markdown document to
plain HTML, then emits a sitemap and a small client bundle for search and
interactive previews.

Project updates live in `content/news/`. Each Markdown file has strict
frontmatter and becomes a prerendered article, search result, sitemap entry,
structured-data article, and RSS item.

## Platform guides

Task guides live in `content/guides/` and are published under a platform path,
for example `/guides/android/offline-files/` or
`/guides/windows/cloud-files/`. Every guide declares one primary platform and
one device class. Shared desktop guides may list both Linux and Windows, but a
guide must split when installation, credential storage, filesystem behavior,
permissions, scheduling, or recovery differs between operating systems.

Guide claims must describe the current implementation, not a roadmap target.
Each step names a deterministic `guide-*` capture scenario rendered from the
real Compose UI with synthetic data. The Android, desktop, Linux, and Windows
hub pages are prerendered, canonical, included in the sitemap, and link to the
matching guides. macOS and iOS remain availability statements until supported
authenticated products exist; do not manufacture setup instructions for an
artifact that cannot complete the workflow.

## Local development

```bash
npm ci
npm run dev
```

The content generator reads the canonical Markdown files from the repository
root before starting Vite. Changes to those files require restarting the local
server or running `npm run content`.

The roadmap page also reads the public GitHub project views and milestones at
build time. It never needs or embeds a GitHub token. If GitHub is unavailable
or rate-limited, generation falls back to the repository-owned workstream
links so local and production builds remain deterministic.

## Production build

```bash
npm ci
npm run build
```

The self-contained static output is written to `website/dist/`. It can be
served by any static web server. Directory index support must remain enabled so
routes such as `/roadmap/` resolve to their prerendered `index.html`.

## News, release notes, and changelog

These surfaces intentionally serve different readers:

- `content/news/*.md` contains long, visual product stories for people who use
  Nextcloud and contributors who want the implementation context. News is
  living documentation: keep its `lastUpdated` date, screenshots, capability
  boundaries, and UI wording current when the product changes.
- per-version release notes are short installer-facing summaries and
  limitations under `/releases/`.
- `changes/unreleased/*.md` provides the live user-facing entries contributed
  by individual pull requests without a shared-file merge conflict.
- the dedicated `/changelog/` page combines those validated fragments with the
  immutable release history in the canonical root `CHANGELOG.md`.

The content build never manufactures changelog entries from news or pull
request titles. Changelog entries and per-release notes are immutable
historical records; later clarifications belong in a new entry or release, not
a rewritten past artifact.

## Product screenshots

The gallery is captured from the real Compose UI using the compile-time
`MarketingDemoFixture`. The fixture contains synthetic names and `.invalid`
addresses, and the capture entry point never constructs account, session,
network, cache, or media services.

```bash
tools/capture-marketing-screenshots.sh
```

This renders every entry in the Compose capture registry and writes its image
and metadata to the capture manifest. Adding a registry entry is sufficient;
Gradle, the shell wrapper, website tests, and the gallery do not carry a second
filename list. Each scenario uses production Compose components and
deterministic synthetic models. The workflow does not use adb, an emulator, a
phone, a Nextcloud account, or network-backed application services.

Production and pull request deployments validate the committed manifest, fully
decode each PNG, and check its dimensions and hash without requiring unrelated
UI source changes to regenerate the catalog:

```bash
npm run --prefix website verify:captures
```

The dedicated `Refresh marketing captures` workflow checks whether the catalog
represents the current capture inputs. Normal build and test jobs do not fail
only because the generated catalog is stale. Run the same freshness gate
locally with:

```bash
npm run --prefix website verify:captures:fresh
```

If the freshness command reports stale inputs, run the capture wrapper with
JDK 21 and review the updated synthetic images. The `/visual-qa/` route lists
scenario, feature, surface, state, platform, viewport, and pixel metadata.
Future scenario entries may also identify the pull request they review.

For same-repository pull requests, the refresh workflow prepares an untrusted
patch without write credentials. A separate default-branch workflow validates
the pull request identity, exact head revision, patch paths, regular-file modes,
and patch size before `obiente-automations[bot]` commits it with a short-lived
token restricted to this repository and `Contents: write`. Forks and Dependabot
remain read-only and must commit stale capture updates themselves.

The canonical Obiente organization avatar lives with the desktop capture
resources. Content generation copies it into `public/` for static hosting, so
the repository does not carry two independent binaries.

## Container

Build from the repository root because the documentation source lives there:

```bash
docker build -f website/Dockerfile -t nextcloud-native-homepage .
docker run --rm -p 8080:80 nextcloud-native-homepage
```

For a local check, `docker compose -f website/compose.yml up --build` provides
the same image. In production, place the container behind the Obiente reverse
proxy and terminate TLS there.

The container exposes `/api/github-repository` as a cached, same-origin proxy
for the public `Obiente/nc-native` repository metadata. The website uses it to
refresh the displayed star count without a deployment. Nginx refreshes the
upstream response at most once every ten minutes and can serve its last cached
response during temporary GitHub failures. The prerendered count remains the
fallback before the first successful request. Production must therefore allow
outbound HTTPS from the website container to `api.github.com`. The container
derives its upstream resolvers from `/etc/resolv.conf` and resolves GitHub only
when this optional endpoint is requested, so upstream DNS failures do not stop
the static website from starting.

### IndexNow

Production images prepare an IndexNow payload from the canonical sitemap and
publish the protocol verification key at the site root. The production Obiente
Cloud deployment must set `INDEXNOW_PRODUCTION=1`; the submitter is fail-closed
when that variable is absent or has any other value. On container startup, a
background deployment hook waits until the exact static-build fingerprint is
visible at `https://nc-native.obiente.dev` before submitting that build's
crawlable URLs to the global IndexNow endpoint. HTTP 200 and the initial
key-verification HTTP 202 response are recorded as successful for that
container, so an ordinary restart does not notify the same build again.

Do not expose `INDEXNOW_PRODUCTION=1` to pull-request previews. Local and preview
containers leave it unset and exit before contacting IndexNow, even when their
static output happens to match the production website.

The canonical hostname is currently configured as
`https://nc-native.obiente.dev` in the prerender and crawler metadata. Change
that value in `src/entry-server.js`, `scripts/prerender.mjs`,
`public/robots.txt`, and `index.html` together if the final hostname differs.
