<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  PhAndroidLogo as AndroidLogo,
  PhAppleLogo as AppleLogo,
  PhArrowRight as ArrowRight,
  PhBookOpen as BookOpen,
  PhCaretDown as CaretDown,
  PhCamera as Camera,
  PhChatCircleDots as ChatCircleDots,
  PhCloudCheck as CloudCheck,
  PhCode as Code,
  PhDesktop as Desktop,
  PhDeviceMobile as DeviceMobile,
  PhFile as File,
  PhGitBranch as GitBranch,
  PhGithubLogo as GithubLogo,
  PhLinuxLogo as LinuxLogo,
  PhListChecks as ListChecks,
  PhList as Menu,
  PhMagnifyingGlass as MagnifyingGlass,
  PhMoon as Moon,
  PhShieldCheck as ShieldCheck,
  PhSquaresFour as SquaresFour,
  PhSun as Sun,
  PhWindowsLogo as WindowsLogo,
  PhX as X,
} from "@phosphor-icons/vue";
import { docs } from "./generated/docs.js";
import { news } from "./generated/news.js";
import { changelog } from "./generated/changelog.js";
import { marketingCaptures } from "./generated/captures.js";
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
const captureByScenario = new Map(
  marketingCaptures.map((capture) => [capture.scenario, capture]),
);
const initialTheme = typeof window === "undefined"
  ? { preference: "system", system: "dark" }
  : window.__NEXTCLOUD_NATIVE_THEME__ ?? { preference: "system", system: "dark" };
const themePreference = ref(initialTheme.preference);
const systemTheme = ref(initialTheme.system);
const resolvedTheme = computed(() =>
  themePreference.value === "system" ? systemTheme.value : themePreference.value,
);
const themeOptions = ["system", "light", "dark"];
const themeLabel = computed(() =>
  themePreference.value === "system"
    ? `System (${resolvedTheme.value})`
    : themePreference.value[0].toUpperCase() + themePreference.value.slice(1),
);
const themeIcon = computed(() => {
  if (themePreference.value === "light") return Sun;
  if (themePreference.value === "dark") return Moon;
  return Desktop;
});
let themeMediaQuery;
let themeMediaListener;
let revealObserver;
const motionEnhanced = ref(false);

function applyDocumentTheme() {
  if (typeof document === "undefined") return;
  document.documentElement.dataset.theme = resolvedTheme.value;
  document.documentElement.style.colorScheme = resolvedTheme.value;
}

function cycleTheme() {
  const currentIndex = themeOptions.indexOf(themePreference.value);
  themePreference.value = themeOptions[(currentIndex + 1) % themeOptions.length];
}

onMounted(() => {
  const savedTheme = window.localStorage.getItem("nextcloud-native-theme");
  if (themeOptions.includes(savedTheme)) themePreference.value = savedTheme;
  themeMediaQuery = window.matchMedia("(prefers-color-scheme: light)");
  themeMediaListener = (event) => {
    systemTheme.value = event.matches ? "light" : "dark";
  };
  systemTheme.value = themeMediaQuery.matches ? "light" : "dark";
  themeMediaQuery.addEventListener("change", themeMediaListener);
  applyDocumentTheme();

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  if (!reducedMotion.matches && "IntersectionObserver" in window) {
    motionEnhanced.value = true;
    nextTick(() => {
      revealObserver = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            if (!entry.isIntersecting) continue;
            entry.target.classList.add("is-visible");
            revealObserver.unobserve(entry.target);
          }
        },
        { rootMargin: "0px 0px -10%", threshold: 0.12 },
      );
      document.querySelectorAll("[data-reveal]").forEach((element) => {
        revealObserver.observe(element);
      });
    });
  }
});

onBeforeUnmount(() => {
  themeMediaQuery?.removeEventListener("change", themeMediaListener);
  revealObserver?.disconnect();
});

watch(themePreference, (preference) => {
  if (typeof window !== "undefined") {
    window.localStorage.setItem("nextcloud-native-theme", preference);
  }
});
watch(resolvedTheme, applyDocumentTheme);

function homepageCapture(darkScenario, lightScenario, fallbackScenario) {
  const scenario = resolvedTheme.value === "light" ? lightScenario : darkScenario;
  return captureByScenario.get(scenario) ?? captureByScenario.get(fallbackScenario);
}

function newsCapture(post) {
  if (resolvedTheme.value === "light") {
    return post.websiteImageLight ?? post.websiteImage;
  }
  return post.websiteImageDark ?? post.websiteImage;
}

