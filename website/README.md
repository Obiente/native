# Nextcloud Native website

The project homepage is a provider-neutral Vue site. Its production build
prerenders the homepage and every selected repository Markdown document to
plain HTML, then emits a sitemap and a small client bundle for search and
interactive previews.

Project updates live in `content/news/`. Each Markdown file has strict
frontmatter and becomes a prerendered article, search result, sitemap entry,
structured-data article, and RSS item.

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

## Synthetic product screenshots

The product gallery is generated only from the committed
`screenshots/fixtures/demo-workspace.json` fixture:

```bash
npm run screenshots
npm test
```

The generator has no endpoint, session, environment, cache, or home-directory
input. Guardrails reject credentials, URLs, email-like values, content URIs,
and absolute home paths. Output is restricted to SVG files under
`public/screenshots/`. This keeps the workflow deterministic and prevents a
developer's account, media, or saved Nextcloud state from entering website
artifacts.

## Container

Build from the repository root because the documentation source lives there:

```bash
docker build -f website/Dockerfile -t nextcloud-native-homepage .
docker run --rm -p 8080:80 nextcloud-native-homepage
```

For a local check, `docker compose -f website/compose.yml up --build` provides
the same image. In production, place the container behind the Obiente reverse
proxy and terminate TLS there.

The canonical hostname is currently configured as
`https://nc-native.obiente.dev` in the prerender and crawler metadata. Change
that value in `src/entry-server.js`, `scripts/prerender.mjs`,
`public/robots.txt`, and `index.html` together if the final hostname differs.
