import { renderToString } from "@vue/server-renderer";
import { createServerApp } from "./site.js";
import { docs } from "./generated/docs.js";
import { docsContent } from "./generated/docs-content.js";
import { news } from "./generated/news.js";
import { changelog } from "./generated/changelog.js";

const siteUrl = "https://nc-native.obiente.dev";

function normalizePath(path) {
  const pathname = path.split("?")[0].split("#")[0];
  if (pathname === "/") return "/";
  return `/${pathname.replace(/^\/|\/$/g, "")}/`;
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function safeJson(value) {
  return JSON.stringify(value)
    .replaceAll("<", "\\u003c")
    .replaceAll("\u2028", "\\u2028")
    .replaceAll("\u2029", "\\u2029");
}

function metadataFor(path) {
  const post = news.find((entry) => entry.path === path);
  if (post) {
    return {
      title: `${post.title} · Nextcloud Native`,
      description: post.description,
      canonical: `${siteUrl}${post.path}`,
      type: "article",
      published: post.date,
      modified: post.lastUpdated,
      tags: post.tags,
      image: `${siteUrl}${post.image}`,
      imageAlt: post.imageAlt,
    };
  }
  if (path === "/news/") {
    return {
      title: "Project news · Nextcloud Native",
      description:
        "See how Nextcloud Native is improving phone photo backup, Files, Talk, Photos, notes sync, offline access, and safe storage cleanup.",
      canonical: `${siteUrl}/news/`,
      type: "website",
    };
  }
  if (path === changelog.path) {
    return {
      title: "Changelog · Nextcloud Native",
      description: changelog.description,
      canonical: `${siteUrl}${changelog.path}`,
      type: "website",
    };
  }
  const doc = docs.find((entry) => entry.path === path);
  if (doc) {
    return {
      title: `${doc.title} · Nextcloud Native`,
      description: doc.description,
      canonical: `${siteUrl}${doc.path}`,
      type: "article",
    };
  }

  return {
    title: "Nextcloud Native · Obiente",
    description:
      "Back up phone photos, sync files and Obsidian notes, use Talk, Photos, Files, and more Nextcloud apps in one native phone and desktop client.",
    canonical: `${siteUrl}/`,
    type: "website",
  };
}

export async function render(pathname) {
  const initialPath = normalizePath(pathname);
  const props = {
    initialPath,
    initialDoc: docsContent.find((doc) => doc.path === initialPath) ?? null,
    initialNews: news.find((post) => post.path === initialPath) ?? null,
  };
  const app = createServerApp(props);
  const html = await renderToString(app);
  const metadata = metadataFor(initialPath);
  const softwareData = {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name: "Nextcloud Native",
    applicationCategory: "ProductivityApplication",
    operatingSystem: "Android, Linux, Windows, macOS, iOS",
    description: metadata.description,
    url: siteUrl,
    codeRepository: "https://github.com/Obiente/nc-native",
    license: "https://www.gnu.org/licenses/agpl-3.0.html",
    author: {
      "@type": "Organization",
      name: "Obiente",
      url: "https://obiente.dev",
    },
    isAccessibleForFree: true,
    softwareHelp: `${siteUrl}/contributing/`,
    screenshot: [
      `${siteUrl}/screenshots/desktop-home.png`,
      `${siteUrl}/screenshots/mobile-home.png`,
    ],
  };
  const structuredData = [
    softwareData,
    {
      "@context": "https://schema.org",
      "@type": "WebSite",
      name: "Nextcloud Native",
      url: siteUrl,
      publisher: { "@type": "Organization", name: "Obiente", url: "https://obiente.dev" },
    },
  ];
  if (metadata.published) {
    structuredData.push({
      "@context": "https://schema.org",
      "@type": "TechArticle",
      headline: metadata.title.replace(" · Nextcloud Native", ""),
      description: metadata.description,
      datePublished: metadata.published,
      dateModified: metadata.modified,
      mainEntityOfPage: metadata.canonical,
      author: { "@type": "Organization", name: "Obiente", url: "https://obiente.dev" },
      publisher: { "@type": "Organization", name: "Obiente", url: "https://obiente.dev" },
      keywords: metadata.tags?.join(", "),
      image: metadata.image,
      isAccessibleForFree: true,
    });
  }
  const head = [
    `<title>${escapeHtml(metadata.title)}</title>`,
    `<meta name="description" content="${escapeHtml(metadata.description)}">`,
    `<link rel="canonical" href="${metadata.canonical}">`,
    `<meta property="og:title" content="${escapeHtml(metadata.title)}">`,
    `<meta property="og:description" content="${escapeHtml(metadata.description)}">`,
    `<meta property="og:type" content="${metadata.type}">`,
    `<meta property="og:url" content="${metadata.canonical}">`,
    `<meta property="og:site_name" content="Nextcloud Native">`,
    `<meta property="og:image" content="${metadata.image ?? `${siteUrl}/social-preview.png`}">`,
    `<meta property="og:image:alt" content="${
      metadata.imageAlt
        ? escapeHtml(metadata.imageAlt)
        : "Nextcloud Native, one adaptive native experience for Nextcloud"
    }">`,
    `<meta name="twitter:card" content="summary_large_image">`,
    `<meta name="twitter:title" content="${escapeHtml(metadata.title)}">`,
    `<meta name="twitter:description" content="${escapeHtml(metadata.description)}">`,
    `<meta name="twitter:image" content="${siteUrl}/social-preview.png">`,
    `<meta name="robots" content="index,follow,max-image-preview:large,max-snippet:-1,max-video-preview:-1">`,
    `<link rel="alternate" type="application/rss+xml" title="Nextcloud Native project news" href="${siteUrl}/news.xml">`,
    ...(metadata.published
      ? [
          `<meta property="article:published_time" content="${metadata.published}">`,
          `<meta property="article:modified_time" content="${metadata.modified}">`,
          ...metadata.tags.map((tag) => `<meta property="article:tag" content="${escapeHtml(tag)}">`),
        ]
      : []),
    `<script type="application/ld+json">${safeJson(structuredData)}</script>`,
  ].join("\n    ");

  return {
    head,
    html,
    props,
  };
}

export const routes = [
  "/",
  "/news/",
  changelog.path,
  ...news.map((post) => post.path),
  ...docs.map((doc) => doc.path),
];
export const newsEntries = news;