const heroDesktopCapture = computed(() =>
  homepageCapture(
    "homepage-overview-desktop-dark",
    "homepage-overview-desktop-light",
    "desktop-home",
  ),
);
const mobileHomeCapture = computed(() =>
  homepageCapture(
    "homepage-overview-mobile-dark",
    "homepage-overview-mobile-light",
    "mobile-home",
  ),
);
const platformDesktopCapture = computed(() =>
  homepageCapture(
    "homepage-files-desktop-dark",
    "homepage-files-desktop-light",
    "obsidian-vault-sync",
  ),
);
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
const isVisualQa = computed(() => normalizedPath === "/visual-qa/");
const isHome = computed(() => normalizedPath === "/");
const mobileNavOpen = ref(false);
const visualQaPlatform = ref("all");
const visualQaPurpose = ref("all");
const visualQaPullRequest = ref("all");
const visualQaPurposes = [
  { value: "all", label: "All states" },
  { value: "showcase", label: "Showcase" },
  { value: "state-coverage", label: "Loading and error states" },
];
const visualQaPlatforms = computed(() => [
  "all",
  ...new Set(marketingCaptures.map((capture) => capture.platform)),
]);
const visualQaPullRequests = computed(() => [
  "all",
  "unlinked",
  ...new Set(
    marketingCaptures
      .map((capture) => capture.pullRequest)
      .filter((pullRequest) => Number.isInteger(pullRequest))
      .map(String),
  ),
]);
const visibleVisualQaCaptures = computed(() =>
  marketingCaptures.filter(
    (capture) =>
      (visualQaPurpose.value === "all" ||
        capture.purpose === visualQaPurpose.value) &&
      (visualQaPlatform.value === "all" ||
        capture.platform === visualQaPlatform.value) &&
      (visualQaPullRequest.value === "all" ||
        (visualQaPullRequest.value === "unlinked"
          ? capture.pullRequest === undefined
          : String(capture.pullRequest) === visualQaPullRequest.value)),
  ),
);
const visualQaGroups = computed(() => {
  const groups = new Map();
  for (const capture of visibleVisualQaCaptures.value) {
    const key = capture.pullRequest === undefined
      ? "Baseline catalog"
      : `Pull request #${capture.pullRequest}`;
    const entries = groups.get(key) ?? [];
    entries.push(capture);
    groups.set(key, entries);
  }
  return [...groups.entries()].map(([label, captures]) => ({ label, captures }));
});
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

const nativePromises = [
  {
    icon: SquaresFour,
    title: "Your apps are all there",
    body: "Files, Photos, Talk, Calendar, Mail, Notes, Deck, and the other apps on your server live under the same account.",
  },
  {
    icon: Desktop,
    title: "It uses the device you are holding",
    body: "A phone gets touch controls and share sheets. A desktop gets resizable panes, keyboard navigation, context menus, and drag and drop.",
  },
  {
    icon: ShieldCheck,
    title: "It stops before it guesses",
    body: "Originals are kept, conflicts are shown, and a write is withheld when the server contract is unclear.",
  },
];

const appFamilies = [
  {
    icon: File,
    title: "Files and documents",
    apps: "Files, Notes, Office, search, sharing, versions, offline folders, and two-way sync",
    body: "Browse, edit, share, recover, and synchronize ordinary files through native pickers and file managers. Offline work remains explicit and conflicts never disappear behind a generic success message.",
    captureDark: "homepage-files-desktop-dark",
    captureLight: "homepage-files-desktop-light",
    captureFallback: "obsidian-vault-sync",
  },
  {
    icon: Camera,
    title: "Photos and memories",
    apps: "Photos, Memories, Recognize, albums, Live Photos, backup, sharing, and non-destructive editing",
    body: "Move from a timeline to albums, recognized people, full-quality originals, edits, and verified backup without leaving the same native media library.",
    captureDark: "homepage-photos-desktop-dark",
    captureLight: "homepage-photos-desktop-light",
    captureFallback: "photo-folder-browser-desktop",
  },
  {
    icon: ChatCircleDots,
    title: "Conversations and people",
    apps: "Talk messages and calls, Mail, Contacts, shared files, notifications, and presence",
    body: "Messages, calls, mail, contacts, and shared files retain their account and object context, with system notifications and communication controls where the platform provides them.",
    captureDark: "homepage-conversations-desktop-dark",
    captureLight: "homepage-conversations-desktop-light",
    captureFallback: "file-share-group-desktop",
  },
  {
    icon: ListChecks,
    title: "Planning and everyday work",
    apps: "Calendar, Tasks, Deck, Tables, Cookbook, Cospend, Music, dashboards, and administration",
    body: "Work with events, tasks, boards, tables, recipes, budgets, music, and server administration through interfaces suited to the job rather than a universal list of fields.",
    captureDark: "homepage-planning-desktop-dark",
    captureLight: "homepage-planning-desktop-light",
    captureFallback: "deck-board-desktop",
  },
  {
    icon: SquaresFour,
    title: "The apps on your server",
    apps: "Verified app capabilities become useful native tables, forms, galleries, boards, conversations, and media views",
    body: "Installed apps are interpreted through verified contracts and reusable native components. Specialized adapters improve workflows where generic semantics are not enough.",
    captureDark: "homepage-apps-desktop-dark",
    captureLight: "homepage-apps-desktop-light",
    captureFallback: "adaptive-dynamic-data",
  },
];
const activeAppFamily = ref(0);
const selectedAppFamily = computed(() => appFamilies[activeAppFamily.value]);
const selectedAppCapture = computed(() =>
  homepageCapture(
    selectedAppFamily.value.captureDark,
    selectedAppFamily.value.captureLight,
    selectedAppFamily.value.captureFallback,
  ),
);

