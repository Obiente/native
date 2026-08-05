#!/usr/bin/env node
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { prepareIndexNowArtifacts } from "./indexnow.mjs";
import { buildRss, buildSitemap } from "./xml-feeds.mjs";

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
const sitemap = buildSitemap(serverEntry.routes, serverEntry.sitemapEntries, baseUrl);
await writeFile(path.join(root, "dist", "sitemap.xml"), sitemap);
const rss = buildRss(serverEntry.newsEntries, baseUrl);
await writeFile(path.join(root, "dist", "news.xml"), rss);
const indexNow = await prepareIndexNowArtifacts({
  distDirectory: path.join(root, "dist"),
  privateDirectory: path.join(root, "dist-indexnow"),
  sitemap,
  siteUrl: baseUrl,
});
await rm(path.join(root, "dist-ssr"), { recursive: true, force: true });

console.log(`Prerendered ${serverEntry.routes.length} crawlable routes.`);
console.log(`Prepared ${indexNow.urlCount} IndexNow URLs for deployment ${indexNow.fingerprint}.`);
