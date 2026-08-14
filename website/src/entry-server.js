import { renderToString } from "@vue/server-renderer";
import { createServerApp } from "./site.js";
import { docs } from "./generated/docs.js";
import { docsContent } from "./generated/docs-content.js";
import { guides } from "./generated/guides.js";
import { guidesContent } from "./generated/guides-content.js";
import { news } from "./generated/news.js";
import { changelog } from "./generated/changelog.js";
import { marketingCaptures } from "./generated/captures.js";
import { guidePlatformHubs, guidePlatformHubForPath } from "./guide-platforms.js";
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
    initialGuide: guidesContent.find((guide) => guide.path === initialPath) ?? null,
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
    applicationCategory: "UtilitiesApplication",
    operatingSystem: "Android, Linux, Windows",
    description:
      "An open-source native alpha client for Nextcloud on Android, Linux, and Windows, with verified Files, media, Calendar, app, offline, and sync foundations.",
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
    offers: {
      "@type": "Offer",
      price: 0,
    },
    inLanguage: "en",
    downloadUrl: "https://github.com/Obiente/nc-native/releases",
    softwareHelp: `${siteUrl}/guides/`,
    featureList: [
      "Nextcloud Login Flow on Android, Linux, and Windows with native credential storage",
      "Native Files browsing, previews, sharing foundations, Android offline storage, and guarded folder sync",
      "Photos and Memories browsing with media backup state on Android",
      "Native Talk history, Notes editing, Calendar, Activity, Dashboard, search, and app navigation",
      "Verified installed-app contracts rendered through reusable native workspaces",
    ],
    screenshot: [
      captureUrl("desktop-home"),
      captureUrl("mobile-home"),
    ],
  };
  const structuredData = initialPath === "/" ? [
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
  ] : [];
  if (initialPath !== "/") {
    const guide = guides.find((entry) => entry.path === initialPath);
    const guideHub = guidePlatformHubForPath(initialPath);
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
            : guide || guideHub
            ? [{
                "@type": "ListItem",
                position: 2,
                name: "Guides",
                item: `${siteUrl}/guides/`,
              }]
            : []),
        ...(guide
          ? [{
              "@type": "ListItem",
              position: 3,
              name: `${guide.platform} guides`,
              item: `${siteUrl}/guides/${guide.platformSlug}/`,
            }]
          : []),
        {
          "@type": "ListItem",
          position: guide ? 4 : metadata.published || guideHub ? 3 : 2,
          name: metadata.title.replace(" · Nextcloud Native", ""),
          item: metadata.canonical,
        },
      ],
    });
  }
  const currentGuide = guides.find((guide) => guide.path === initialPath);
  if (currentGuide) {
    structuredData.push({
      "@context": "https://schema.org",
      "@type": "TechArticle",
      headline: currentGuide.title,
      description: currentGuide.description,
      dateModified: currentGuide.lastUpdated,
      mainEntityOfPage: `${siteUrl}${currentGuide.path}`,
      author: { "@type": "Organization", name: "Obiente", url: organizationUrl },
      publisher: { "@type": "Organization", name: "Obiente", url: organizationUrl },
      audience: {
        "@type": "Audience",
        audienceType: `${currentGuide.platform} ${currentGuide.device} users`,
      },
      about: [
        { "@type": "SoftwareApplication", name: "Nextcloud Native", url: siteUrl },
        { "@type": "Thing", name: currentGuide.platform },
      ],
      timeRequired: `PT${currentGuide.durationMinutes}M`,
      dependencies: currentGuide.prerequisites.join("; "),
      hasPart: currentGuide.steps.map((step) => ({
        "@type": "HowToStep",
        position: step.number,
        name: step.title,
        text: step.text,
        url: `${siteUrl}${currentGuide.path}#step-${step.number}`,
        image: `${siteUrl}${step.imageDark}`,
      })),
      image: `${siteUrl}${currentGuide.imageDark}`,
      inLanguage: "en",
      isAccessibleForFree: true,
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
  const head = [
    `<title>${escapeHtml(metadata.title)}</title>`,
    `<meta name="description" content="${escapeHtml(metadata.description)}">`,
    sharingHeadFor(metadata),
    `<meta name="robots" content="${metadata.robots ?? "index,follow,max-image-preview:large,max-snippet:-1,max-video-preview:-1"}">`,
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
  "/guides/",
  ...guidePlatformHubs.map((hub) => `/guides/${hub.slug}/`),
  "/news/",
  "/visual-qa/",
  changelog.path,
  ...news.map((post) => post.path),
  ...guides.map((guide) => guide.path),
  ...docs.map((doc) => doc.path),
];
export const sitemapRoutes = routes.filter((route) => route !== "/visual-qa/");
export const newsEntries = news;
const latestModification = (entries) => entries
  .map((entry) => entry.lastUpdated)
  .filter(Boolean)
  .sort()
  .at(-1);
export const sitemapEntries = [
  ...news,
  ...guides,
  { path: "/news/", lastUpdated: latestModification(news) },
  { path: "/guides/", lastUpdated: latestModification(guides) },
  ...guidePlatformHubs.map((hub) => ({
    path: `/guides/${hub.slug}/`,
    lastUpdated: latestModification(guides.filter((guide) =>
      hub.slug === "desktop" ? guide.device === "Desktop" : guide.platforms.includes(hub.label)
    )),
  })),
];
