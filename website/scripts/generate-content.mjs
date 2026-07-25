#!/usr/bin/env node
import { copyFile, mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import MarkdownIt from "markdown-it";
import markdownItAnchor from "markdown-it-anchor";
import {
  normalizeNewsArticleBody,
  parseNewsFrontmatter,
} from "./content-frontmatter.mjs";
import {
  assertValidNativeNewsFeed,
  nativeNewsFeedRevision,
} from "./news-feed-contract.mjs";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(websiteRoot, "..");
const generatedDirectory = path.join(websiteRoot, "src", "generated");
const publicDirectory = path.join(websiteRoot, "public");
const newsDirectory = path.join(websiteRoot, "content", "news");
const changelogFile = path.join(repositoryRoot, "CHANGELOG.md");
const changelogRoute = "/changelog/";
await mkdir(generatedDirectory, { recursive: true });
await mkdir(publicDirectory, { recursive: true });
const canonicalObienteAvatar = path.join(
  repositoryRoot,
  "ui",
  "src",
  "desktopMain",
  "resources",
  "marketing",
  "obiente-avatar.png",
);
await copyFile(canonicalObienteAvatar, path.join(publicDirectory, "obiente-avatar.png"));
const captureManifest = JSON.parse(
  await readFile(
    path.join(publicDirectory, "screenshots", "capture-manifest.json"),
    "utf8",
  ),
);
const captureByImage = new Map(
  captureManifest.captures.map((capture) => [
    `/screenshots/${capture.file}`,
    capture,
  ]),
);
const marketingCaptures = captureManifest.captures.map((capture) => ({
  ...capture,
  path: `/screenshots/${capture.file}`,
}));
await writeFile(
  path.join(generatedDirectory, "captures.js"),
  `// Generated from the Compose capture manifest. Do not edit.\nexport const marketingCaptures = ${JSON.stringify(marketingCaptures, null, 2)};\n`,
);

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
routeByFile.set("CHANGELOG.md", changelogRoute);

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

let changelogSource;
let changelogAvailable = true;
try {
  changelogSource = await readFile(changelogFile, "utf8");
} catch (error) {
  if (error.code !== "ENOENT") throw error;
  changelogAvailable = false;
  changelogSource = [
    "# Changelog",
    "",
    "The first curated release history will appear here with the first public release.",
  ].join("\n");
}
if (
  changelogAvailable &&
  (
    !/^#\s+Changelog\s*$/im.test(changelogSource) ||
    !/^##\s+.+$/m.test(changelogSource) ||
    !/^###\s+(Added|Changed|Deprecated|Removed|Fixed|Security)\s*$/m.test(changelogSource)
  )
) {
  throw new Error(
    "CHANGELOG.md must contain a Changelog title, a release section, and a Keep a Changelog category.",
  );
}
const changelogBody = changelogSource.replace(/^#\s+Changelog\s*\n+/i, "");
const changelogText = textOnly(changelogBody);
const changelog = {
  file: "CHANGELOG.md",
  path: changelogRoute,
  title: "Changelog",
  shortTitle: "Changelog",
  description:
    "Concise Added, Changed, Fixed, and Security records for each Nextcloud Native release.",
  html: markdown.render(changelogBody),
  text: changelogText,
  headings: headingsFrom(changelogBody),
  readingMinutes: Math.max(1, Math.ceil(changelogText.split(/\s+/).length / 220)),
  available: changelogAvailable,
};
await writeFile(
  path.join(generatedDirectory, "changelog.js"),
  `// Generated from the canonical root CHANGELOG.md when available. Do not edit.\nexport const changelog = ${JSON.stringify(changelog, null, 2)};\n`,
);

const newsFiles = (await readdir(newsDirectory))
  .filter((file) => file.endsWith(".md"))
  .sort()
  .reverse();
const news = await Promise.all(
  newsFiles.map(async (file) => {
    const source = await readFile(path.join(newsDirectory, file), "utf8");
    const { metadata, body } = parseNewsFrontmatter(source, file);
    const text = textOnly(body);
    const articleBody = normalizeNewsArticleBody(body, metadata.title);
    const capture = captureByImage.get(metadata.image);
    if (!capture) {
      throw new Error(`${file}: image must reference a declared Compose capture.`);
    }
    return {
      file,
      path: `/news/${metadata.slug}/`,
      title: metadata.title,
      shortTitle: metadata.title,
      description: metadata.description,
      date: metadata.date,
      lastUpdated: metadata.lastUpdated,
      tags: metadata.tags.split(",").map((tag) => tag.trim()).filter(Boolean),
      image: metadata.image,
      imageAlt: metadata.imageAlt,
      imageCaption: metadata.imageCaption,
      imageWidth: capture.width,
      imageHeight: capture.height,
      html: markdown.render(articleBody),
      text,
      headings: headingsFrom(articleBody),
      readingMinutes: Math.max(1, Math.ceil(text.split(/\s+/).length / 220)),
    };
  }),
);
if (new Set(news.map((post) => post.path)).size !== news.length) {
  throw new Error("News slugs must be unique.");
}
await writeFile(
  path.join(generatedDirectory, "news.js"),
  `// Generated from fixture-safe repository news. Do not edit.\nexport const news = ${JSON.stringify(news, null, 2)};\n`,
);
const newsFeedEntries = await Promise.all(
  news.map(async (post) => ({
    id: post.path.slice("/news/".length, -1),
    title: post.title,
    description: post.description,
    publishedDate: post.date,
    lastUpdated: post.lastUpdated,
    tags: post.tags,
    bodyMarkdown: normalizeNewsArticleBody(
      parseNewsFrontmatter(
      // Re-read from the single canonical source rather than reconstructing Markdown from HTML.
        await readFile(path.join(newsDirectory, post.file), "utf8"),
        post.file,
      ).body,
      post.title,
    ),
    webUrl: `https://nc-native.obiente.dev${post.path}`,
    image: {
      url: `https://nc-native.obiente.dev${post.image}`,
      alt: post.imageAlt,
      width: post.imageWidth,
      height: post.imageHeight,
      sha256: createHash("sha256")
        .update(await readFile(path.join(publicDirectory, post.image.slice(1))))
        .digest("hex"),
    },
  })),
);
const hashedNewsFeedEntries = newsFeedEntries.map((entry) => ({
  ...entry,
  contentSha256: createHash("sha256").update(entry.bodyMarkdown).digest("hex"),
}));
const newsFeed = {
  schemaVersion: 1,
  feedRevision: nativeNewsFeedRevision(hashedNewsFeedEntries),
  entries: hashedNewsFeedEntries,
};
const serializedNewsFeed = `${JSON.stringify(newsFeed, null, 2)}\n`;
assertValidNativeNewsFeed(newsFeed, Buffer.byteLength(serializedNewsFeed));
await writeFile(
  path.join(publicDirectory, "news-feed-v1.json"),
  serializedNewsFeed,
);

const searchIndex = [
  ...docs.map(({ html, ...doc }) => ({ ...doc, contentType: "Documentation" })),
  ...news.map(({ html, ...post }) => ({ ...post, contentType: "News" })),
  { ...changelog, html: undefined, contentType: "Changelog" },
];

const githubApiHeaders = {
  Accept: "application/vnd.github+json",
  "X-GitHub-Api-Version": "2026-03-10",
  "User-Agent": "nextcloud-native-website-build",
};
const projectUrl = "https://github.com/orgs/Obiente/projects/4";
const projectApi = "https://api.github.com/orgs/Obiente/projectsV2/4";
const projectFieldQuery = "fields=372340503,372340864,372340888,372340889";

async function githubJson(url) {
  const response = await fetch(url, {
    headers: githubApiHeaders,
    signal: AbortSignal.timeout(8_000),
  });
  if (!response.ok) {
    throw new Error(`GitHub roadmap request failed with HTTP ${response.status}.`);
  }
  return response.json();
}

function projectField(item, name) {
  const value = item.fields?.find((field) => field.name === name)?.value;
  return value?.name?.raw ?? value?.raw ?? null;
}

function roadmapItem(item) {
  const issue = item.content;
  if (
    item.content_type !== "Issue" ||
    !issue ||
    typeof issue.number !== "number" ||
    typeof issue.title !== "string" ||
    typeof issue.html_url !== "string"
  ) {
    return null;
  }
  return {
    number: issue.number,
    taskId: projectField(item, "Task ID"),
    title: issue.title.replace(/^\[[^\]]+]\s*/, ""),
    url: issue.html_url,
    priority: projectField(item, "Priority"),
    area: projectField(item, "Area"),
    status: projectField(item, "Status"),
    milestone: issue.milestone?.title ?? null,
    progress: issue.sub_issues_summary ?? null,
  };
}

const fallbackRoadmap = {
  source: "repository",
  projectUrl,
  epics: [
    [10, "EPIC-MEDIA", "Safe media backup and storage", "Media"],
    [11, "EPIC-SYNC", "Files client and advanced sync", "Files and sync"],
    [12, "EPIC-DAV", "DAV device sync and native groupware", "DAV"],
    [13, "EPIC-TALK", "Native Talk replacement", "Talk"],
    [14, "EPIC-PHOTO", "Photos and Memories", "Photos and Memories"],
    [15, "EPIC-DYN", "Adaptive Nextcloud apps", "Adaptive apps"],
    [16, "EPIC-PLATFORM", "Platform UX, quality, and releases", "Release"],
  ].map(([number, taskId, title, area]) => ({
    number,
    taskId,
    title,
    area,
    priority: "P0",
    status: "In Progress",
    milestone: null,
    progress: null,
    url: `https://github.com/Obiente/nc-native/issues/${number}`,
  })),
  milestones: [],
  priorities: [],
  verification: [],
};

let roadmap = fallbackRoadmap;
try {
  const [epics, priorities, verification, milestones] = await Promise.all([
    githubJson(`${projectApi}/views/5/items?per_page=100&${projectFieldQuery}`),
    githubJson(`${projectApi}/views/3/items?per_page=100&${projectFieldQuery}`),
    githubJson(`${projectApi}/views/4/items?per_page=100&${projectFieldQuery}`),
    githubJson(
      "https://api.github.com/repos/Obiente/nc-native/milestones?state=all&per_page=100",
    ),
  ]);
  roadmap = {
    source: "github",
    projectUrl,
    epics: epics.map(roadmapItem).filter(Boolean),
    priorities: priorities
      .map(roadmapItem)
      .filter(Boolean)
      .filter((item) => !item.taskId?.startsWith("EPIC-"))
      .sort(
        (left, right) =>
          (left.priority ?? "").localeCompare(right.priority ?? "") ||
          (left.taskId ?? "").localeCompare(right.taskId ?? ""),
      ),
    verification: verification.map(roadmapItem).filter(Boolean),
    milestones: milestones.map((milestone) => ({
      number: milestone.number,
      title: milestone.title,
      url: milestone.html_url,
      open: milestone.open_issues,
      closed: milestone.closed_issues,
    })),
  };
} catch (error) {
  console.warn(`Using repository roadmap fallback: ${error.message}`);
}

await writeFile(
  path.join(generatedDirectory, "roadmap.js"),
  `// Generated from public GitHub roadmap data with a repository fallback. Do not edit.\nexport const roadmap = ${JSON.stringify(roadmap, null, 2)};\n`,
);

const roadmapSearchText = [
  ...roadmap.epics,
  ...roadmap.priorities,
  ...roadmap.verification,
  ...roadmap.milestones,
]
  .map((item) => `${item.taskId ?? ""} ${item.title ?? ""} ${item.area ?? ""}`)
  .join(" ");
await writeFile(
  path.join(publicDirectory, "search-index.json"),
  `${JSON.stringify(
    searchIndex.map((doc) =>
      doc.path === "/roadmap/"
        ? { ...doc, text: `${doc.text} ${roadmapSearchText}`.trim() }
        : doc,
    ),
  )}\n`,
);
