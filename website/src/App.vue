<script setup>
import { computed, ref } from "vue";
import {
  PhArrowRight as ArrowRight,
  PhBookOpen as BookOpen,
  PhCalendarBlank as CalendarBlank,
  PhCamera as Camera,
  PhChatCircleDots as ChatCircleDots,
  PhCode as Code,
  PhDesktop as Desktop,
  PhFile as File,
  PhGitBranch as GitBranch,
  PhGithubLogo as GithubLogo,
  PhListChecks as ListChecks,
  PhMagnifyingGlass as MagnifyingGlass,
  PhMusicNotes as MusicNotes,
  PhShieldCheck as ShieldCheck,
  PhSparkle as Sparkle,
  PhStack as Stack,
  PhSquaresFour as SquaresFour,
  PhUsersThree as UsersThree,
  PhX as X,
} from "@phosphor-icons/vue";
import { docs } from "./generated/docs.js";
import { news } from "./generated/news.js";
import { changelog } from "./generated/changelog.js";
import { marketingCaptures } from "./generated/captures.js";
import NativePreview from "./components/NativePreview.vue";
import RoadmapDashboard from "./components/RoadmapDashboard.vue";
import ArticleRoadmap from "./components/ArticleRoadmap.vue";

const props = defineProps({
  initialPath: {
    type: String,
    default: "/",
  },
  initialDoc: {
    type: Object,
    default: null,
  },
  initialNews: {
    type: Object,
    default: null,
  },
});

const githubUrl = "https://github.com/Obiente/nc-native";
const workflowCaptureCopy = {
  "obsidian-vault-sync": {
    title: "Keep an Obsidian vault in sync",
    body: "A real folder pair shows direction, destination, network policy, and bounded transfer counts.",
    alt: "Nextcloud Native Obsidian vault two-way sync pair with pending and completed transfer counts",
  },
  "media-backup-queue": {
    title: "See what your phone still needs to back up",
    body: "Detected media folders and the active Camera pair stay visible without loading an unbounded history.",
    alt: "Nextcloud Native media backup view with Camera and Screenshots suggestions and active transfer counts",
  },
  "adaptive-dynamic-data": {
    title: "Turn discovered data into a useful native view",
    body: "The adaptive renderer maps typed fields into a compact table instead of exposing raw API data.",
    alt: "Nextcloud Native adaptive table with item, category, value, status, and updated columns",
  },
};
const workflowCaptures = marketingCaptures
  .filter((capture) => workflowCaptureCopy[capture.scenario])
  .map((capture) => ({ ...capture, ...workflowCaptureCopy[capture.scenario] }));
const normalizedPath =
  props.initialPath === "/"
    ? "/"
    : `/${props.initialPath.replace(/^\/|\/$/g, "")}/`;
const currentDoc = computed(
  () => props.initialDoc ?? docs.find((doc) => doc.path === normalizedPath),
);
const currentPost = computed(
  () => props.initialNews ?? news.find((post) => post.path === normalizedPath),
);
const relatedPosts = computed(() =>
  news.filter((post) => post.path !== currentPost.value?.path).slice(0, 2),
);
const isNewsIndex = computed(() => normalizedPath === "/news/");
const isChangelog = computed(() => normalizedPath === "/changelog/");
const isHome = computed(() => normalizedPath === "/");
const searchOpen = ref(false);
const searchQuery = ref("");
const searchDocuments = ref(docs);
const searchLoaded = ref(false);

async function openSearch() {
  searchOpen.value = true;
  if (searchLoaded.value || typeof window === "undefined") return;

  try {
    const response = await fetch("/search-index.json");
    if (response.ok) {
      searchDocuments.value = await response.json();
    }
  } finally {
    searchLoaded.value = true;
  }
}

const searchResults = computed(() => {
  const terms = searchQuery.value.toLowerCase().trim().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return searchDocuments.value.slice(0, 5);

  return searchDocuments.value
    .map((doc) => {
      const title = doc.title.toLowerCase();
      const haystack = `${doc.title} ${doc.description} ${doc.text ?? ""}`.toLowerCase();
      const score = terms.reduce((total, term) => {
        if (title.includes(term)) return total + 4;
        if (haystack.includes(term)) return total + 1;
        return total - 10;
      }, 0);
      return { ...doc, score };
    })
    .filter((doc) => doc.score >= terms.length)
    .sort((left, right) => right.score - left.score)
    .slice(0, 6);
});

