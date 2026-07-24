#!/usr/bin/env node
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const template = await readFile(path.join(root, "dist", "index.html"), "utf8");
const serverEntry = await import(
  `${pathToFileURL(path.join(root, "dist-ssr", "entry-server.js")).href}?t=${Date.now()}`
);

for (const route of serverEntry.routes) {
  const rendered = await serverEntry.render(route);
  const state = `<script>window.__NEXTCLOUD_NATIVE_SITE__=${JSON.stringify(rendered.props).replaceAll("<", "\\u003c")}</script>`;
  const page = template
    .replace(/<!--app-head-start-->[\s\S]*?<!--app-head-end-->/, rendered.head)
    .replace("<!--app-html-->", rendered.html)
    .replace("<!--app-state-->", state);
  const outputDirectory =
    route === "/" ? path.join(root, "dist") : path.join(root, "dist", route);
  await mkdir(outputDirectory, { recursive: true });
  await writeFile(path.join(outputDirectory, "index.html"), page);
}

const baseUrl = "https://nc-native.obiente.dev";
const sitemap = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
  ...serverEntry.routes.map((route) => {
    const post = serverEntry.newsEntries.find((entry) => entry.path === route);
    return `  <url><loc>${baseUrl}${route}</loc>${post ? `<lastmod>${post.date}</lastmod>` : ""}</url>`;
  }),
  "</urlset>",
  "",
].join("\n");
await writeFile(path.join(root, "dist", "sitemap.xml"), sitemap);
const rss = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<rss version="2.0"><channel>',
  "<title>Nextcloud Native project news</title>",
  `<link>${baseUrl}/news/</link>`,
  "<description>Engineering notes and product decisions from Nextcloud Native.</description>",
  "<language>en</language>",
  ...serverEntry.newsEntries.map(
    (post) =>
      `<item><title>${post.title.replaceAll("&", "&amp;")}</title>` +
      `<link>${baseUrl}${post.path}</link><guid>${baseUrl}${post.path}</guid>` +
      `<pubDate>${new Date(`${post.date}T12:00:00Z`).toUTCString()}</pubDate>` +
      `<description>${post.description.replaceAll("&", "&amp;")}</description></item>`,
  ),
  "</channel></rss>",
  "",
].join("\n");
await writeFile(path.join(root, "dist", "news.xml"), rss);
await rm(path.join(root, "dist-ssr"), { recursive: true, force: true });

console.log(`Prerendered ${serverEntry.routes.length} crawlable routes.`);
