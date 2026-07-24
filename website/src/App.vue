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
import NativePreview from "./components/NativePreview.vue";
import RoadmapDashboard from "./components/RoadmapDashboard.vue";

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
  { icon: MusicNotes, name: "Music" },
  { icon: ListChecks, name: "Deck & Tables" },
  { icon: SquaresFour, name: "Dynamic apps" },
];

const platforms = [
  {
    icon: Desktop,
    name: "Android",
    status: "Developer build",
    body: "Native login, media, filesystem integration and background work.",
    available: true,
  },
  {
    icon: Desktop,
    name: "Linux",
    status: "Developer build",
    body: "A real desktop workspace with platform credential storage.",
    available: true,
  },
  {
    icon: Code,
    name: "iOS · macOS · Windows",
    status: "Architecture ready",
    body: "Shared domain logic with thin, genuinely native platform integrations.",
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
      "That is a core goal. The planned experience pairs a normal Android folder with a Nextcloud folder, supports two-way sync, keeps notes visible to Obsidian, and asks before resolving conflicts. The foundations are under development and are not release-ready yet.",
  },
  {
    question: "Can it back up photos and safely free phone storage?",
    answer:
      "That is also a core goal. The app will distinguish waiting, uploading, verified, changed, failed, and cloud-only photos. Storage cleanup will only be offered for an exact version verified on the server, followed by Android's own confirmation.",
  },
  {
    question: "Does every Nextcloud app work already?",
    answer:
      "Not yet. This is an early developer preview. Files, Photos and Memories, Talk, Activity, Notes and several dynamically discovered apps have working paths, while the compatibility matrix records what still needs evidence or deeper interaction support.",
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
            placeholder="Search roadmap, apps, schema, security…"
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
              Independent · open source · early preview
            </p>
            <h1>Your cloud.<br /><span>One native experience.</span></h1>
            <p class="hero-lede">
              Back up phone photos, sync files and notes, chat in Talk, and use
              more of your Nextcloud apps from one consistent client for phone
              and desktop.
            </p>
            <div class="hero-actions">
              <a class="button button-primary" :href="githubUrl" target="_blank" rel="noreferrer">
                Explore the source
                <ArrowRight :size="19" weight="bold" aria-hidden="true" />
              </a>
              <a class="button button-secondary" href="/roadmap/">
                Read the roadmap
              </a>
            </div>
            <p class="hero-note">Built by Obiente. Not affiliated with Nextcloud GmbH.</p>
          </div>

          <NativePreview />
        </section>

        <section id="approach" class="approach section-width">
          <div class="section-heading">
            <p class="eyebrow">One app for everyday Nextcloud</p>
            <h2>Spend less time jumping between apps.</h2>
            <p>
              Your photos, files, messages, notes, calendars, and other apps should
              share familiar navigation, previews, search, editing, and offline behavior.
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
              <p class="eyebrow">How unfamiliar apps can still feel native</p>
              <h2>The right screen for the information in front of you.</h2>
              <p>
                A less common Nextcloud app should not fall back to a technical data
                dump. Verified information can become a useful native screen without
                the app guessing permissions or unsafe actions.
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
              <p class="eyebrow">Files, Talk, Photos, and more</p>
              <h2>Your work should follow you across apps.</h2>
              <p>
                Open a file shared in Talk with the same preview and actions as Files.
                Find a person, note, photo, or calendar item from one search.
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
            <p class="eyebrow">Phone and desktop</p>
            <h2>At home on every device you use.</h2>
            <p>
              Behavior stays familiar while Android, iOS, Windows, macOS, and Linux
              keep control of their own files, notifications, background work, and calls.
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

        <section class="screenshots-section">
          <div class="section-width">
            <div class="section-heading">
              <p class="eyebrow">Built around real workflows</p>
              <h2>One workspace, without the web-wrapper seams.</h2>
              <p>
                See the same native workspace on a large screen and in your hand.
                These are real Compose screens rendered from a built-in demo account.
              </p>
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
              <p class="eyebrow">The work behind the promise</p>
              <h2>Read the project, not just the pitch.</h2>
              <p>
                The public documentation is built directly from the repository, so
                architecture decisions, current limitations and acceptance gates stay visible.
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
              <p class="eyebrow">What we are building</p>
              <h2>See Nextcloud Native taking shape.</h2>
            </div>
            <a class="text-link" href="/news/">All project news <ArrowRight :size="18" weight="bold" /></a>
          </div>
          <div class="news-grid">
            <a v-for="post in news.slice(0, 3)" :key="post.path" class="news-card" :href="post.path">
              <img :src="post.image" :alt="post.imageAlt" loading="lazy" />
              <time :datetime="post.date">{{ post.date }}</time>
              <h3>{{ post.title }}</h3>
              <p>{{ post.description }}</p>
              <span>{{ post.readingMinutes }} min read <ArrowRight :size="16" weight="bold" /></span>
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
            <p class="eyebrow">Built in the open</p>
            <h2>Help make the best Nextcloud client possible.</h2>
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
          <p class="article-release-link">
            Looking for concise version-by-version changes?
            <a href="/changelog/">Read the changelog</a>.
          </p>
        </article>
      </section>

      <section v-else-if="isNewsIndex" class="news-index section-width">
        <header class="doc-heading">
          <p class="eyebrow">Nextcloud Native news</p>
          <h1>What is getting better, and why it matters.</h1>
          <p>Start with the everyday benefit, then dig into the technical decisions if you want the detail.</p>
          <a class="text-link" href="/changelog/">
            Looking for release changes? Read the changelog
            <ArrowRight :size="18" weight="bold" />
          </a>
        </header>
        <div class="news-grid news-index-grid">
          <a v-for="post in news" :key="post.path" class="news-card" :href="post.path">
            <img :src="post.image" :alt="post.imageAlt" loading="lazy" />
            <time :datetime="post.lastUpdated">Updated {{ post.lastUpdated }}</time>
            <h2>{{ post.title }}</h2>
            <p>{{ post.description }}</p>
            <span>{{ post.readingMinutes }} min read <ArrowRight :size="16" weight="bold" /></span>
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
          <a class="doc-back" href="/#docs">Nextcloud Native documentation</a>
          <header class="doc-heading">
            <p class="eyebrow">Repository documentation</p>
            <h1>{{ currentDoc.title }}</h1>
            <p>{{ currentDoc.description }}</p>
            <span>{{ currentDoc.readingMinutes }} minute read · sourced from {{ currentDoc.file }}</span>
          </header>
          <RoadmapDashboard v-if="currentDoc.path === '/roadmap/'" />
          <div class="markdown-body" v-html="currentDoc.html"></div>
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
