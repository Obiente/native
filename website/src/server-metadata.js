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

export function socialImageFor(metadata) {
  return metadata.image ?? `${siteUrl}/social-preview.png`;
}
