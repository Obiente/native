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
import { parseGuideFrontmatter } from "./guide-frontmatter.mjs";
import {
  assertValidNativeNewsFeed,
  nativeNewsFeedRevision,
} from "./news-feed-contract.mjs";
import {
  githubJsonPages,
  normalizeRoadmapSnapshot,
  repositoryRoadmapFallback,
  roadmapSnapshotFromLive,
  shippedPriorityItems,
} from "./roadmap-data.mjs";
import {
  composeChangelogSource,
  loadFragments,
  validateArchivedReleaseHistory,
} from "../../tools/changelog-fragments.mjs";
import {
  articleCapturePair,
  stableCapturePath,
  validateCaptureManifest,
  websiteCapturePath,
} from "./marketing-captures.mjs";
import { resolveGithubRepositoryData } from "./github-repository-data.mjs";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(websiteRoot, "..");
const generatedDirectory = path.join(websiteRoot, "src", "generated");
const publicDirectory = path.join(websiteRoot, "public");
const newsDirectory = path.join(websiteRoot, "content", "news");
const guideDirectory = path.join(websiteRoot, "content", "guides");
const roadmapSnapshotFile = path.join(websiteRoot, "data", "roadmap-snapshot.json");
const repositorySnapshotFile = path.join(websiteRoot, "data", "github-repository.json");
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
const captureManifest = validateCaptureManifest(JSON.parse(
  await readFile(
    path.join(publicDirectory, "screenshots", "capture-manifest.json"),
    "utf8",
  ),
));
const marketingCaptures = captureManifest.captures.map((capture) => ({
  ...capture,
  path: stableCapturePath(capture),
  websitePath: websiteCapturePath(captureManifest, capture),
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
    file: "docs/shared-ui-controls.md",
    path: "/shared-ui-controls/",
    title: "Shared native choice controls",
    shortTitle: "Shared controls",
    description:
      "Reusable native view switchers and form choices, their real consumers, and the state and permission boundaries they preserve.",
  },
  {
    file: "CONTRIBUTING.md",
    path: "/contributing/",
    title: "Contributing",
    shortTitle: "Contributing",
    description:
      "Build requirements, validation commands and contribution guidance for nati.ve.",
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
  typographer: false,
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
  return [...source.matchAll(/^(#{2,4})[\t ]+(.+)$/gm)].map((match) => ({
    title: match[2].replace(/[`*_]/g, "").trim(),
    anchor: slugify(match[2]),
    level: match[1].length,
  }));
}

function withoutLeadingTitle(source) {
  return source.replace(/^#\s+[^\r\n]+\r?\n(?:\r?\n)*/u, "");
}

const docs = await Promise.all(
  sources.map(async (source) => {
    const markdownSource = await readFile(path.join(repositoryRoot, source.file), "utf8");
    const html = markdown.render(withoutLeadingTitle(markdownSource));
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

const guideFiles = (await readdir(guideDirectory))
  .filter((file) => file.endsWith(".md"))
  .sort();
const guides = await Promise.all(
  guideFiles.map(async (file) => {
    const source = await readFile(path.join(guideDirectory, file), "utf8");
    const parsed = parseGuideFrontmatter(source, file);
    const steps = parsed.steps.map((step) => {
      const capturePair = articleCapturePair(
        captureManifest,
        step.captureScenario,
        `${file} step ${step.number}`,
      );
      const dark = capturePair.dark;
      const light = capturePair.light;
      return {
        ...step,
        html: markdown.render(step.source),
        text: textOnly(step.source),
        imageDark: stableCapturePath(dark),
        imageLight: stableCapturePath(light),
        websiteImageDark: websiteCapturePath(captureManifest, dark),
        websiteImageLight: websiteCapturePath(captureManifest, light),
        imageWidth: dark.width,
        imageHeight: dark.height,
      };
    });
    const text = [parsed.introduction, ...steps.map((step) => step.text)].join(" ");
    const firstStep = steps[0];
    return {
      file,
      path: `/guides/${parsed.metadata.platformSlug}/${parsed.metadata.slug}/`,
      title: parsed.metadata.title,
      shortTitle: parsed.metadata.title,
      description: parsed.metadata.description,
      category: parsed.metadata.category,
      platform: parsed.metadata.platform,
      platformSlug: parsed.metadata.platformSlug,
      device: parsed.metadata.device,
      platforms: parsed.metadata.platforms,
      durationMinutes: parsed.metadata.durationMinutes,
      difficulty: parsed.metadata.difficulty,
      lastUpdated: parsed.metadata.lastUpdated,
      prerequisites: parsed.metadata.prerequisites,
      introduction: parsed.introduction,
      introductionHtml: markdown.render(parsed.introduction),
      steps,
      text,
      readingMinutes: Math.max(1, Math.ceil(text.split(/\s+/u).length / 220)),
      imageDark: firstStep.imageDark,
      imageLight: firstStep.imageLight,
      websiteImageDark: firstStep.websiteImageDark,
      websiteImageLight: firstStep.websiteImageLight,
      imageAlt: firstStep.imageAlt,
      imageWidth: firstStep.imageWidth,
      imageHeight: firstStep.imageHeight,
    };
  }),
);
if (new Set(guides.map((guide) => guide.path)).size !== guides.length) {
  throw new Error("Guide slugs must be unique.");
}
const guidesMetadata = guides.map(({ introductionHtml, steps, ...guide }) => ({
  ...guide,
  steps: steps.map(({ html, source, ...step }) => step),
}));
await writeFile(
  path.join(generatedDirectory, "guides.js"),
  `// Generated from task-based guide Markdown. Do not edit.\nexport const guides = ${JSON.stringify(guidesMetadata, null, 2)};\n`,
);
await writeFile(
  path.join(generatedDirectory, "guides-content.js"),
  `// Generated from task-based guide Markdown. Do not edit.\nexport const guidesContent = ${JSON.stringify(guides, null, 2)};\n`,
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
const allFragments = await loadFragments(repositoryRoot, { includeArchive: true });
await validateArchivedReleaseHistory(repositoryRoot, allFragments);
const unreleasedFragments = await loadFragments(repositoryRoot);
if (changelogAvailable) {
  changelogSource = composeChangelogSource(changelogSource, unreleasedFragments);
}
const changelogBody = changelogSource.replace(/^#\s+Changelog\s*\n+/i, "");
const changelogText = textOnly(changelogBody);
const changelog = {
  file: "changes/unreleased and CHANGELOG.md",
  path: changelogRoute,
  title: "Changelog",
  shortTitle: "Changelog",
  description:
    "Concise user-facing changes from independent pull-request fragments and immutable release history.",
  html: markdown.render(changelogBody),
  text: changelogText,
  headings: headingsFrom(changelogBody),
  readingMinutes: Math.max(1, Math.ceil(changelogText.split(/\s+/).length / 220)),
  available: changelogAvailable,
};
await writeFile(
  path.join(generatedDirectory, "changelog.js"),
  `// Generated from changes/unreleased and the canonical root CHANGELOG.md. Do not edit.\nexport const changelog = ${JSON.stringify(changelog, null, 2)};\n`,
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
    const capturePair = articleCapturePair(
      captureManifest,
      metadata.captureScenario,
      file,
    );
    const imageDark = stableCapturePath(capturePair.dark);
    const imageLight = stableCapturePath(capturePair.light);
    return {
      file,
      path: `/news/${metadata.slug}/`,
      title: metadata.title,
      shortTitle: metadata.title,
      description: metadata.description,
      date: metadata.date,
      lastUpdated: metadata.lastUpdated,
      tags: metadata.tags.split(",").map((tag) => tag.trim()).filter(Boolean),
      captureScenario: capturePair.dark.baseScenario,
      captureScenarioDark: capturePair.dark.scenario,
      captureScenarioLight: capturePair.light.scenario,
      image: imageDark,
      imageDark,
      imageLight,
      websiteImage: websiteCapturePath(captureManifest, capturePair.dark),
      websiteImageDark: websiteCapturePath(captureManifest, capturePair.dark),
      websiteImageLight: websiteCapturePath(captureManifest, capturePair.light),
      imageAlt: metadata.imageAlt,
      imageCaption: metadata.imageCaption,
      imageWidth: capturePair.dark.width,
      imageHeight: capturePair.dark.height,
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
  {
    path: "/",
    title: "nati.ve for Android, Linux and Windows",
    shortTitle: "nati.ve",
    description:
      "Open-source native Nextcloud alpha with Files, Photos, Talk history, Calendar, offline files, sync, and installed-app views.",
    text:
      "Android Linux Windows native Nextcloud alpha Files offline sync multiple accounts background transfer global search photo backup Memories Recognize Live Photos Talk history Mail Calendar Contacts Tasks Notes Deck Tables Cookbook Cospend Music Obsidian folder sync planned iOS iPadOS macOS",
    contentType: "Product",
  },
  ...docs.map(({ html, ...doc }) => ({ ...doc, contentType: "Documentation" })),
  ...guides.map(({ introductionHtml, steps, ...guide }) => ({
    ...guide,
    contentType: "Guide",
    headings: steps.map((step) => ({ title: step.title, anchor: `step-${step.number}` })),
  })),
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
    throw new Error(`GitHub API request failed with HTTP ${response.status}.`);
  }
  return response.json();
}

function githubProjectItems(url) {
  return githubJsonPages(url, { headers: githubApiHeaders });
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
    labels: (issue.labels ?? []).map((label) => label.name).filter(Boolean),
    updatedAt: issue.updated_at ?? null,
    closedAt: issue.closed_at ?? null,
  };
}

async function allProjectItems() {
  return githubProjectItems(`${projectApi}/items?per_page=100&${projectFieldQuery}`);
}

const fallbackRoadmap = repositoryRoadmapFallback(projectUrl);

const githubRepositoryResult = await resolveGithubRepositoryData({
  loadLive: () => githubJson("https://api.github.com/repos/obiente/native"),
  loadSnapshot: async () => JSON.parse(await readFile(repositorySnapshotFile, "utf8")),
});
if (githubRepositoryResult.warning) {
  console.warn(
    `Using bundled GitHub repository snapshot: ${githubRepositoryResult.warning}`,
  );
}
await writeFile(
  path.join(generatedDirectory, "github-repository.js"),
  `// Generated from public GitHub repository metadata with a bundled fallback. Do not edit.\nexport const githubRepository = Object.freeze(${JSON.stringify(githubRepositoryResult.repository, null, 2)});\n`,
);

let roadmap = fallbackRoadmap;
try {
  const [epics, priorities, projectItems, verification, milestones] = await Promise.all([
    githubProjectItems(`${projectApi}/views/5/items?per_page=100&${projectFieldQuery}`),
    githubProjectItems(`${projectApi}/views/3/items?per_page=100&${projectFieldQuery}`),
    allProjectItems(),
    githubProjectItems(`${projectApi}/views/4/items?per_page=100&${projectFieldQuery}`),
    githubJson(
      "https://api.github.com/repos/obiente/native/milestones?state=all&per_page=100",
    ),
  ]);
  const projectRoadmapItems = projectItems.map(roadmapItem).filter(Boolean);
  const projectUpdatedAt = [
    ...projectRoadmapItems.map((item) => item.updatedAt),
    ...milestones.map((milestone) => milestone.updated_at),
  ]
    .filter(Boolean)
    .sort()
    .at(-1) ?? null;
  roadmap = {
    source: "github",
    syncState: "live",
    projectUrl,
    updatedAt: projectUpdatedAt,
    epics: epics.map(roadmapItem).filter(Boolean),
    shipped: shippedPriorityItems(projectRoadmapItems)
      .sort((left, right) => (right.closedAt ?? "").localeCompare(left.closedAt ?? "")),
    priorities: priorities
      .map(roadmapItem)
      .filter(Boolean)
      .filter((item) => !item.taskId?.startsWith("EPIC-"))
      .filter((item) => item.status !== "Done")
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
      description: milestone.description,
      state: milestone.state,
      dueOn: milestone.due_on,
      updatedAt: milestone.updated_at,
      open: milestone.open_issues,
      closed: milestone.closed_issues,
    })),
  };
  await mkdir(path.dirname(roadmapSnapshotFile), { recursive: true });
  await writeFile(
    roadmapSnapshotFile,
    `${JSON.stringify(roadmapSnapshotFromLive(roadmap), null, 2)}\n`,
  );
} catch (error) {
  try {
    roadmap = normalizeRoadmapSnapshot(
      JSON.parse(await readFile(roadmapSnapshotFile, "utf8")),
    );
    console.warn(`Using bundled GitHub roadmap snapshot: ${error.message}`);
  } catch (snapshotError) {
    roadmap = fallbackRoadmap;
    console.warn(
      `Using repository roadmap fallback: ${error.message}; ${snapshotError.message}`,
    );
  }
}

await writeFile(
  path.join(generatedDirectory, "roadmap.js"),
  `// Generated from public GitHub roadmap data with a repository fallback. Do not edit.\nexport const roadmap = ${JSON.stringify(roadmap, null, 2)};\n`,
);

const roadmapSearchText = [
  ...roadmap.epics,
  ...roadmap.shipped,
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
