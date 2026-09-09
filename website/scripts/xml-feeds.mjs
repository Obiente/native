export function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

export function buildSitemap(routes, contentEntries, baseUrl) {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...routes.map((route) => {
      const entry = contentEntries.find((candidate) => candidate.path === route);
      const lastModified = entry?.lastUpdated
        ? `<lastmod>${escapeXml(entry.lastUpdated)}</lastmod>`
        : "";
      return `  <url><loc>${escapeXml(`${baseUrl}${route}`)}</loc>${lastModified}</url>`;
    }),
    "</urlset>",
    "",
  ].join("\n");
}

export function buildRss(newsEntries, baseUrl) {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<rss version="2.0"><channel>',
    "<title>nati.ve project news</title>",
    `<link>${escapeXml(`${baseUrl}/news/`)}</link>`,
    "<description>Dated product and design notes from the nati.ve project.</description>",
    "<language>en</language>",
    ...newsEntries.map((post) => {
      const url = `${baseUrl}${post.path}`;
      return `<item><title>${escapeXml(post.title)}</title>` +
        `<link>${escapeXml(url)}</link><guid>${escapeXml(url)}</guid>` +
        `<pubDate>${escapeXml(new Date(`${post.date}T12:00:00Z`).toUTCString())}</pubDate>` +
        `<description>${escapeXml(post.description)}</description></item>`;
    }),
    "</channel></rss>",
    "",
  ].join("\n");
}