const capabilities = [
  {
    icon: SquaresFour,
    title: "Your apps in one place",
    body: "Open Files, Photos, Talk, notes, calendars, boards, and more without learning a different interface each time.",
  },
  {
    icon: Sparkle,
    title: "Made for your device",
    body: "Preview and edit files, browse photos, reply to messages, and work offline in interfaces built for phone and desktop.",
  },
  {
    icon: ShieldCheck,
    title: "Your cloud stays yours",
    body: "The app connects directly to your Nextcloud. There is no Obiente account, subscription, or cloud service in the middle.",
  },
];

const appFamilies = [
  { icon: File, name: "Files & Notes" },
  { icon: Camera, name: "Photos & Memories" },
  { icon: ChatCircleDots, name: "Talk" },
  { icon: CalendarBlank, name: "Calendar" },
  { icon: UsersThree, name: "Contacts" },
  { icon: File, name: "Mail" },
  { icon: MusicNotes, name: "Music" },
  { icon: ListChecks, name: "Deck & Tables" },
  { icon: BookOpen, name: "Cookbook" },
  { icon: Stack, name: "Cospend" },
  { icon: ShieldCheck, name: "Administration" },
  { icon: SquaresFour, name: "Dynamic apps" },
];

const featureAreas = [
  {
    icon: File,
    title: "Files, WebDAV, and advanced sync",
    stage: "In active development",
    body: "The product direction covers browsing, previews, editing, sharing, offline files, and revision-safe folder pairs. The alpha currently provides real WebDAV browsing, previews, and guarded text editing while the broader sync workflow is completed.",
    link: "/news/sync-obsidian-notes/",
    label: "How folder sync works",
  },
  {
    icon: Camera,
    title: "Photos, Memories, and Recognize",
    stage: "In active development",
    body: "The alpha already browses real media, RAW previews, and recognized people. Verified backup, albums, Live Photos, non-destructive editing, and cloud-only sharing remain tracked delivery work.",
    link: "/news/media-sync-foundations/",
    label: "How photo backup stays trustworthy",
  },
  {
    icon: ChatCircleDots,
    title: "Talk messages and calls",
    stage: "In active development",
    body: "The alpha provides native rooms, read-only history, attachments, and typed call or system events. Sending, richer interactions, notifications, and full audio or video calling remain on the public roadmap.",
    link: "/compatibility/",
    label: "Explore app compatibility",
  },
  {
    icon: CalendarBlank,
    title: "Calendar, Contacts, and Mail",
    stage: "In active development",
    body: "Native calendar, contact, and mailbox components are being connected to CalDAV, CardDAV, and Mail data. Full editing, composition, search, and device synchronization are tracked work.",
    link: "/architecture/",
    label: "See how native views are selected",
  },
  {
    icon: ListChecks,
    title: "Tables, Deck, Cookbook, Cospend, and Music",
    stage: "In active development",
    body: "Adaptive views already recognize these resource families. Editing tables, moving Kanban cards, recipe workflows, budget actions, and complete system playback integration are being verified app by app.",
    link: "/news/adaptive-native-apps/",
    label: "Read about adaptive native apps",
  },
  {
    icon: ShieldCheck,
    title: "Native Nextcloud administration",
    stage: "Planned",
    body: "The planned administration workspace covers users, groups, quotas, apps, server settings, background jobs, security status, and app-specific administration with explicit permission checks.",
    link: "/roadmap/",
    label: "Follow the public delivery roadmap",
  },
];

const platforms = [
  {
    icon: Desktop,
    name: "Android",
    status: "Alpha build",
    body: "The current Android alpha provides native account, app, file, media, and system integration foundations while broader sync and communication work continues.",
    available: true,
  },
  {
    icon: Desktop,
    name: "Linux",
    status: "Alpha build",
    body: "The current Linux desktop alpha provides the native workspace used for development and integration testing.",
    available: true,
  },
  {
    icon: Code,
    name: "Windows and macOS",
    status: "Packaging preview",
    body: "Prerelease MSI and DMG artifacts prove the packaging pipeline. Native credential storage and supported authenticated use are not implemented yet.",
    available: false,
  },
  {
    icon: Code,
    name: "iOS and iPadOS",
    status: "Planned",
    body: "The shared architecture targets Apple mobile platforms, but no supported launcher is shipped yet.",
    available: false,
  },
];

