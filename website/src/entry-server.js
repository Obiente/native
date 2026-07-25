import { renderToString } from "@vue/server-renderer";
import { createServerApp } from "./site.js";
import { docs } from "./generated/docs.js";
import { docsContent } from "./generated/docs-content.js";
import { news } from "./generated/news.js";
import { changelog } from "./generated/changelog.js";
import { metadataFor, siteUrl, socialImageFor } from "./server-metadata.js";

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
  const socialImage = socialImageFor(metadata);
  const softwareData = {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name: "Nextcloud Native",
    applicationCategory: "ProductivityApplication",
    operatingSystem: "Android, Linux, Windows, macOS, iOS",
    description:
      "A native mobile and desktop client for Files, sync, Photos, Memories, Talk, Calendar, Contacts, Mail, and installed Nextcloud apps.",
    url: siteUrl,
    codeRepository: "https://github.com/Obiente/nc-native",
    license: "https://www.gnu.org/licenses/agpl-3.0.html",
    author: {
      "@type": "Organization",
      name: "Obiente",
      url: "https://obiente.dev",
    },
    publisher: {
      "@type": "Organization",
      name: "Obiente",
      url: "https://obiente.dev",
    },
    isAccessibleForFree: true,
    inLanguage: "en",
    downloadUrl: "https://github.com/Obiente/nc-native/releases",
    softwareHelp: `${siteUrl}/contributing/`,
    featureList: [
      "Native Nextcloud Files and WebDAV sync",
      "Phone photo backup, Memories, albums, tags, people, RAW, and Live Photos",
      "Nextcloud Talk messages, attachments, notifications, and calls",
      "Calendar and CalDAV event management",
      "Contacts and CardDAV address books",
      "Mail folders, HTML messages, attachments, search, and composition",
      "Tables, Deck, Cookbook, Cospend, Music, and adaptive installed apps",
      "Native Nextcloud user, app, and server administration",
      "Offline cache, multiple accounts, background sync, and global search",
    ],
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
      author: { "@type": "Organization", name: "Obiente", url: "https://obiente.dev" },
      publisher: { "@type": "Organization", name: "Obiente", url: "https://obiente.dev" },
      keywords: metadata.tags?.join(", "),
      image: socialImage,
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
          "Yes. It pairs a normal device folder with a Nextcloud folder and provides revision-aware two-way sync.",
        ],
        [
          "Can Nextcloud Native back up phone photos?",
          "Yes. It shows waiting, uploading, verified, changed, failed, and cloud-only states and keeps current media visible to other apps.",
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
    `<link rel="canonical" href="${metadata.canonical}">`,
    `<meta property="og:title" content="${escapeHtml(metadata.title)}">`,
    `<meta property="og:description" content="${escapeHtml(metadata.description)}">`,
    `<meta property="og:type" content="${metadata.type}">`,
    `<meta property="og:url" content="${metadata.canonical}">`,
    `<meta property="og:site_name" content="Nextcloud Native">`,
    `<meta property="og:image" content="${escapeHtml(socialImage)}">`,
    `<meta property="og:image:alt" content="${
      metadata.imageAlt
        ? escapeHtml(metadata.imageAlt)
        : "Nextcloud Native desktop and mobile clients connected to Nextcloud"
    }">`,
    `<meta name="twitter:card" content="summary_large_image">`,
    `<meta name="twitter:title" content="${escapeHtml(metadata.title)}">`,
    `<meta name="twitter:description" content="${escapeHtml(metadata.description)}">`,
    `<meta name="twitter:image" content="${escapeHtml(socialImage)}">`,
    `<meta name="twitter:image:alt" content="${
      metadata.imageAlt
        ? escapeHtml(metadata.imageAlt)
        : "Nextcloud Native desktop and mobile clients connected to Nextcloud"
    }">`,
    `<meta name="robots" content="index,follow,max-image-preview:large,max-snippet:-1,max-video-preview:-1">`,
    `<meta name="author" content="Obiente">`,
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
