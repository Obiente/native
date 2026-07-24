#!/usr/bin/env node
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import MarkdownIt from "markdown-it";
import markdownItAnchor from "markdown-it-anchor";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(websiteRoot, "..");
const generatedDirectory = path.join(websiteRoot, "src", "generated");
const publicDirectory = path.join(websiteRoot, "public");

const sources = [
  {
    file: "ROADMAP.md",
    path: "/roadmap/",
    title: "Product and engineering roadmap",
    shortTitle: "Roadmap",
    description:
      "The dependency-driven path from developer preview to a safe, native five-platform Nextcloud client.",
  },
  {
    file: "ADAPTER_ARCHITECTURE.md",
    path: "/architecture/",
    title: "Adaptive app architecture",
    shortTitle: "Architecture",
    description:
      "How verified contracts, semantic inference and reusable native components turn app APIs into useful interfaces.",
  },
  {
    file: "COMPATIBILITY.md",
    path: "/compatibility/",
    title: "App compatibility",
    shortTitle: "Compatibility",
    description:
      "The current integration matrix, tested Nextcloud apps and evidence behind each supported experience.",
  },
  {
    file: "NATIVE_SCHEMA.md",
    path: "/native-schema/",
    title: "Native UI schema",
    shortTitle: "Native schema",
    description:
      "The platform-neutral grammar that maps discovered resources, fields and actions to native components.",
  },
  {
    file: "DYNAMIC_APP_DESCRIPTOR.md",
    path: "/dynamic-apps/",
    title: "Dynamic app descriptor",
    shortTitle: "Dynamic apps",
    description:
      "The validated discovery and execution contract for arbitrary installed Nextcloud applications.",
  },
  {
    file: "PLATFORMS.md",
    path: "/platforms/",
    title: "Platform architecture",
    shortTitle: "Platforms",
    description:
      "How Android, iOS, Windows, macOS and Linux share domain rules while keeping native system integrations.",
  },
  {
    file: "CONTRIBUTING.md",
    path: "/contributing/",
    title: "Contributing",
    shortTitle: "Contributing",
    description:
      "Build requirements, validation commands and contribution guidance for Nextcloud Native.",
  },
  {
    file: "SECURITY.md",
    path: "/security/",
    title: "Security policy",
    shortTitle: "Security",
    description:
      "Private vulnerability reporting and the security boundaries of this early developer preview.",
  },
];
const routeByFile = new Map(sources.map((source) => [source.file, source.path]));

function slugify(value) {
  return value
    .toLowerCase()
    .trim()
    .replace(/<[^>]*>/g, "")
    .replace(/[^\p{Letter}\p{Number}\s-]/gu, "")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-");
}

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
}).use(markdownItAnchor, {
  slugify,
  permalink: markdownItAnchor.permalink.linkInsideHeader({
    symbol: "#",
    placement: "before",
    ariaHidden: true,
  }),
});

const defaultLinkOpen =
  markdown.renderer.rules.link_open ??
  ((tokens, index, options, _environment, renderer) =>
    renderer.renderToken(tokens, index, options));
markdown.renderer.rules.link_open = (tokens, index, options, environment, renderer) => {
  const hrefIndex = tokens[index].attrIndex("href");
  if (hrefIndex >= 0) {
    const href = tokens[index].attrs[hrefIndex][1];
    const [file, fragment] = href.split("#");
    const internalRoute = routeByFile.get(file);
    if (internalRoute) {
      tokens[index].attrs[hrefIndex][1] = fragment ? `${internalRoute}#${fragment}` : internalRoute;
    }
  }
  return defaultLinkOpen(tokens, index, options, environment, renderer);
};

function textOnly(source) {
  return source
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/`([^`]+)`/g, "$1")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\[([^\]]+)]\([^)]*\)/g, "$1")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/[>*_|~-]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function headingsFrom(source) {
  return [...source.matchAll(/^#{2,4}\s+(.+)$/gm)].map((match) => ({
    title: match[1].replace(/[`*_]/g, "").trim(),
    anchor: slugify(match[1]),
  }));
}

await mkdir(generatedDirectory, { recursive: true });

const docs = await Promise.all(
  sources.map(async (source) => {
    const markdownSource = await readFile(path.join(repositoryRoot, source.file), "utf8");
    const html = markdown.render(markdownSource);
    const text = textOnly(markdownSource);
    return {
      ...source,
      html,
      text,
      headings: headingsFrom(markdownSource),
      readingMinutes: Math.max(1, Math.ceil(text.split(/\s+/).length / 220)),
    };
  }),
);

const docsMetadata = docs.map(({ html, text, ...doc }) => doc);
const moduleSource = `// Generated from repository Markdown. Do not edit.\nexport const docs = ${JSON.stringify(docsMetadata, null, 2)};\n`;
await writeFile(path.join(generatedDirectory, "docs.js"), moduleSource);
await writeFile(
  path.join(generatedDirectory, "docs-content.js"),
  `// Generated from repository Markdown. Do not edit.\nexport const docsContent = ${JSON.stringify(docs, null, 2)};\n`,
);

const searchIndex = docs.map(({ html, ...doc }) => doc);
await writeFile(
  path.join(publicDirectory, "search-index.json"),
  `${JSON.stringify(searchIndex)}\n`,
);
