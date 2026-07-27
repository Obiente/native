import { renderToString } from "@vue/server-renderer";
import { createServerApp } from "./site.js";
import { docs } from "./generated/docs.js";
import { docsContent } from "./generated/docs-content.js";
import { news } from "./generated/news.js";
import { changelog } from "./generated/changelog.js";
import { marketingCaptures } from "./generated/captures.js";
import {
  metadataFor,
  sharingHeadFor,
  siteUrl,
  socialImageDetailsFor,
} from "./server-metadata.js";

const organizationUrl = "https://obiente.org";
const captureUrl = (scenario) => {
  const capture = marketingCaptures.find((candidate) => candidate.scenario === scenario);
  if (!capture) throw new Error(`Missing marketing capture: ${scenario}`);
  return `${siteUrl}${capture.websitePath}`;
};

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
  const socialImage = socialImageDetailsFor(metadata);
  const softwareData = {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name: "Nextcloud Native",
    applicationCategory: "ProductivityApplication",
    operatingSystem: "Android, Linux",
    description:
      "An early alpha native Nextcloud client for Android and Linux with real Files, media, Talk, Notes, Activity, dashboard, and adaptive app integrations.",
    url: siteUrl,
    codeRepository: "https://github.com/Obiente/nc-native",
    license: "https://www.gnu.org/licenses/agpl-3.0.html",
    author: {
      "@type": "Organization",
      name: "Obiente",
      url: organizationUrl,
    },
    publisher: {
      "@type": "Organization",
      name: "Obiente",
      url: organizationUrl,
    },
    image: `${siteUrl}/icon-512.png`,
    isAccessibleForFree: true,
    inLanguage: "en",
    downloadUrl: "https://github.com/Obiente/nc-native/releases",
    softwareHelp: `${siteUrl}/contributing/`,
    featureList: [
      "Native Nextcloud account connection on Android and Linux",
      "WebDAV file browsing, previews, bounded downloads, and guarded text editing",
      "Media browsing, server and RAW previews, recognized people, and per-person galleries",
      "Talk room and read-only history views with typed attachments and system events",
      "Markdown Notes editing and preview with explicit save and conflict handling",
      "Read-only Activity timeline and adaptive typed views for installed apps",
    ],
    screenshot: [
      captureUrl("desktop-home"),
      captureUrl("mobile-home"),
    ],
  };
  const structuredData = [
    softwareData,
    {
      "@context": "https://schema.org",
      "@type": "WebSite",
      name: "Nextcloud Native",
      url: siteUrl,
      publisher: { "@type": "Organization", name: "Obiente", url: organizationUrl },
      image: `${siteUrl}/icon-512.png`,
      sameAs: ["https://github.com/Obiente/nc-native"],
      inLanguage: "en",
    },
  ];
  if (initialPath !== "/") {
    structuredData.push({
      "@context": "https://schema.org",
      "@type": "BreadcrumbList",
      itemListElement: [
        {
          "@type": "ListItem",
          position: 1,
          name: "Nextcloud Native",
          item: siteUrl,
        },
        ...(metadata.published
          ? [{
              "@type": "ListItem",
              position: 2,
              name: "News",
              item: `${siteUrl}/news/`,
            }]
          : []),
        {
          "@type": "ListItem",
          position: metadata.published ? 3 : 2,
          name: metadata.title.replace(" · Nextcloud Native", ""),
          item: metadata.canonical,
        },
      ],
    });
  }
  if (metadata.published) {
    structuredData.push({
      "@context": "https://schema.org",
      "@type": "Article",
      headline: metadata.title.replace(" · Nextcloud Native", ""),
      description: metadata.description,
      datePublished: metadata.published,
      dateModified: metadata.modified,
      mainEntityOfPage: metadata.canonical,
      author: { "@type": "Organization", name: "Obiente", url: organizationUrl },
      publisher: { "@type": "Organization", name: "Obiente", url: organizationUrl },
      keywords: metadata.tags?.join(", "),
      image: {
        "@type": "ImageObject",
        url: socialImage.url,
        width: socialImage.width,
        height: socialImage.height,
        caption: socialImage.alt,
      },
      isAccessibleForFree: true,
      inLanguage: "en",
      about: [
        { "@type": "SoftwareApplication", name: "Nextcloud Native", url: siteUrl },
        { "@type": "Thing", name: "Nextcloud" },
      ],
    });
  }
  if (initialPath === "/") {
    structuredData.push({
      "@context": "https://schema.org",
      "@type": "FAQPage",
      mainEntity: [
        [
          "Is Nextcloud Native a web wrapper?",
          "No. It uses Nextcloud server APIs and renders native interfaces for phone and desktop.",
        ],
        [
          "Can Nextcloud Native sync an Obsidian folder?",
          "Revision-aware folder-pair sync is in active development and is tracked on the public roadmap.",
        ],
        [
          "Can Nextcloud Native back up phone photos?",
          "Verified photo backup and safe storage recovery are in active development and are tracked on the public roadmap.",
        ],
      ].map(([name, text]) => ({
        "@type": "Question",
        name,
        acceptedAnswer: { "@type": "Answer", text },
      })),
    });
  }
  const head = [
    `<title>${escapeHtml(metadata.title)}</title>`,
    `<meta name="description" content="${escapeHtml(metadata.description)}">`,
    sharingHeadFor(metadata),
    `<meta name="robots" content="index,follow,max-image-preview:large,max-snippet:-1,max-video-preview:-1">`,
    `<meta name="author" content="Obiente">`,
    `<link rel="alternate" type="application/rss+xml" title="Nextcloud Native project news" href="${siteUrl}/news.xml">`,
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
  "/visual-qa/",
  changelog.path,
  ...news.map((post) => post.path),
  ...docs.map((doc) => doc.path),
];
export const newsEntries = news;
