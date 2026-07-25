import { changelog } from "./generated/changelog.js";
import { docs } from "./generated/docs.js";
import { news } from "./generated/news.js";

export const siteUrl = "https://nc-native.obiente.dev";

export function metadataFor(path) {
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
      imageWidth: post.imageWidth,
      imageHeight: post.imageHeight,
    };
  }
  if (path === "/news/") {
    return {
      title: "Project news · Nextcloud Native",
      description:
        "Guides to Nextcloud Native photo backup, WebDAV file sync, Obsidian notes, Talk, Photos, offline access, and adaptive native Nextcloud apps.",
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
      "An early alpha native Nextcloud client for Android and Linux, with real integrations and a public roadmap for broader app and platform support.",
    canonical: `${siteUrl}/`,
    type: "website",
  };
}

export function socialImageFor(metadata) {
  return metadata.image ?? `${siteUrl}/social-preview.png`;
}

export function socialImageDetailsFor(metadata) {
  return {
    url: socialImageFor(metadata),
    alt:
      metadata.imageAlt ??
      "Nextcloud Native desktop and mobile clients connected to Nextcloud",
    width: metadata.imageWidth ?? 1280,
    height: metadata.imageHeight ?? 640,
    type: "image/png",
  };
}

function escapeAttribute(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function sharingHeadFor(metadata) {
  const image = socialImageDetailsFor(metadata);
  return [
    `<link rel="canonical" href="${escapeAttribute(metadata.canonical)}">`,
    `<link rel="alternate" hreflang="en" href="${escapeAttribute(metadata.canonical)}">`,
    `<link rel="alternate" hreflang="x-default" href="${escapeAttribute(metadata.canonical)}">`,
    `<meta property="og:title" content="${escapeAttribute(metadata.title)}">`,
    `<meta property="og:description" content="${escapeAttribute(metadata.description)}">`,
    `<meta property="og:type" content="${escapeAttribute(metadata.type)}">`,
    `<meta property="og:url" content="${escapeAttribute(metadata.canonical)}">`,
    `<meta property="og:site_name" content="Nextcloud Native">`,
    `<meta property="og:locale" content="en_US">`,
    `<meta property="og:image" content="${escapeAttribute(image.url)}">`,
    `<meta property="og:image:secure_url" content="${escapeAttribute(image.url)}">`,
    `<meta property="og:image:type" content="${image.type}">`,
    `<meta property="og:image:width" content="${image.width}">`,
    `<meta property="og:image:height" content="${image.height}">`,
    `<meta property="og:image:alt" content="${escapeAttribute(image.alt)}">`,
    `<meta name="twitter:card" content="summary_large_image">`,
    `<meta name="twitter:title" content="${escapeAttribute(metadata.title)}">`,
    `<meta name="twitter:description" content="${escapeAttribute(metadata.description)}">`,
    `<meta name="twitter:image" content="${escapeAttribute(image.url)}">`,
    `<meta name="twitter:image:alt" content="${escapeAttribute(image.alt)}">`,
    ...(metadata.published
      ? [
          `<meta property="article:published_time" content="${escapeAttribute(metadata.published)}">`,
          `<meta property="article:modified_time" content="${escapeAttribute(metadata.modified)}">`,
          `<meta property="article:author" content="https://obiente.org">`,
          ...metadata.tags.map(
            (tag) => `<meta property="article:tag" content="${escapeAttribute(tag)}">`,
          ),
        ]
      : []),
  ].join("\n    ");
}
