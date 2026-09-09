import { changelog } from "./generated/changelog.js";
import { docs } from "./generated/docs.js";
import { guides } from "./generated/guides.js";
import { news } from "./generated/news.js";
import { guidePlatformHubForPath } from "./guide-platforms.js";

export const siteUrl = "https://nati.ve";

function brandedTitle(title) {
  const suffix = " | nati.ve";
  return title.length + suffix.length <= 60 ? `${title}${suffix}` : title;
}

function metadataDescription(description) {
  if (description.length <= 160) return description;
  const shortened = description.slice(0, 157).replace(/\s+\S*$/u, "").trimEnd();
  return `${shortened}.`;
}

export function metadataFor(path) {
  const guide = guides.find((entry) => entry.path === path);
  if (guide) {
    return {
      title: brandedTitle(guide.title),
      description: metadataDescription(guide.description),
      canonical: `${siteUrl}${guide.path}`,
      type: "article",
      modified: guide.lastUpdated,
      image: `${siteUrl}${guide.websiteImageDark}`,
      imageAlt: guide.imageAlt,
      imageWidth: guide.imageWidth,
      imageHeight: guide.imageHeight,
    };
  }
  const guideHub = guidePlatformHubForPath(path);
  if (guideHub) {
    return {
      title: brandedTitle(guideHub.title),
      description: metadataDescription(guideHub.description),
      canonical: `${siteUrl}/guides/${guideHub.slug}/`,
      type: "website",
    };
  }
  if (path === "/guides/") {
    return {
      title: "Android, Linux and Windows guides | nati.ve",
      description:
        "Choose Android, Linux, or Windows instructions for nati.ve setup, offline files, folder sync, photo backup, Calendar, and desktop integration.",
      canonical: `${siteUrl}/guides/`,
      type: "website",
    };
  }
  const post = news.find((entry) => entry.path === path);
  if (post) {
    return {
      title: brandedTitle(post.title),
      description: metadataDescription(post.description),
      canonical: `${siteUrl}${post.path}`,
      type: "article",
      published: post.date,
      modified: post.lastUpdated,
      tags: post.tags,
      image: `${siteUrl}${post.websiteImage}`,
      imageAlt: post.imageAlt,
      imageWidth: post.imageWidth,
      imageHeight: post.imageHeight,
    };
  }
  if (path === "/news/") {
    return {
      title: "Project journal | nati.ve",
      description:
        "Dated product and design notes about nati.ve app rendering, folder sync, Obsidian workflows, Android media backup, and platform integration.",
      canonical: `${siteUrl}/news/`,
      type: "website",
    };
  }
  if (path === "/visual-qa/") {
    return {
      title: "Visual QA catalog | nati.ve",
      description:
        "Browse synthetic desktop and mobile screenshots rendered directly from the nati.ve Compose UI.",
      canonical: `${siteUrl}/visual-qa/`,
      type: "website",
      robots: "noindex,follow",
    };
  }
  if (path === changelog.path) {
    return {
      title: "Changelog | nati.ve",
      description: metadataDescription(changelog.description),
      canonical: `${siteUrl}${changelog.path}`,
      type: "website",
    };
  }
  const doc = docs.find((entry) => entry.path === path);
  if (doc) {
    return {
      title: brandedTitle(doc.title),
      description: metadataDescription(doc.description),
      canonical: `${siteUrl}${doc.path}`,
      type: "article",
      modified: "2026-08-20",
    };
  }

  return {
    title: "nati.ve for Android, Linux and Windows | Obiente",
    description:
      "Open-source native Nextcloud alpha for Android, Linux, and Windows. Test Files, Photos, Talk history, Calendar, offline files, sync, and installed-app views.",
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
      "nati.ve wordmark with mint and blue curves on charcoal",
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
    `<meta property="og:site_name" content="nati.ve">`,
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
    ...(metadata.type === "article" && metadata.modified
      ? [
          ...(metadata.published
            ? [`<meta property="article:published_time" content="${escapeAttribute(metadata.published)}">`]
            : []),
          `<meta property="article:modified_time" content="${escapeAttribute(metadata.modified)}">`,
          `<meta property="article:author" content="https://obiente.org">`,
          ...(metadata.tags ?? []).map(
            (tag) => `<meta property="article:tag" content="${escapeAttribute(tag)}">`,
          ),
        ]
      : []),
  ].join("\n    ");
}
