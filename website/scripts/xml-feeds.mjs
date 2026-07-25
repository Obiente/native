export function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;");
}

export function buildSitemap(routes, newsEntries, baseUrl) {
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...routes.map((route) => {
      const post = newsEntries.find((entry) => entry.path === route);
      const lastModified = post ? `<lastmod>${escapeXml(post.lastUpdated)}</lastmod>` : "";
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
    "<title>Nextcloud Native project news</title>",
    `<link>${escapeXml(`${baseUrl}/news/`)}</link>`,
    "<description>Product stories about how Nextcloud Native is taking shape across phones and desktops.</description>",
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