const adaptiveSteps = [
  {
    icon: GitBranch,
    step: "01",
    title: "See what your server offers",
    body: "The app checks the features and apps your own Nextcloud safely makes available.",
  },
  {
    icon: Stack,
    step: "02",
    title: "Understand the information",
    body: "Dates, people, files, rows, messages, and actions are recognized from verified data.",
  },
  {
    icon: Desktop,
    step: "03",
    title: "Choose the right native view",
    body: "The result becomes a useful gallery, editor, table, conversation, calendar, board, or dashboard.",
  },
];

const frequentlyAsked = [
  {
    question: "Is this a web wrapper?",
    answer:
      "No. Nextcloud Native consumes server APIs and renders native Compose interfaces. Web content is reserved for formats that genuinely require a document renderer, not app navigation.",
  },
  {
    question: "Can I sync an Obsidian notes folder with Nextcloud?",
    answer:
      "Revision-safe folder-pair sync is in active development. The intended workflow pairs a normal device folder with a Nextcloud folder, keeps Markdown visible to Obsidian and other editors, and preserves both versions when changes conflict.",
  },
  {
    question: "Can it back up photos and safely free phone storage?",
    answer:
      "Verified photo backup and storage recovery are in active development. The design distinguishes waiting, uploading, verified, changed, failed, and cloud-only files, and only offers cleanup for exact versions verified on the selected server.",
  },
  {
    question: "How does it work with so many Nextcloud apps?",
    answer:
      "Reusable native components understand common resources such as files, messages, people, events, tables, boards, media, forms, and actions. Verified app knowledge improves specialized workflows without turning every integration into a separate client.",
  },
  {
    question: "Why use adaptive components instead of only app-specific clients?",
    answer:
      "App-specific knowledge can improve an experience, but reusable semantics let similar data and actions work across apps we have never tested. Verified adapters remain available for the places where they create a genuine UX improvement.",
  },
  {
    question: "Is this an official Nextcloud project?",
    answer:
      "No. Nextcloud Native is an independent Obiente project, licensed under AGPL-3.0-or-later. It is not affiliated with, sponsored by or endorsed by Nextcloud GmbH.",
  },
];
</script>

