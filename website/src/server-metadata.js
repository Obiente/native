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
      "A native Nextcloud mobile and desktop client for Files, WebDAV sync, Photos, Memories, Talk, Calendar, Contacts, Mail, and installed apps.",
    canonical: `${siteUrl}/`,
    type: "website",
  };
}

export function socialImageFor(metadata) {
  return metadata.image ?? `${siteUrl}/social-preview.png`;
}
