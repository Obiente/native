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
    title: "Adaptive by design",
    body: "It reads app contracts, data shapes and actions, then composes a useful native experience.",
  },
  {
    icon: Sparkle,
    title: "Native where it matters",
    body: "Files, photos, messages, tables and media use reusable platform components, not embedded pages.",
  },
  {
    icon: ShieldCheck,
    title: "Your server stays yours",
    body: "Credentials and learned app knowledge stay local. There is no Obiente cloud in the middle.",
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
    title: "Discover the contract",
    body: "Capabilities, OpenAPI, app metadata and observed safe reads describe what the server can actually do.",
  },
  {
    icon: Stack,
    step: "02",
    title: "Compile the semantics",
    body: "Resources, fields, relations and actions become a typed platform-neutral native schema.",
  },
  {
    icon: Desktop,
    step: "03",
    title: "Compose the experience",
    body: "Reusable native components choose the right list, gallery, editor, table, conversation or dashboard.",
  },
];

const frequentlyAsked = [
  {
    question: "Is this a web wrapper?",
    answer:
      "No. Nextcloud Native consumes server APIs and renders native Compose interfaces. Web content is reserved for formats that genuinely require a document renderer, not app navigation.",
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
              Nextcloud Native is one adaptive client for your whole Nextcloud.
              It turns app data and actions into interfaces that feel built for
              your phone and desktop.
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
            <p class="eyebrow">A different kind of client</p>
            <h2>Native does not have to mean narrow.</h2>
            <p>
              Custom experiences stay possible, but shared semantics do the heavy lifting.
              New apps can inherit the right patterns without waiting for a one-off rewrite.
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
              <p class="eyebrow">From unknown API to useful UI</p>
              <h2>The interface is compiled, not guessed.</h2>
              <p>
                Deterministic discovery comes first. Semantic inference can improve the
                result, but it may never invent an endpoint, payload or permission.
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
              <p class="eyebrow">One coherent workspace</p>
              <h2>Apps should feel connected.</h2>
              <p>
                Shared search, previews, people, files and actions can move through the
                whole client instead of stopping at app boundaries.
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
            <p class="eyebrow">One core, platform-native edges</p>
            <h2>Designed beyond a single screen.</h2>
            <p>
              The shared runtime keeps behavior consistent. Each operating system still
              owns credentials, files, background work, notifications and calls.
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
                Explore representative Files, media backup, and Talk experiences.
                Every image below is generated from a committed synthetic fixture.
              </p>
            </div>
            <div class="screenshot-gallery">
              <figure>
                <img
                  src="/screenshots/files-workspace.svg"
                  alt="Synthetic Nextcloud Native Files workspace with folder list, preview inspector, and upload progress"
                  width="1200"
                  height="750"
                  loading="lazy"
                />
                <figcaption><strong>Files that behave like files</strong><span>Search, inspect, cache, share, and track transfers.</span></figcaption>
              </figure>
              <figure>
                <img
                  src="/screenshots/photos-timeline.svg"
                  alt="Synthetic Nextcloud Native photo timeline with backup status dashboard"
                  width="1200"
                  height="750"
                  loading="lazy"
                />
                <figcaption><strong>Media backup you can understand</strong><span>Visible local, pending, verified, and cloud-only state.</span></figcaption>
              </figure>
              <figure>
                <img
                  src="/screenshots/talk-conversation.svg"
                  alt="Synthetic Nextcloud Native Talk conversation with native conversation list and composer"
                  width="1200"
                  height="750"
                  loading="lazy"
                />
                <figcaption><strong>Talk as part of the workspace</strong><span>Messages, shared files, and calls in a native thread.</span></figcaption>
              </figure>
            </div>
            <p class="fixture-disclosure">
              Product direction preview. Synthetic names and data only, with no connected account or user media.
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
              <p class="eyebrow">Development notes</p>
              <h2>Follow the decisions behind the client.</h2>
            </div>
            <a class="text-link" href="/news/">All project news <ArrowRight :size="18" weight="bold" /></a>
          </div>
          <div class="news-grid">
            <a v-for="post in news.slice(0, 3)" :key="post.path" class="news-card" :href="post.path">
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
            <p class="eyebrow">Development note</p>
            <h1>{{ currentPost.title }}</h1>
            <p>{{ currentPost.description }}</p>
            <span><time :datetime="currentPost.date">{{ currentPost.date }}</time> · {{ currentPost.readingMinutes }} minute read</span>
            <div class="article-tags"><span v-for="tag in currentPost.tags" :key="tag">{{ tag }}</span></div>
          </header>
          <div class="markdown-body" v-html="currentPost.html"></div>
        </article>
      </section>

      <section v-else-if="isNewsIndex" class="news-index section-width">
        <header class="doc-heading">
          <p class="eyebrow">Nextcloud Native news</p>
          <h1>Building in the open.</h1>
          <p>Engineering notes, product decisions, and honest progress from the independent open-source client.</p>
        </header>
        <div class="news-grid news-index-grid">
          <a v-for="post in news" :key="post.path" class="news-card" :href="post.path">
            <time :datetime="post.date">{{ post.date }}</time>
            <h2>{{ post.title }}</h2>
            <p>{{ post.description }}</p>
            <span>{{ post.readingMinutes }} min read <ArrowRight :size="16" weight="bold" /></span>
          </a>
        </div>
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
        <a href="/security/">Security</a>
        <a :href="`${githubUrl}/blob/main/LICENSE`">License</a>
      </div>
    </footer>
  </div>
</template>