<template>
  <div class="site-shell">
    <a class="skip-link" href="#main">Skip to content</a>

    <header class="site-header">
      <a class="brand" href="/" aria-label="Nextcloud Native home">
        <span class="brand-mark">
          <img src="/cloud.svg" alt="" width="28" height="28" />
        </span>
        <span>Nextcloud Native</span>
      </a>

      <nav class="desktop-nav" aria-label="Primary navigation">
        <a href="/#approach">Approach</a>
        <a href="/#apps">Apps</a>
        <a href="/#platforms">Platforms</a>
        <a href="/roadmap/">Roadmap</a>
        <a href="/news/">News</a>
        <a href="/changelog/">Changelog</a>
        <a href="/#docs">Docs</a>
      </nav>

      <div class="header-actions">
        <button
          class="header-search"
          type="button"
          aria-label="Search project documentation"
          @click="openSearch"
        >
          <MagnifyingGlass :size="20" weight="bold" aria-hidden="true" />
          <span>Search</span>
        </button>
        <a class="header-github" :href="githubUrl" target="_blank" rel="noreferrer">
          <GithubLogo :size="20" weight="fill" aria-hidden="true" />
          <span>GitHub</span>
        </a>
      </div>
    </header>

    <div
      v-if="searchOpen"
      class="search-overlay"
      role="presentation"
      @click.self="searchOpen = false"
      @keydown.esc="searchOpen = false"
    >
      <section class="search-panel" role="dialog" aria-modal="true" aria-labelledby="search-title">
        <div class="search-panel-header">
          <div>
            <p class="eyebrow">Project knowledge</p>
            <h2 id="search-title">Search the documentation</h2>
          </div>
          <button class="icon-button" type="button" aria-label="Close search" @click="searchOpen = false">
            <X :size="21" weight="bold" aria-hidden="true" />
          </button>
        </div>
        <label class="search-input">
          <MagnifyingGlass :size="21" weight="bold" aria-hidden="true" />
          <input
            v-model="searchQuery"
            type="search"
            name="documentation-search"
            placeholder="Search roadmap, apps, schema, security..."
            autocomplete="off"
          />
        </label>
        <div class="search-results" aria-live="polite">
          <a v-for="result in searchResults" :key="result.path" class="search-result" :href="result.path">
            <span>
              <strong>{{ result.shortTitle }}</strong>
              <small>{{ result.description }}</small>
            </span>
            <ArrowRight :size="18" weight="bold" aria-hidden="true" />
          </a>
          <p v-if="searchResults.length === 0" class="empty-search">
            No matching project documentation found.
          </p>
        </div>
      </section>
    </div>

    <main id="main">
      <template v-if="isHome">
        <section class="hero section-width">
          <div class="hero-copy">
            <p class="eyebrow">
              <span class="status-dot" aria-hidden="true"></span>
              Open source · Android and desktop
            </p>
            <h1>One native client for your <span>Nextcloud.</span></h1>
            <p class="hero-lede">
              Use Files, Photos, Memories, Talk, Calendar, Contacts, Mail, Music,
              Deck, Tables, Cookbook, Cospend, and server administration from one
              consistent phone and desktop app.
            </p>
            <div class="hero-actions">
              <a class="button button-primary" href="https://github.com/Obiente/nc-native/releases/latest" target="_blank" rel="noreferrer">
                Download the alpha
                <ArrowRight :size="19" weight="bold" aria-hidden="true" />
              </a>
              <a class="button button-secondary" href="/#apps">
                Explore supported apps
              </a>
            </div>
            <p class="hero-note">An independent Obiente project. Connects directly to your own Nextcloud.</p>
          </div>

          <NativePreview />
        </section>

        <section id="approach" class="approach section-width">
          <div class="section-heading">
            <p class="eyebrow">A complete Nextcloud client</p>
            <h2>Familiar controls across every part of your cloud.</h2>
            <p>
              The same search, selection, sharing, editing, caching, and offline
              behavior follows you from files to messages, photos, calendars, and
              installed apps.
            </p>
          </div>

          <div class="capability-grid">
            <article v-for="capability in capabilities" :key="capability.title" class="capability-card">
              <span class="feature-icon">
                <component :is="capability.icon" :size="25" weight="duotone" aria-hidden="true" />
              </span>
              <h3>{{ capability.title }}</h3>
              <p>{{ capability.body }}</p>
            </article>
          </div>
        </section>

        <section class="adaptive-section">
          <div class="section-width adaptive-layout">
            <div class="section-heading compact">
              <p class="eyebrow">Adaptive native views</p>
              <h2>Installed apps become useful interfaces, not API output.</h2>
              <p>
                Nextcloud Native recognizes resources such as records, files,
                messages, events, media, forms, and actions. It selects a suitable
                native view and adds specialized app knowledge where it improves the workflow.
              </p>
              <a class="text-link" href="/architecture/">
                Read the architecture
                <ArrowRight :size="18" weight="bold" aria-hidden="true" />
              </a>
            </div>

            <ol class="adaptive-steps">
              <li v-for="item in adaptiveSteps" :key="item.step">
                <span class="step-number">{{ item.step }}</span>
                <span class="feature-icon">
                  <component :is="item.icon" :size="24" weight="duotone" aria-hidden="true" />
                </span>
                <div>
                  <h3>{{ item.title }}</h3>
                  <p>{{ item.body }}</p>
                </div>
              </li>
            </ol>
          </div>
        </section>

        <section id="apps" class="apps-section">
          <div class="section-width apps-layout">
            <div class="section-heading compact">
              <p class="eyebrow">Nextcloud apps</p>
              <h2>Each app keeps its purpose.</h2>
              <p>
                Mail stays a mailbox. Deck stays a board. Tables stays a table.
                Shared building blocks keep navigation and actions consistent without
                flattening every app into the same generic screen.
              </p>
              <a class="text-link" href="/compatibility/">
                See the compatibility work
                <ArrowRight :size="18" weight="bold" aria-hidden="true" />
              </a>
            </div>

            <div class="app-family-grid">
              <article v-for="family in appFamilies" :key="family.name" class="app-family">
                <component :is="family.icon" :size="23" weight="duotone" aria-hidden="true" />
                <span>{{ family.name }}</span>
              </article>
            </div>
          </div>
        </section>

        <section id="platforms" class="platform-section section-width">
          <div class="section-heading">
            <p class="eyebrow">Phone and desktop clients</p>
            <h2>Designed for the screen it runs on.</h2>
            <p>
              Mobile uses touch navigation, system sharing, background work, and media
              controls. Desktop uses persistent navigation, dense tables, keyboard
              shortcuts, inspectors, and resizable workspaces.
            </p>
          </div>

          <div class="platform-grid">
            <article v-for="platform in platforms" :key="platform.name" class="platform-card">
              <div class="platform-card-top">
                <span class="feature-icon">
                  <component :is="platform.icon" :size="24" weight="duotone" aria-hidden="true" />
                </span>
                <span class="platform-status" :class="{ available: platform.available }">
                  {{ platform.status }}
                </span>
              </div>
              <h3>{{ platform.name }}</h3>
              <p>{{ platform.body }}</p>
            </article>
          </div>
        </section>

        <section class="feature-overview">
          <div class="section-width">
            <div class="section-heading">
              <p class="eyebrow">Product capabilities</p>
              <h2>More than a viewer for server data.</h2>
              <p>
                Each area includes the controls and system integrations needed to
                browse, create, edit, share, synchronize, and administer real work.
              </p>
            </div>
            <div class="feature-overview-grid">
              <article v-for="feature in featureAreas" :key="feature.title">
                <span class="feature-icon">
                  <component :is="feature.icon" :size="24" weight="duotone" aria-hidden="true" />
                </span>
                <span class="feature-stage">{{ feature.stage }}</span>
                <h3>{{ feature.title }}</h3>
                <p>{{ feature.body }}</p>
                <a :href="feature.link">
                  {{ feature.label }}
                  <ArrowRight :size="16" weight="bold" aria-hidden="true" />
                </a>
              </article>
            </div>
          </div>
        </section>

        <section id="workflows" class="screenshots-section">
          <div class="section-width">
            <div class="section-heading">
              <p class="eyebrow">Captured from the application</p>
              <h2>Real Compose UI, repeatable sample data.</h2>
              <p>
                These captures are rendered from the application's own UI. A local
                fixture server supplies safe, deterministic content for screenshots,
                tests, documentation, and release notes.
              </p>
            </div>
            <div class="workflow-showcase">
              <figure v-for="capture in workflowCaptures" :key="capture.scenario">
                <div class="workflow-capture-media">
                  <img
                    :src="capture.path"
                    :alt="capture.alt"
                    :width="capture.width"
                    :height="capture.height"
                    loading="lazy"
                  />
                </div>
                <figcaption>
                  <strong>{{ capture.title }}</strong>
                  <span>{{ capture.body }}</span>
                </figcaption>
              </figure>
            </div>
            <div class="screenshot-gallery">
              <figure class="screenshot-desktop">
                <img
                  src="/screenshots/desktop-home.png"
                  alt="Nextcloud Native desktop home rendered by the real Compose application with synthetic demo data"
                  width="1440"
                  height="900"
                  loading="lazy"
                />
                <figcaption><strong>A workspace made for desktop</strong><span>Persistent navigation, useful density, and room for focused work.</span></figcaption>
              </figure>
              <figure class="screenshot-mobile">
                <img
                  src="/screenshots/mobile-home.png"
                  alt="Nextcloud Native mobile home rendered offscreen by the real Compose UI with synthetic demo data"
                  width="1080"
                  height="2400"
                  loading="lazy"
                />
                <figcaption><strong>The same cloud, shaped for mobile</strong><span>Touch-sized choices and platform navigation without a web view.</span></figcaption>
              </figure>
            </div>
            <p class="fixture-disclosure">
              Captured from the real app with synthetic names and data. No account, server, cache, or user media is read.
            </p>
          </div>
        </section>

        <section id="docs" class="docs-section">
          <div class="section-width">
            <div class="section-heading">
              <p class="eyebrow">Documentation</p>
              <h2>Architecture, security, compatibility, and contribution guides.</h2>
              <p>
                Documentation is built from the repository alongside the site.
                Architecture decisions, security boundaries, compatibility work,
                and public acceptance gates stay searchable.
              </p>
            </div>
            <div class="docs-grid">
              <a v-for="doc in docs.slice(0, 6)" :key="doc.path" class="doc-card" :href="doc.path">
                <BookOpen :size="24" weight="duotone" aria-hidden="true" />
                <span>
                  <strong>{{ doc.shortTitle }}</strong>
                  <small>{{ doc.description }}</small>
                </span>
                <span class="read-time">{{ doc.readingMinutes }} min</span>
              </a>
            </div>
          </div>
        </section>

        <section class="news-section section-width">
          <div class="news-heading">
            <div class="section-heading compact">
              <p class="eyebrow">Product guides and project news</p>
              <h2>How Nextcloud Native handles everyday work.</h2>
            </div>
            <a class="text-link" href="/news/">All project news <ArrowRight :size="18" weight="bold" /></a>
          </div>
          <div class="news-grid">
            <a v-for="post in news.slice(0, 3)" :key="post.path" class="news-card" :href="post.path">
              <div class="news-card-media">
                <img
                  :src="post.image"
                  :alt="post.imageAlt"
                  :width="post.imageWidth"
                  :height="post.imageHeight"
                  loading="lazy"
                />
              </div>
              <div class="news-card-copy">
                <time :datetime="post.date">{{ post.date }}</time>
                <h3>{{ post.title }}</h3>
                <p>{{ post.description }}</p>
                <span class="news-card-link">{{ post.readingMinutes }} min read <ArrowRight :size="16" weight="bold" /></span>
              </div>
            </a>
          </div>
        </section>

        <section class="faq-section section-width">
          <div class="section-heading compact">
            <p class="eyebrow">Straight answers</p>
            <h2>What Nextcloud Native is, and is not.</h2>
          </div>
          <div class="faq-list">
            <details v-for="item in frequentlyAsked" :key="item.question">
              <summary>{{ item.question }}</summary>
              <p>{{ item.answer }}</p>
            </details>
          </div>
        </section>

        <section class="contribute section-width">
          <div>
            <p class="eyebrow">AGPL-3.0 open source</p>
            <h2>Build and test Nextcloud Native with us.</h2>
            <p>
              Read the architecture, run the clients, test another app contract,
              or bring a platform integration you care about.
            </p>
          </div>
          <a class="button button-primary" href="/contributing/">
            Start contributing
            <ArrowRight :size="19" weight="bold" aria-hidden="true" />
          </a>
        </section>
      </template>

      <section
        v-else-if="currentPost"
        class="article-page section-width"
      >
        <article class="news-article">
          <a class="doc-back" href="/news/">Project news</a>
          <header class="doc-heading">
            <p class="eyebrow">Product story</p>
            <h1>{{ currentPost.title }}</h1>
            <p>{{ currentPost.description }}</p>
            <span>
              Published <time :datetime="currentPost.date">{{ currentPost.date }}</time>
              · Updated <time :datetime="currentPost.lastUpdated">{{ currentPost.lastUpdated }}</time>
              · {{ currentPost.readingMinutes }} minute read
            </span>
            <div class="article-tags"><span v-for="tag in currentPost.tags" :key="tag">{{ tag }}</span></div>
          </header>
          <figure class="article-hero">
            <img
              :src="currentPost.image"
              :alt="currentPost.imageAlt"
              :width="currentPost.imageWidth"
              :height="currentPost.imageHeight"
            />
            <figcaption>{{ currentPost.imageCaption }}</figcaption>
          </figure>
          <div class="markdown-body" v-html="currentPost.html"></div>
          <ArticleRoadmap :slug="currentPost.path.split('/').filter(Boolean).at(-1)" />
          <aside class="article-related" aria-labelledby="article-related-title">
            <div>
              <p class="eyebrow">Continue exploring</p>
              <h2 id="article-related-title">Related Nextcloud Native guides</h2>
            </div>
            <a v-for="post in relatedPosts" :key="post.path" :href="post.path">
              <span>{{ post.title }}</span>
              <ArrowRight :size="16" weight="bold" aria-hidden="true" />
            </a>
            <a href="/architecture/">
              <span>How adaptive native app rendering works</span>
              <ArrowRight :size="16" weight="bold" aria-hidden="true" />
            </a>
          </aside>
          <p class="article-release-link">
            Looking for concise version-by-version changes?
            <a href="/changelog/">Read the changelog</a>.
          </p>
        </article>
      </section>

      <section v-else-if="isNewsIndex" class="news-index section-width">
        <header class="doc-heading">
          <p class="eyebrow">Nextcloud Native news</p>
          <h1>What your Nextcloud can do in one native client.</h1>
          <p>Practical guides explain everyday workflows, the technology behind them, and the public roadmap for each area.</p>
          <a class="text-link" href="/changelog/">
            Looking for release changes? Read the changelog
            <ArrowRight :size="18" weight="bold" />
          </a>
        </header>
        <div class="news-grid news-index-grid">
          <a v-for="post in news" :key="post.path" class="news-card" :href="post.path">
            <div class="news-card-media">
              <img
                :src="post.image"
                :alt="post.imageAlt"
                :width="post.imageWidth"
                :height="post.imageHeight"
                loading="lazy"
              />
            </div>
            <div class="news-card-copy">
              <time :datetime="post.lastUpdated">Updated {{ post.lastUpdated }}</time>
              <h2>{{ post.title }}</h2>
              <p>{{ post.description }}</p>
              <span class="news-card-link">{{ post.readingMinutes }} min read <ArrowRight :size="16" weight="bold" /></span>
            </div>
          </a>
        </div>
      </section>

      <section v-else-if="isChangelog" class="article-page section-width">
        <article class="news-article changelog-article">
          <a class="doc-back" href="/news/">Project news</a>
          <header class="doc-heading">
            <p class="eyebrow">Release history</p>
            <h1>{{ changelog.title }}</h1>
            <p>{{ changelog.description }}</p>
            <span>
              Sourced from {{ changelog.file }}
              <template v-if="!changelog.available"> · awaiting the first public release</template>
            </span>
          </header>
          <div class="markdown-body" v-html="changelog.html"></div>
        </article>
      </section>

      <section
        v-else-if="currentDoc"
        class="doc-page section-width"
        :class="{ 'roadmap-page': currentDoc.path === '/roadmap/' }"
      >
        <aside class="doc-sidebar" aria-label="Project documentation">
          <p>Documentation</p>
          <nav>
            <a
              v-for="doc in docs"
              :key="doc.path"
              :href="doc.path"
              :class="{ active: doc.path === currentDoc.path }"
            >
              {{ doc.shortTitle }}
            </a>
          </nav>
        </aside>

        <article class="doc-article">
          <template v-if="currentDoc.path === '/roadmap/'">
            <header class="roadmap-route-heading">
              <p class="eyebrow">Product roadmap</p>
              <h1>Roadmap</h1>
              <p>
                Release targets and feature work linked directly to the public GitHub project.
              </p>
            </header>
            <RoadmapDashboard />
          </template>
          <template v-else>
            <a class="doc-back" href="/#docs">Nextcloud Native documentation</a>
            <header class="doc-heading">
              <p class="eyebrow">Repository documentation</p>
              <h1>{{ currentDoc.title }}</h1>
              <p>{{ currentDoc.description }}</p>
              <span>{{ currentDoc.readingMinutes }} minute read · sourced from {{ currentDoc.file }}</span>
            </header>
            <div class="markdown-body" v-html="currentDoc.html"></div>
          </template>
        </article>
      </section>

      <section v-else class="not-found section-width">
        <p class="eyebrow">Not found</p>
        <h1>This page is not part of the workspace.</h1>
        <a class="button button-primary" href="/">Return home</a>
      </section>
    </main>

    <footer class="site-footer section-width">
      <a class="brand footer-brand" href="/">
        <span class="brand-mark">
          <img src="/cloud.svg" alt="" width="25" height="25" />
        </span>
        <span>Nextcloud Native</span>
      </a>
      <p>An independent AGPL-3.0-or-later project by Obiente.</p>
      <div class="footer-links">
        <a :href="githubUrl">GitHub</a>
        <a href="/changelog/">Changelog</a>
        <a href="/security/">Security</a>
        <a :href="`${githubUrl}/blob/main/LICENSE`">License</a>
      </div>
    </footer>
  </div>
</template>