const platforms = [
  {
    icon: Desktop,
    name: "Mobile and tablet",
    body: "Android, iPhone, and iPad use touch-first navigation, native sharing, background transfer, notifications, media controls, file providers, and layouts that adapt from a phone to a large screen.",
  },
  {
    icon: Code,
    name: "Desktop",
    body: "Linux, Windows, and macOS use resizable multi-pane workspaces, keyboard navigation, context menus, drag and drop, native file access, system notifications, and desktop media controls.",
  },
];

const adaptiveSteps = [
  {
    icon: CloudCheck,
    label: "Server",
    title: "Ask what is actually available",
    body: "The app discovers the exact features, versions, permissions, and installed apps your Nextcloud makes available.",
  },
  {
    icon: Code,
    label: "Contract",
    title: "Check before doing anything",
    body: "Verified contracts turn files, dates, people, messages, rows, media, relationships, and actions into typed concepts.",
  },
  {
    icon: Desktop,
    label: "Interface",
    title: "Use the right native view",
    body: "The result becomes a gallery, editor, table, conversation, calendar, board, or dashboard integrated with the operating system.",
  },
];

const frequentlyAsked = [
  {
    question: "Is this a web wrapper?",
    answer:
      "No. Nextcloud Native consumes server APIs and renders native Compose interfaces. Web content is reserved for formats that genuinely require a document renderer, not app navigation.",
  },
  {
    question: "Can I keep normal folders and an Obsidian vault in sync?",
    answer:
      "Yes. Folder pairs connect a normal device folder with a Nextcloud folder, keep files visible to Obsidian and other editors, work offline, and preserve both versions when changes conflict.",
  },
  {
    question: "Can it back up photos and safely make space on my device?",
    answer:
      "Yes. Backup distinguishes waiting, uploading, verified, changed, failed, and cloud-only files. Storage cleanup is offered only for the exact file version verified on your selected server.",
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
  <div
    class="site-shell"
    :class="{ 'motion-enhanced': motionEnhanced, 'is-home': isHome }"
    :data-theme="resolvedTheme"
  >
    <a class="skip-link" href="#main">Skip to content</a>

    <header class="site-header">
      <a class="brand" href="/" aria-label="Nextcloud Native home">
        <span class="brand-mark">
          <img src="/cloud.svg" alt="" width="28" height="28" />
        </span>
        <span class="brand-copy"><strong>Nextcloud Native</strong><small>by Obiente</small></span>
      </a>

      <nav class="desktop-nav" aria-label="Primary navigation">
        <a href="/#experience">Experience</a>
        <a href="/#apps">Apps</a>
        <a href="/#native">How it works</a>
        <a href="/roadmap/">Roadmap</a>
        <a href="/news/">Journal</a>
        <a href="/#docs">Docs</a>
      </nav>

      <div class="header-actions">
        <button
          class="mobile-menu-button"
          type="button"
          aria-label="Toggle primary navigation"
          aria-controls="mobile-site-navigation"
          :aria-expanded="mobileNavOpen"
          @click="mobileNavOpen = !mobileNavOpen"
        >
          <X v-if="mobileNavOpen" :size="21" weight="bold" aria-hidden="true" />
          <Menu v-else :size="21" weight="bold" aria-hidden="true" />
        </button>
        <button
          class="theme-toggle"
          type="button"
          :aria-label="`Theme: ${themeLabel}. Activate to change theme.`"
          :title="`Theme: ${themeLabel}`"
          @click="cycleTheme"
        >
          <component :is="themeIcon" :size="19" weight="bold" aria-hidden="true" />
          <span>{{ themeLabel }}</span>
        </button>
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

      <nav
        v-if="mobileNavOpen"
        id="mobile-site-navigation"
        class="mobile-nav"
        aria-label="Mobile primary navigation"
        @click="mobileNavOpen = false"
      >
        <a href="/#experience">Experience</a>
        <a href="/#apps">Apps</a>
        <a href="/#native">How it works</a>
        <a href="/roadmap/">Roadmap</a>
        <a href="/news/">Journal</a>
        <a href="/#docs">Docs</a>
      </nav>
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
        <div class="home-page">
          <section class="product-hero section-width">
            <div class="product-hero-copy">
              <p class="eyebrow">An independent client by Obiente</p>
              <h1>Your Nextcloud deserves <span>a real app.</span></h1>
              <p class="hero-lede">
                Use Files, Photos, Talk, Calendar, and the apps installed on your
                server from one client on phone, tablet, and desktop.
              </p>
              <div class="hero-actions">
                <a class="button button-primary" href="https://github.com/Obiente/nc-native/releases" target="_blank" rel="noreferrer">
                  Get Nextcloud Native
                  <ArrowRight :size="19" weight="bold" aria-hidden="true" />
                </a>
                <a class="button button-secondary" href="/#experience">See how it works</a>
              </div>
            </div>

            <figure class="product-hero-visual">
              <img
                class="product-hero-desktop"
                :src="heroDesktopCapture.websitePath"
                alt="Nextcloud Native desktop home view with account status, recent files, activity, events, and photo backup"
                :width="heroDesktopCapture.width"
                :height="heroDesktopCapture.height"
              />
              <img
                class="product-hero-mobile"
                :src="mobileHomeCapture.websitePath"
                alt="Nextcloud Native mobile home with files, conversations, events, and sync status"
                :width="mobileHomeCapture.width"
                :height="mobileHomeCapture.height"
              />
              <figcaption>
                <span>The same account, shaped for desktop and mobile.</span>
                <span class="capture-provenance">
                  <ShieldCheck :size="15" weight="fill" aria-hidden="true" />
                  Real native UI. Synthetic private data.
                </span>
              </figcaption>
            </figure>

            <div class="hero-foundation" aria-label="Availability and project details">
              <div class="hero-foundation-platforms">
                <div class="hero-availability">
                  <p>Available for</p>
                  <ul>
                    <li><WindowsLogo :size="21" weight="fill" aria-hidden="true" /><span>Windows</span></li>
                    <li class="platform-pending"><AppleLogo :size="21" weight="fill" aria-hidden="true" /><span>macOS</span></li>
                    <li><LinuxLogo :size="21" weight="fill" aria-hidden="true" /><span>Linux</span></li>
                    <li><AndroidLogo :size="21" weight="fill" aria-hidden="true" /><span>Android</span></li>
                    <li class="platform-pending"><DeviceMobile :size="21" weight="fill" aria-hidden="true" /><span>iOS</span></li>
                  </ul>
                </div>
              </div>

              <ul class="hero-trust">
                <li>
                  <GithubLogo :size="21" weight="fill" aria-hidden="true" />
                  <span>
                    <strong>Open source</strong>
                    <small>
                      AGPL-3.0-or-later
                      <a :href="githubUrl" target="_blank" rel="noreferrer">View source</a>
                    </small>
                  </span>
                </li>
              </ul>

              <div class="hero-project-note">
                <p class="maker-note">
                  Built by <strong>Obiente</strong>. Independent and <span class="no-break">community-driven</span>.
                </p>
                <nav class="hero-release-links" aria-label="Project downloads and updates">
                  <a href="https://github.com/Obiente/nc-native/releases" target="_blank" rel="noreferrer">Packages on GitHub</a>
                  <a href="/changelog/">Release notes <ArrowRight :size="14" weight="bold" aria-hidden="true" /></a>
                </nav>
              </div>
            </div>
          </section>

          <section id="experience" class="native-promise section-width" data-reveal>
            <div class="section-heading compact">
              <p class="eyebrow">Everyday work</p>
              <h2>Open a file. Share a photo. Answer a call.</h2>
              <p>
                Nextcloud Native uses your device's file picker, share sheet,
                notifications, background work, keyboard, and touch controls. Your
                account and permissions stay with you as you move between them.
              </p>
            </div>

            <div class="native-promise-list">
              <article v-for="item in nativePromises" :key="item.title">
                <span class="feature-icon">
                  <component :is="item.icon" :size="24" weight="duotone" aria-hidden="true" />
                </span>
                <div><h3>{{ item.title }}</h3><p>{{ item.body }}</p></div>
              </article>
            </div>
          </section>

          <section id="apps" class="app-story" data-reveal>
            <div class="section-width app-story-layout">
              <div class="section-heading compact">
                <p class="eyebrow">Your apps</p>
                <h2>Each app keeps the shape that makes it useful.</h2>
                <p>
                  Mail is a mailbox, Deck is a board, Tables is a table, and
                  Memories is a photo library. Shared native building blocks make
                  navigation and actions predictable without flattening everything
                  into a generic data screen.
                </p>
                <a class="text-link" href="/compatibility/">Explore the app model <ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
              </div>

              <div class="app-showcase">
                <figure class="app-showcase-visual">
                  <div class="app-showcase-meta">
                    <span>
                      <small>Product view</small>
                      <strong>{{ selectedAppFamily.title }}</strong>
                    </span>
                    <span class="capture-provenance">
                      <ShieldCheck :size="15" weight="fill" aria-hidden="true" />
                      Real Compose UI
                    </span>
                  </div>
                  <div
                    class="app-showcase-capture"
                    :style="{ aspectRatio: `${selectedAppCapture.width} / ${selectedAppCapture.height}` }"
                  >
                    <Transition name="capture-swap">
                      <img
                        :key="selectedAppCapture.scenario"
                        :src="selectedAppCapture.websitePath"
                        :alt="`${selectedAppFamily.title} shown in the real Nextcloud Native Compose interface with synthetic data`"
                        :width="selectedAppCapture.width"
                        :height="selectedAppCapture.height"
                      />
                    </Transition>
                  </div>
                  <figcaption>
                    <span>{{ selectedAppFamily.apps }}</span>
                    <span>Synthetic data, captured from the app.</span>
                  </figcaption>
                </figure>

                <div class="app-family-list">
                  <article
                    v-for="(family, index) in appFamilies"
                    :key="family.title"
                    :class="{ active: activeAppFamily === index }"
                  >
                    <button
                      type="button"
                      :aria-expanded="activeAppFamily === index"
                      @click="activeAppFamily = index"
                    >
                      <component :is="family.icon" :size="24" weight="duotone" aria-hidden="true" />
                      <span>{{ family.title }}</span>
                      <CaretDown :size="18" weight="bold" aria-hidden="true" />
                    </button>
                    <Transition name="accordion-detail">
                      <div v-if="activeAppFamily === index" class="app-family-detail">
                        <p>{{ family.body }}</p>
                      </div>
                    </Transition>
                  </article>
                </div>
              </div>
            </div>
          </section>

          <section id="native" class="native-method section-width" data-reveal>
            <div class="native-method-intro">
              <p class="eyebrow">How it works</p>
              <h2>Native is a behavior, not a coat of paint.</h2>
              <p>
                Nextcloud Native reads verified capabilities and versioned contracts,
                understands the work they represent, and chooses a useful native
                interface. It never invents an endpoint or passes an embedded website
                off as an app.
              </p>
              <a class="text-link" href="/architecture/">Read the architecture <ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
            </div>

            <ol class="native-method-steps">
              <li v-for="(item, index) in adaptiveSteps" :key="item.label">
                <div class="method-step-heading">
                  <component :is="item.icon" :size="24" weight="duotone" aria-hidden="true" />
                  <span>{{ item.label }}</span>
                </div>
                <div><h3>{{ item.title }}</h3><p>{{ item.body }}</p></div>
                <ArrowRight
                  v-if="index < adaptiveSteps.length - 1"
                  class="method-step-arrow"
                  :size="19"
                  weight="bold"
                  aria-hidden="true"
                />
              </li>
            </ol>
          </section>

          <section class="platform-story section-width" data-reveal>
            <div class="section-heading compact">
              <p class="eyebrow">Every device</p>
              <h2>Built for each device, not merely resized.</h2>
              <p>
                Your data follows the same safety and sync rules everywhere. Each
                platform still gets the layout, controls, lifecycle, and system
                integration that belong there.
              </p>
            </div>
            <div class="platform-story-content">
              <figure class="platform-story-visual">
                <img
                  class="platform-story-desktop"
                  :src="platformDesktopCapture.websitePath"
                  alt="Nextcloud Native Files on desktop with folders, photos, documents, and offline availability"
                  :width="platformDesktopCapture.width"
                  :height="platformDesktopCapture.height"
                  loading="lazy"
                />
                <img
                  class="platform-story-mobile"
                  :src="mobileHomeCapture.websitePath"
                  alt="Nextcloud Native home on mobile"
                  :width="mobileHomeCapture.width"
                  :height="mobileHomeCapture.height"
                  loading="lazy"
                />
                <figcaption>
                  <span>One account. Separate layouts for the screen in front of you.</span>
                  <span class="capture-provenance"><ShieldCheck :size="15" weight="fill" aria-hidden="true" />Real Compose UI</span>
                </figcaption>
              </figure>

              <div class="platform-story-list">
                <article v-for="platform in platforms" :key="platform.name">
                  <component :is="platform.icon" :size="25" weight="duotone" aria-hidden="true" />
                  <div><h3>{{ platform.name }}</h3><p>{{ platform.body }}</p></div>
                </article>
              </div>
            </div>
          </section>

          <section class="project-links section-width" data-reveal>
            <div>
              <p class="eyebrow">Built in the open</p>
              <h2>The work is public.</h2>
            </div>
            <a href="/roadmap/"><GitBranch :size="22" weight="duotone" aria-hidden="true" /><span><strong>Roadmap</strong><small>Priorities, milestones, and delivery</small></span><ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
            <a href="/news/"><BookOpen :size="22" weight="duotone" aria-hidden="true" /><span><strong>Journal</strong><small>Product decisions and deeper stories</small></span><ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
            <a :href="githubUrl" target="_blank" rel="noreferrer"><GithubLogo :size="22" weight="duotone" aria-hidden="true" /><span><strong>Source</strong><small>Code, issues, and contribution</small></span><ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
          </section>

          <section id="docs" class="docs-section" data-reveal>
            <div class="section-width docs-layout">
              <div class="section-heading compact">
                <p class="eyebrow">Documentation</p>
                <h2>Read how the product is put together.</h2>
                <p>
                  Architecture, security decisions, compatibility rules, and
                  contribution guides live beside the source they describe.
                </p>
              </div>
              <nav class="docs-index" aria-label="Product documentation">
                <a v-for="doc in docs.slice(0, 6)" :key="doc.path" :href="doc.path">
                  <BookOpen :size="24" weight="duotone" aria-hidden="true" />
                  <span><strong>{{ doc.shortTitle }}</strong><small>{{ doc.description }}</small></span>
                  <span class="read-time">{{ doc.readingMinutes }} min</span>
                  <ArrowRight :size="17" weight="bold" aria-hidden="true" />
                </a>
              </nav>
            </div>
          </section>

          <section class="news-section section-width" data-reveal>
            <div class="news-heading">
              <div class="section-heading compact">
                <p class="eyebrow">Journal</p>
                <h2>Notes from building the client.</h2>
              </div>
              <a class="text-link" href="/news/">All project news <ArrowRight :size="18" weight="bold" /></a>
            </div>

            <div class="journal-layout">
              <a v-if="news[0]" class="journal-feature" :href="news[0].path">
                <div class="journal-feature-media">
                  <img
                    :src="newsCapture(news[0])"
                    :alt="news[0].imageAlt"
                    :width="news[0].imageWidth"
                    :height="news[0].imageHeight"
                    loading="lazy"
                  />
                </div>
                <div class="journal-feature-copy">
                  <time :datetime="news[0].date">{{ news[0].date }}</time>
                  <h3>{{ news[0].title }}</h3>
                  <p>{{ news[0].description }}</p>
                  <span>{{ news[0].readingMinutes }} min read <ArrowRight :size="16" weight="bold" aria-hidden="true" /></span>
                </div>
              </a>

              <div class="journal-briefs">
                <a v-for="post in news.slice(1, 3)" :key="post.path" :href="post.path">
                  <div>
                    <time :datetime="post.date">{{ post.date }}</time>
                    <h3>{{ post.title }}</h3>
                    <p>{{ post.description }}</p>
                    <span>{{ post.readingMinutes }} min read <ArrowRight :size="15" weight="bold" aria-hidden="true" /></span>
                  </div>
                  <img
                    :src="newsCapture(post)"
                    :alt="post.imageAlt"
                    :width="post.imageWidth"
                    :height="post.imageHeight"
                    loading="lazy"
                  />
                </a>
              </div>
            </div>
          </section>

          <section class="faq-section section-width">
            <div class="section-heading compact">
              <p class="eyebrow">Straight answers</p>
              <h2>Before you connect a server.</h2>
            </div>
            <div class="faq-list">
              <details v-for="(item, index) in frequentlyAsked" :key="item.question" :open="index === 0">
                <summary>{{ item.question }}</summary>
                <p>{{ item.answer }}</p>
              </details>
            </div>
          </section>

          <section class="contribute section-width">
            <div>
              <p class="eyebrow">AGPL-3.0 open source</p>
              <h2>Use it. Read it. Help shape it.</h2>
              <p>
                Run the clients, test your server and apps, inspect the architecture,
                or bring a platform integration you care about.
              </p>
            </div>
            <nav class="contribute-links" aria-label="Contribute to Nextcloud Native">
              <a href="/contributing/">Contribution guide <ArrowRight :size="17" weight="bold" aria-hidden="true" /></a>
              <a :href="githubUrl" target="_blank" rel="noreferrer">Browse the source <GithubLogo :size="17" weight="fill" aria-hidden="true" /></a>
              <a :href="`${githubUrl}/issues`" target="_blank" rel="noreferrer">Open issues <ArrowRight :size="17" weight="bold" aria-hidden="true" /></a>
            </nav>
          </section>
        </div>
      </template>

      <section v-else-if="isVisualQa" class="visual-qa-page section-width">
        <header class="doc-heading visual-qa-heading">
          <p class="eyebrow">Synthetic Compose catalog</p>
          <h1>Visual QA</h1>
          <p>
            Review deterministic screenshots rendered from the application UI.
            The catalog uses synthetic data and does not connect to a personal
            Nextcloud account.
          </p>
          <div class="page-record">
            <span><strong>Renderer</strong> Compose ImageComposeScene</span>
            <span><strong>Catalog</strong> {{ marketingCaptures.length }} registered captures</span>
            <a href="/screenshots/capture-manifest.json" target="_blank" rel="noreferrer">
              Inspect capture manifest
              <ArrowRight :size="14" weight="bold" aria-hidden="true" />
            </a>
          </div>
          <div class="visual-qa-filter-set">
            <span>State</span>
            <div
              class="visual-qa-filters"
              role="group"
              aria-label="Filter captures by state purpose"
            >
              <button
                v-for="purpose in visualQaPurposes"
                :key="purpose.value"
                type="button"
                :class="{ active: visualQaPurpose === purpose.value }"
                :aria-pressed="visualQaPurpose === purpose.value"
                @click="visualQaPurpose = purpose.value"
              >
                {{ purpose.label }}
              </button>
            </div>
          </div>
          <div class="visual-qa-filter-set">
            <span>Platform</span>
            <div
              class="visual-qa-filters"
              role="group"
              aria-label="Filter captures by platform"
            >
              <button
                v-for="platform in visualQaPlatforms"
                :key="platform"
                type="button"
                :class="{ active: visualQaPlatform === platform }"
                :aria-pressed="visualQaPlatform === platform"
                @click="visualQaPlatform = platform"
              >
                {{ platform === "all" ? "All platforms" : platform }}
              </button>
            </div>
          </div>
          <div class="visual-qa-filter-set">
            <span>Review source</span>
            <div
              class="visual-qa-filters"
              role="group"
              aria-label="Filter captures by pull request"
            >
              <button
                v-for="pullRequest in visualQaPullRequests"
                :key="pullRequest"
                type="button"
                :class="{ active: visualQaPullRequest === pullRequest }"
                :aria-pressed="visualQaPullRequest === pullRequest"
                @click="visualQaPullRequest = pullRequest"
              >
                {{
                  pullRequest === "all"
                    ? "All pull requests"
                    : pullRequest === "unlinked"
                      ? "Baseline catalog"
                      : `PR #${pullRequest}`
                }}
              </button>
            </div>
          </div>
        </header>

        <p class="sr-only" aria-live="polite">
          {{ visualQaGroups.reduce((total, group) => total + group.captures.length, 0) }}
          captures match the selected filters.
        </p>
        <div v-if="visualQaGroups.length" class="visual-qa-groups">
          <section
            v-for="group in visualQaGroups"
            :key="group.label"
            class="visual-qa-group"
          >
            <header>
              <h2>{{ group.label }}</h2>
              <span>{{ group.captures.length }} captures</span>
            </header>
            <div class="visual-qa-grid">
              <figure
                v-for="capture in group.captures"
                :key="capture.scenario"
                class="visual-qa-card"
              >
                <a
                  class="visual-qa-image"
                  :href="capture.websitePath"
                  target="_blank"
                  rel="noreferrer"
                  :aria-label="`Open ${capture.scenario} at full size`"
                >
                  <img
                    :src="capture.websitePath"
                    :alt="`${capture.feature} ${capture.surface}: ${capture.state}`"
                    :width="capture.width"
                    :height="capture.height"
                    loading="lazy"
                  />
                </a>
                <figcaption>
                  <div>
                    <span class="visual-qa-feature">{{ capture.feature }}</span>
                    <strong>{{ capture.surface }}</strong>
                    <p>{{ capture.state }}</p>
                  </div>
                  <dl>
                    <div><dt>Purpose</dt><dd>{{ capture.purpose }}</dd></div>
                    <div><dt>Scenario</dt><dd>{{ capture.scenario }}</dd></div>
                    <div><dt>Platform</dt><dd>{{ capture.platform }}</dd></div>
                    <div><dt>Viewport</dt><dd>{{ capture.viewport }}</dd></div>
                    <div><dt>Pixels</dt><dd>{{ capture.width }} x {{ capture.height }}</dd></div>
                    <div v-if="capture.pullRequest">
                      <dt>Review</dt>
                      <dd>
                        <a
                          :href="`${githubUrl}/pull/${capture.pullRequest}`"
                          target="_blank"
                          rel="noreferrer"
                        >
                          PR #{{ capture.pullRequest }}
                        </a>
                      </dd>
                    </div>
                    <div v-if="capture.issue">
                      <dt>Issue</dt>
                      <dd>
                        <a
                          :href="`${githubUrl}/issues/${capture.issue}`"
                          target="_blank"
                          rel="noreferrer"
                        >
                          #{{ capture.issue }}
                        </a>
                      </dd>
                    </div>
                  </dl>
                  <a
                    class="visual-qa-full-size"
                    :href="capture.websitePath"
                    target="_blank"
                    rel="noreferrer"
                  >
                    Open full-size PNG
                  </a>
                </figcaption>
              </figure>
            </div>
          </section>
        </div>
        <p v-else class="visual-qa-empty">No captures match these filters.</p>
      </section>

      <section
        v-else-if="currentPost"
        class="article-page section-width"
      >
        <article class="news-article">
          <a class="doc-back" href="/news/">Project news</a>
          <header class="doc-heading" data-reveal>
            <p class="eyebrow">Product story</p>
            <h1>{{ currentPost.title }}</h1>
            <p>{{ currentPost.description }}</p>
            <div class="page-record">
              <span>
                <strong>Published</strong>
                <time :datetime="currentPost.date">{{ currentPost.date }}</time>
              </span>
              <span>
                <strong>Updated</strong>
                <time :datetime="currentPost.lastUpdated">{{ currentPost.lastUpdated }}</time>
              </span>
              <span><strong>Reading time</strong> {{ currentPost.readingMinutes }} minutes</span>
              <a
                :href="`${githubUrl}/blob/main/website/content/news/${currentPost.file}`"
                target="_blank"
                rel="noreferrer"
              >
                View article source
                <GithubLogo :size="14" weight="fill" aria-hidden="true" />
              </a>
            </div>
            <ul class="article-tags" aria-label="Article topics">
              <li v-for="tag in currentPost.tags" :key="tag">{{ tag }}</li>
            </ul>
          </header>
          <figure class="article-hero" data-reveal>
            <img
              :src="newsCapture(currentPost)"
              :alt="currentPost.imageAlt"
              :width="currentPost.imageWidth"
              :height="currentPost.imageHeight"
            />
            <figcaption>{{ currentPost.imageCaption }}</figcaption>
          </figure>
          <div class="markdown-body" data-reveal v-html="currentPost.html"></div>
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
        <header class="doc-heading" data-reveal>
          <p class="eyebrow">Nextcloud Native news</p>
          <h1>What your Nextcloud can do in one native client.</h1>
          <p>Practical guides explain everyday workflows, the technology behind them, and the public roadmap for each area.</p>
          <div class="page-record">
            <span><strong>Stories</strong> {{ news.length }} maintained guides</span>
            <span><strong>Latest review</strong> {{ news[0]?.lastUpdated }}</span>
            <a :href="`${githubUrl}/tree/main/website/content/news`" target="_blank" rel="noreferrer">
              Browse article sources
              <GithubLogo :size="14" weight="fill" aria-hidden="true" />
            </a>
          </div>
          <a class="text-link" href="/changelog/">
            Looking for release changes? Read the changelog
            <ArrowRight :size="18" weight="bold" />
          </a>
        </header>
        <div class="news-grid news-index-grid">
          <a v-for="post in news" :key="post.path" class="news-card" :href="post.path" data-reveal>
            <div class="news-card-media">
              <img
                :src="newsCapture(post)"
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
          <header class="doc-heading" data-reveal>
            <p class="eyebrow">Release history</p>
            <h1>{{ changelog.title }}</h1>
            <p>{{ changelog.description }}</p>
            <div class="page-record">
              <span><strong>Source</strong> {{ changelog.file }}</span>
              <span v-if="!changelog.available"><strong>Status</strong> Awaiting the first public release</span>
              <a :href="`${githubUrl}/blob/main/${changelog.file}`" target="_blank" rel="noreferrer">
                View source history
                <GithubLogo :size="14" weight="fill" aria-hidden="true" />
              </a>
            </div>
          </header>
          <div class="markdown-body" data-reveal v-html="changelog.html"></div>
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
            <header class="roadmap-route-heading" data-reveal>
              <p class="eyebrow">Product roadmap</p>
              <h1>Roadmap</h1>
              <p>
                Release targets and feature work linked directly to the public GitHub project.
              </p>
            </header>
            <RoadmapDashboard data-reveal />
            <details class="roadmap-source-document">
              <summary>Read the detailed product and engineering roadmap</summary>
              <div class="markdown-body" v-html="currentDoc.html"></div>
            </details>
          </template>
          <template v-else>
            <a class="doc-back" href="/#docs">Nextcloud Native documentation</a>
            <header class="doc-heading" data-reveal>
              <p class="eyebrow">Repository documentation</p>
              <h1>{{ currentDoc.title }}</h1>
              <p>{{ currentDoc.description }}</p>
              <div class="page-record">
                <span><strong>Reading time</strong> {{ currentDoc.readingMinutes }} minutes</span>
                <span><strong>Repository file</strong> {{ currentDoc.file }}</span>
                <a :href="`${githubUrl}/blob/main/${currentDoc.file}`" target="_blank" rel="noreferrer">
                  View source
                  <GithubLogo :size="14" weight="fill" aria-hidden="true" />
                </a>
              </div>
            </header>
            <div class="markdown-body" data-reveal v-html="currentDoc.html"></div>
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
        <span class="brand-copy"><strong>Nextcloud Native</strong><small>by Obiente</small></span>
      </a>
      <p>An independent AGPL-3.0-or-later project by Obiente.</p>
      <div class="footer-links">
        <a :href="githubUrl">GitHub</a>
        <a href="/roadmap/">Roadmap</a>
        <a href="/news/">Journal</a>
        <a href="/changelog/">Changelog</a>
        <a href="/visual-qa/">Visual QA</a>
        <a href="/security/">Security</a>
        <a :href="`${githubUrl}/blob/main/LICENSE`">License</a>
      </div>
    </footer>
  </div>
</template>
