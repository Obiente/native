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
import { guides } from "./generated/guides.js";
import { news } from "./generated/news.js";
import { changelog } from "./generated/changelog.js";
import { marketingCaptures } from "./generated/captures.js";
import { githubRepository } from "./generated/github-repository.js";
import {
  fetchGithubRepository,
  shouldRefreshGithubRepository,
} from "../scripts/github-repository-data.mjs";
import { hydrationTheme } from "./hydration-state.js";
import {
  guidePlatformHubForPath,
  guidePlatformHubs,
  guidesForPlatformHub,
} from "./guide-platforms.js";
import RoadmapDashboard from "./components/RoadmapDashboard.vue";
import ArticleRoadmap from "./components/ArticleRoadmap.vue";
import NativeHome from "./components/NativeHome.vue";
import PageOutline from "./components/PageOutline.vue";

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
  initialGuide: {
    type: Object,
    default: null,
  },
});

const githubUrl = "https://github.com/obiente/native";
const currentGithubRepository = ref(githubRepository);
const githubStarLabel = computed(() => `${new Intl.NumberFormat("en", {
  notation: "compact",
  maximumFractionDigits: 1,
}).format(currentGithubRepository.value.stargazersCount)} stars`);
const downloadPlatforms = [
  {
    id: "android",
    name: "Android",
    detail: "Android 8.0 or newer",
    format: "APK",
    href: "/d/android-latest",
    icon: AndroidLogo,
  },
  {
    id: "windows",
    name: "Windows",
    detail: "x86-64 - unsigned MSI",
    format: "MSI",
    href: "/d/windows-latest",
    icon: WindowsLogo,
  },
  {
    id: "linux-deb",
    name: "Linux",
    detail: "Debian and Ubuntu - x86-64",
    format: "DEB",
    href: "/d/linux-deb-latest",
    icon: LinuxLogo,
  },
  {
    id: "linux-rpm",
    name: "Linux",
    detail: "Fedora and RHEL - x86-64",
    format: "RPM",
    href: "/d/linux-rpm-latest",
    icon: LinuxLogo,
  },
  {
    id: "macos",
    name: "macOS",
    detail: "Intel - sign-in unavailable",
    format: "DMG",
    href: "/d/macos-latest",
    icon: AppleLogo,
  },
];
const captureByScenario = new Map(
  marketingCaptures.map((capture) => [capture.scenario, capture]),
);
const initialTheme = hydrationTheme;
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
let repositoryRefreshTimer;
const motionEnhanced = ref(false);

async function refreshGithubRepository() {
  try {
    currentGithubRepository.value = await fetchGithubRepository();
  } catch {
    // Keep the validated build-time snapshot when the live endpoint is unavailable.
  }
}

function refreshGithubRepositoryWhenVisible() {
  if (document.visibilityState === "visible") void refreshGithubRepository();
}

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
  if (shouldRefreshGithubRepository(window.location)) {
    void refreshGithubRepository();
    repositoryRefreshTimer = window.setInterval(refreshGithubRepository, 5 * 60 * 1000);
    document.addEventListener("visibilitychange", refreshGithubRepositoryWhenVisible);
  }

  try {
    const savedTheme = window.localStorage.getItem("nextcloud-native-theme");
    if (themeOptions.includes(savedTheme)) themePreference.value = savedTheme;
  } catch {
    themePreference.value = "system";
  }
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
  window.clearInterval(repositoryRefreshTimer);
  document.removeEventListener("visibilitychange", refreshGithubRepositoryWhenVisible);
  themeMediaQuery?.removeEventListener("change", themeMediaListener);
  revealObserver?.disconnect();
});

watch(themePreference, (preference) => {
  if (typeof window !== "undefined") {
    try {
      window.localStorage.setItem("nextcloud-native-theme", preference);
    } catch {
      // Theme selection remains active for this page when storage is unavailable.
    }
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
const currentGuide = computed(
  () => props.initialGuide ?? guides.find((guide) => guide.path === normalizedPath),
);
const relatedPosts = computed(() =>
  news.filter((post) => post.path !== currentPost.value?.path).slice(0, 2),
);
const relatedGuides = computed(() =>
  guides
    .filter((guide) =>
      guide.path !== currentGuide.value?.path &&
      guide.platforms.some((platform) => currentGuide.value?.platforms.includes(platform)),
    )
    .sort((left, right) => {
      const leftPlatform = left.platform === currentGuide.value?.platform ? 0 : 1;
      const rightPlatform = right.platform === currentGuide.value?.platform ? 0 : 1;
      const leftMatch = left.category === currentGuide.value?.category ? 0 : 1;
      const rightMatch = right.category === currentGuide.value?.category ? 0 : 1;
      return leftPlatform - rightPlatform || leftMatch - rightMatch || left.title.localeCompare(right.title);
    })
    .slice(0, 3),
);
const isNewsIndex = computed(() => normalizedPath === "/news/");
const isGuidesIndex = computed(() => normalizedPath === "/guides/");
const currentGuidePlatformHub = computed(() => guidePlatformHubForPath(normalizedPath));
const isGuidesLanding = computed(() => isGuidesIndex.value || currentGuidePlatformHub.value !== null);
const visibleGuideLibrary = computed(() =>
  currentGuidePlatformHub.value
    ? guidesForPlatformHub(guides, currentGuidePlatformHub.value)
    : guides,
);
const guideSearch = ref("");
const filteredGuideLibrary = computed(() => {
  const terms = guideSearch.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return visibleGuideLibrary.value.filter((guide) => {
    const text = [guide.title, guide.description, guide.category, ...guide.platforms].join(" ").toLowerCase();
    return terms.every((term) => text.includes(term));
  });
});
const isChangelog = computed(() => normalizedPath === "/changelog/");
const isVisualQa = computed(() => normalizedPath === "/visual-qa/");
const isHome = computed(() => normalizedPath === "/");
const mobileNavOpen = ref(false);
const siteHeader = ref(null);
const projectMenu = ref(null);
const mobileMenuTrigger = ref(null);
const headerRaised = ref(false);

function updateHeaderElevation() {
  headerRaised.value = window.scrollY > 12;
}

function closeHeaderMenus() {
  mobileNavOpen.value = false;
  if (projectMenu.value) projectMenu.value.open = false;
}

function dismissHeaderMenus(event) {
  if (!siteHeader.value?.contains(event.target)) closeHeaderMenus();
}

function onHeaderEscape() {
  if (!mobileNavOpen.value) return;
  mobileNavOpen.value = false;
  nextTick(() => mobileMenuTrigger.value?.focus());
}

onMounted(() => {
  updateHeaderElevation();
  window.addEventListener("scroll", updateHeaderElevation, { passive: true });
  document.addEventListener("pointerdown", dismissHeaderMenus);
  document.addEventListener("focusin", dismissHeaderMenus);
});

onBeforeUnmount(() => {
  window.removeEventListener("scroll", updateHeaderElevation);
  document.removeEventListener("pointerdown", dismissHeaderMenus);
  document.removeEventListener("focusin", dismissHeaderMenus);
});
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
const searchLoading = ref(false);
const searchError = ref(false);
const searchInput = ref(null);
const searchTrigger = ref(null);

async function openSearch() {
  closeHeaderMenus();
  searchOpen.value = true;
  await nextTick();
  searchInput.value?.focus();
  if (!searchLoaded.value) await loadSearchIndex();
}

async function loadSearchIndex() {
  if (searchLoading.value || typeof window === "undefined") return;
  searchLoading.value = true;
  searchError.value = false;

  try {
    const response = await fetch("/search-index.json");
    if (!response.ok) throw new Error("Search index unavailable");
    searchDocuments.value = await response.json();
    searchLoaded.value = true;
  } catch {
    // Keep bundled documentation searchable and allow a retry for the full index.
    searchError.value = true;
  } finally {
    searchLoading.value = false;
  }
}

function closeSearch() {
  searchOpen.value = false;
  nextTick(() => searchTrigger.value?.focus());
}

function trapSearchFocus(event) {
  const focusable = [...event.currentTarget.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )].filter((element) => !element.hidden && element.getAttribute("aria-hidden") !== "true");
  if (focusable.length === 0) {
    event.preventDefault();
    searchInput.value?.focus();
    return;
  }

  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const active = document.activeElement;
  if (event.shiftKey && (active === first || !event.currentTarget.contains(active))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && active === last) {
    event.preventDefault();
    first.focus();
  }
}

function guideCapture(step) {
  return resolvedTheme.value === "light" ? step.websiteImageLight : step.websiteImageDark;
}

function guideHeroCapture(guide) {
  return resolvedTheme.value === "light" ? guide.websiteImageLight : guide.websiteImageDark;
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
    title: "Your installed apps are discoverable",
    body: "Files, Photos, Talk, Calendar, Mail, Notes, Deck, and other server apps share one account, with native support shown at its verified level.",
  },
  {
    icon: Desktop,
    title: "It uses the device you are holding",
    body: "Android gets touch controls, system Back, and share sheets. Linux and Windows use resizable panes, pointer actions, and desktop file integration where implemented.",
  },
  {
    icon: ShieldCheck,
    title: "It stops before it guesses",
    body: "The client avoids destructive writes, shows known conflicts, and withholds an action when the server contract is unclear.",
  },
];

const appFamilies = [
  {
    icon: File,
    title: "Files and documents",
    apps: "Files, Notes, search, sharing foundations, Android offline storage, folder pairs, and desktop virtual files",
    body: "Browse and preview ordinary files, edit guarded text content, and use platform file integration. Sync, cache, and conflict state remain explicit while the alpha is still being hardened.",
    captureDark: "homepage-files-desktop-dark",
    captureLight: "homepage-files-desktop-light",
    captureFallback: "obsidian-vault-sync",
  },
  {
    icon: Camera,
    title: "Photos and memories",
    apps: "Photos, Memories, Recognize people, albums, RAW previews, Live Photos, and Android media backup state",
    body: "Browse timelines, folders, albums, and recognized people, inspect native previews, and follow exact-version Android backup state. Editing remains a foundation, not a complete shipped workflow.",
    captureDark: "homepage-photos-desktop-dark",
    captureLight: "homepage-photos-desktop-light",
    captureFallback: "photo-folder-browser-desktop",
  },
  {
    icon: ChatCircleDots,
    title: "Conversations and people",
    apps: "Talk room history and rich message cards, Mail and Contacts workspaces, shared files, status, and notification foundations",
    body: "Talk history is readable through typed message cards, while Mail, Contacts, and communication actions remain at different levels of completeness. Native calling is not shipped yet.",
    captureDark: "homepage-conversations-desktop-dark",
    captureLight: "homepage-conversations-desktop-light",
    captureFallback: "file-share-group-desktop",
  },
  {
    icon: ListChecks,
    title: "Planning and everyday work",
    apps: "Calendar, Tasks, Deck, Tables, Cookbook, Cospend, Music, dashboards, and administration inventory",
    body: "Calendar has direct CalDAV workflows. Other planning, media, and administration surfaces use native semantics at different levels of completeness and with capability-gated actions.",
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
    captureFallback: "tables-insights-desktop",
  },
];

const platforms = [
  {
    icon: Desktop,
    name: "Android mobile and tablet",
    body: "Android is the active mobile target, with touch-first navigation, system Files integration, permissions, and durable background work. iPhone and iPad builds are planned but not available.",
  },
  {
    icon: Code,
    name: "Linux and Windows desktop",
    body: "Linux and Windows have authenticated alpha packages and OS-specific file integration. macOS has an early packaging artifact but no supported Keychain-backed login yet.",
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
      "No. nati.ve consumes server APIs and renders native Compose interfaces. Web content is reserved for formats that genuinely require a document renderer, not app navigation.",
  },
  {
    question: "Can I keep normal folders and an Obsidian vault in sync?",
    answer:
      "Folder pairs connect a normal device folder with a Nextcloud folder and expose direction, deletion, conflict, and scheduling policy. Keep independent backups while this workflow remains alpha.",
  },
  {
    question: "Can it back up photos and safely make space on my device?",
    answer:
      "Android backup distinguishes pending, uploading, backed-up, changed, failed, and cloud-only files. A complete camera-roll cleanup action is not exposed yet, so keep originals and use an independent backup.",
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
      "No. nati.ve is an independent Obiente project, licensed under AGPL-3.0-or-later. It is not affiliated with, sponsored by or endorsed by Nextcloud GmbH.",
  },
];
const downloadDialog = ref(null);
function openDownloads() {
  closeHeaderMenus();
  downloadDialog.value?.showModal();
}
function closeDownloads() {
  downloadDialog.value?.close();
}
function closeProjectMenu(event) {
  event.currentTarget.open = false;
  event.currentTarget.querySelector("summary")?.focus();
}
function onDownloadBackdropClick(event) {
  if (event.target !== downloadDialog.value) return;
  const bounds = downloadDialog.value.getBoundingClientRect();
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeDownloads();
}
onMounted(() => {
  if (window.location.hash === "#download-chooser") openDownloads();
});
</script>

<template>
  <div
    class="site-shell"
    :class="{ 'motion-enhanced': motionEnhanced, 'is-home': isHome }"
    :data-theme="resolvedTheme"
  >
    <a class="skip-link" href="#main">Skip to content</a>

    <div class="site-header-frame" :class="{ 'is-raised': headerRaised }">
    <header ref="siteHeader" class="site-header" @keydown.esc="onHeaderEscape">
      <a class="brand" href="/" aria-label="nati.ve home">
        <span class="native-brand-mark"><img :src="resolvedTheme === 'light' ? '/brand/native-mark.svg' : '/brand/native-mark-dark.svg'" alt="" width="38" height="38" /></span>
        <span class="brand-copy"><strong>nati.ve</strong></span>
      </a>

      <nav class="desktop-nav" aria-label="Primary navigation">
        <a href="/#product">Product</a>
        <a href="/#apps">Apps</a>
        <a href="/guides/" :aria-current="isGuidesLanding || currentGuide ? 'page' : undefined">Guides</a>
        <details ref="projectMenu" class="project-menu" :class="{ 'is-current': currentDoc || currentPost || isNewsIndex || isChangelog }" @keydown.esc.stop="closeProjectMenu">
          <summary>Project <CaretDown :size="13" aria-hidden="true" /></summary>
          <nav aria-label="Project resources" @click="closeHeaderMenus">
            <a href="/#experience">Experience</a>
            <a href="/#native">How it works</a>
            <a href="/#docs">Documentation</a>
            <a href="/roadmap/">Roadmap</a>
            <a href="/news/">Journal</a>
            <a href="/architecture/">Architecture</a>
            <a href="/native-schema/">Native schema</a>
            <a href="/dynamic-apps/">Dynamic apps</a>
            <a href="/compatibility/">Compatibility</a>
            <a href="/contributing/">Contributing</a>
            <a href="/changelog/">Release notes</a>
            <a :href="githubUrl" target="_blank" rel="noreferrer">Star on GitHub · {{ githubStarLabel }}</a>
          </nav>
        </details>
      </nav>

      <div class="header-actions">
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
          ref="searchTrigger"
          class="header-search"
          type="button"
          aria-label="Search guides and project documentation"
          @click="openSearch"
        >
          <MagnifyingGlass :size="20" weight="bold" aria-hidden="true" />
          <span>Search</span>
        </button>
        <button
          ref="mobileMenuTrigger"
          class="mobile-menu-button"
          type="button"
          :aria-label="mobileNavOpen ? 'Close primary navigation' : 'Open primary navigation'"
          aria-controls="mobile-site-navigation"
          :aria-expanded="mobileNavOpen"
          @click="mobileNavOpen = !mobileNavOpen"
        >
          <X v-if="mobileNavOpen" :size="21" weight="bold" aria-hidden="true" />
          <Menu v-else :size="21" weight="bold" aria-hidden="true" />
        </button>
        <button type="button" class="native-button header-download" @click="openDownloads">Download</button>
      </div>

      <nav
        v-if="mobileNavOpen"
        id="mobile-site-navigation"
        class="mobile-nav"
        aria-label="Mobile primary navigation"
      >
        <div class="mobile-nav-primary">
          <a href="/" @click="mobileNavOpen = false">Home</a>
          <a href="/#experience" @click="mobileNavOpen = false">Experience</a>
          <a href="/#apps" @click="mobileNavOpen = false">Apps</a>
          <a href="/#native" @click="mobileNavOpen = false">How it works</a>
          <a href="/guides/" @click="mobileNavOpen = false">Guides</a>
          <a href="/roadmap/" @click="mobileNavOpen = false">Roadmap</a>
          <a href="/news/" @click="mobileNavOpen = false">Journal</a>
          <a href="/#docs" @click="mobileNavOpen = false">Docs</a>
          <button type="button" @click="openDownloads">Downloads <ArrowRight :size="16" aria-hidden="true" /></button>
        </div>
        <div class="mobile-nav-tools">
          <button
            class="mobile-theme-button"
            type="button"
            :aria-label="`Theme: ${themeLabel}. Activate to change theme.`"
            @click="cycleTheme"
          >
            <component :is="themeIcon" :size="19" weight="bold" aria-hidden="true" />
            <span><small>Appearance</small><strong>{{ themeLabel }}</strong></span>
          </button>
          <a :href="githubUrl" target="_blank" rel="noreferrer">
            <GithubLogo :size="19" weight="fill" aria-hidden="true" />
            <span><small>Project</small><strong>Open GitHub</strong></span>
          </a>
        </div>
      </nav>
    </header>
    </div>

    <div
      v-if="searchOpen"
      class="search-overlay"
      @click.self="closeSearch"
      @keydown.esc.stop.prevent="closeSearch"
      @keydown.tab="trapSearchFocus"
    >
      <section class="search-panel" role="dialog" aria-modal="true" aria-labelledby="search-title">
        <div class="search-panel-header">
          <div>
            <p class="eyebrow">Project knowledge</p>
            <h2 id="search-title">Search guides and documentation</h2>
          </div>
          <button class="icon-button" type="button" aria-label="Close search" @click="closeSearch">
            <X :size="21" weight="bold" aria-hidden="true" />
          </button>
        </div>
        <label class="search-input">
          <span class="sr-only">Search guides and project documentation</span>
          <MagnifyingGlass :size="21" weight="bold" aria-hidden="true" />
          <input
            ref="searchInput"
            v-model="searchQuery"
            type="search"
            name="documentation-search"
            placeholder="Search roadmap, apps, schema, security..."
            autocomplete="off"
          />
        </label>
        <div class="search-status" role="status">
          <p v-if="searchLoading">Loading the guide and documentation library...</p>
          <p v-else-if="searchError">The full search library could not load. Bundled documentation is still available. <button type="button" @click="loadSearchIndex">Try again</button></p>
          <p v-else>{{ searchQuery.trim() ? `${searchResults.length} results` : 'Start with a guide or explore the project' }}</p>
        </div>
        <div class="search-results" :aria-busy="searchLoading">
          <a v-for="result in searchResults" :key="result.path" class="search-result" :href="result.path">
            <span>
              <span class="search-result-kind">{{ result.contentType ?? 'Documentation' }}</span>
              <strong>{{ result.shortTitle }}</strong>
              <small>{{ result.description }}</small>
            </span>
            <ArrowRight :size="18" weight="bold" aria-hidden="true" />
          </a>
          <p v-if="searchResults.length === 0 && !searchLoading" class="empty-search">
            No matches found. Try an app name, platform, or workflow.
          </p>
        </div>
        <div class="search-panel-footer"><span>Search guides, articles, and project documentation</span><span><kbd>Esc</kbd> to close</span></div>
      </section>
    </div>

    <dialog id="download-chooser" ref="downloadDialog" class="native-download-dialog" aria-labelledby="download-title" @click="onDownloadBackdropClick" @keydown.esc.prevent="closeDownloads">
      <div class="native-download-heading">
        <div><p class="native-eyebrow">Try the alpha</p><h2 id="download-title">Choose your platform.</h2></div>
        <button type="button" class="native-dialog-close" aria-label="Close downloads" autofocus @click="closeDownloads"><X :size="22" aria-hidden="true" /></button>
      </div>
      <p class="native-download-intro">Connect your existing Nextcloud account. Android, Linux and Windows builds are ready to try.</p>
      <div class="native-download-list">
        <a v-for="platform in downloadPlatforms" :id="`download-choice-${platform.id}`" :key="platform.id" :href="platform.href">
          <component :is="platform.icon" :size="26" weight="fill" aria-hidden="true" />
          <span><strong>{{ platform.name }}</strong><small>{{ platform.detail }}</small></span>
          <b>{{ platform.format }}</b><ArrowRight :size="18" aria-hidden="true" />
        </a>
      </div>
      <p class="native-download-note">Alpha software: keep another copy of important data. macOS is a packaging preview without sign-in; iOS is planned.</p>
      <a class="native-text-link" href="https://github.com/obiente/native/releases" target="_blank" rel="noreferrer">Release notes and checksums <ArrowRight :size="17" aria-hidden="true" /></a>
    </dialog>

    <main id="main">
      <NativeHome v-if="isHome" :theme="resolvedTheme" :app-families="appFamilies" @download="openDownloads">
        <div class="native-home-content">
          <section id="downloads" class="download-section section-width" data-reveal>
            <div class="section-heading compact">
              <p class="eyebrow">Get the current build</p>
              <h2>Download for your platform.</h2>
            </div>
            <div class="download-grid">
              <a
                v-for="platform in downloadPlatforms"
                :id="`download-${platform.id}`"
                :key="platform.id"
                class="download-card"
                :href="platform.href"
              >
                <component :is="platform.icon" :size="28" weight="fill" aria-hidden="true" />
                <span>
                  <strong>{{ platform.name }}</strong>
                  <small>{{ platform.detail }}</small>
                </span>
                <b>{{ platform.format }}</b>
                <ArrowRight :size="18" weight="bold" aria-hidden="true" />
              </a>
            </div>
            <p class="download-caveat">
              Alpha software: keep another copy of important data. Check the
              <a href="https://github.com/obiente/native/releases" target="_blank" rel="noreferrer">release notes and checksums</a>
              before installing.
            </p>
          </section>

          <section id="experience" class="native-promise section-width" data-reveal>
            <div class="section-heading compact">
              <p class="eyebrow">Everyday work</p>
              <h2>Open a file. Review a photo. Read a message.</h2>
              <p>
                nati.ve uses verified server APIs and the platform
                integrations implemented for Android, Linux, and Windows. Capability
                checks keep unavailable actions out of otherwise useful workspaces.
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

          <section id="native" class="native-method section-width" data-reveal>
            <div class="native-method-intro">
              <p class="eyebrow">How it works</p>
              <h2>Native is a behavior, not a coat of paint.</h2>
              <p>
                nati.ve reads verified capabilities and versioned contracts,
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
                Supported builds apply the same core safety rules while each
                platform keeps its own layout, controls, lifecycle, and available
                system integration.
              </p>
            </div>
            <div class="platform-story-content">
              <figure class="platform-story-visual">
                <img
                  class="platform-story-desktop"
                  :src="platformDesktopCapture.websitePath"
                  alt="nati.ve Files on desktop with folders, photos, documents, and offline availability"
                  :width="platformDesktopCapture.width"
                  :height="platformDesktopCapture.height"
                  loading="lazy"
                />
                <img
                  class="platform-story-mobile"
                  :src="mobileHomeCapture.websitePath"
                  alt="nati.ve home on mobile"
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
            <a href="/guides/"><BookOpen :size="22" weight="duotone" aria-hidden="true" /><span><strong>Guides</strong><small>Learn complete workflows step by step</small></span><ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
            <a href="/roadmap/"><GitBranch :size="22" weight="duotone" aria-hidden="true" /><span><strong>Roadmap</strong><small>Priorities, milestones, and delivery</small></span><ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
            <a href="/news/"><BookOpen :size="22" weight="duotone" aria-hidden="true" /><span><strong>Journal</strong><small>Product decisions and deeper stories</small></span><ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
          </section>

          <section class="guide-home-section section-width" data-reveal>
            <div class="guide-section-heading">
              <div class="section-heading compact">
                <p class="eyebrow">Learn by doing</p>
                <h2>Follow the whole task, not a list of controls.</h2>
                <p>Every guide uses current synthetic captures from the real app and explains the safe result you should expect.</p>
              </div>
              <a class="text-link" href="/guides/">All guides <ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
            </div>
            <div class="guide-home-grid">
              <a v-for="guide in guides" :key="guide.path" class="guide-home-card" :href="guide.path">
                <div class="guide-home-media">
                  <img
                    :src="guideHeroCapture(guide)"
                    :alt="guide.imageAlt"
                    :width="guide.imageWidth"
                    :height="guide.imageHeight"
                    loading="lazy"
                  />
                </div>
                <div class="guide-home-copy">
                  <span>{{ guide.category }}</span>
                  <h3>{{ guide.title }}</h3>
                  <p>{{ guide.description }}</p>
                  <small>{{ guide.durationMinutes }} min · {{ guide.steps.length }} steps</small>
                </div>
              </a>
            </div>
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
              <a class="text-link" href="/news/">All project stories <ArrowRight :size="18" weight="bold" aria-hidden="true" /></a>
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
            <nav class="contribute-links" aria-label="Contribute to nati.ve">
              <a href="/contributing/">Contribution guide <ArrowRight :size="17" weight="bold" aria-hidden="true" /></a>
              <a :href="githubUrl" target="_blank" rel="noreferrer">Browse the source <GithubLogo :size="17" weight="fill" aria-hidden="true" /></a>
              <a :href="`${githubUrl}/issues`" target="_blank" rel="noreferrer">Open issues <ArrowRight :size="17" weight="bold" aria-hidden="true" /></a>
            </nav>
          </section>
        </div>
      </NativeHome>

      <section v-else-if="isGuidesLanding" class="guides-index section-width">
        <header class="guides-index-heading">
          <div>
            <p class="eyebrow">
              {{ currentGuidePlatformHub ? `${currentGuidePlatformHub.device} guides` : "nati.ve guides" }}
            </p>
            <h1>{{ currentGuidePlatformHub?.title ?? "Choose your platform, then finish a workflow." }}</h1>
            <p>
              {{ currentGuidePlatformHub?.summary ?? "Android, Linux, and Windows do not share identical permissions, background behavior, or file integration. Start with your platform for instructions and screenshots that match the product you can actually use." }}
            </p>
          </div>
          <div class="guides-index-summary" aria-label="Guide library summary">
            <span><strong>{{ visibleGuideLibrary.length }}</strong> maintained guides</span>
            <span><strong>{{ visibleGuideLibrary.reduce((total, guide) => total + guide.steps.length, 0) }}</strong> illustrated steps</span>
            <span><ShieldCheck :size="16" weight="fill" aria-hidden="true" /> Synthetic data in real Compose UI</span>
          </div>
        </header>

        <nav class="guide-platform-nav" aria-label="Filter guides by platform">
          <a href="/guides/" :aria-current="isGuidesIndex ? 'page' : undefined">All guides</a>
          <a
            v-for="hub in guidePlatformHubs"
            :key="hub.slug"
            :href="`/guides/${hub.slug}/`"
            :aria-current="currentGuidePlatformHub?.slug === hub.slug ? 'page' : undefined"
          >
            <span>{{ hub.device }}</span>
            <strong>{{ hub.label }}</strong>
          </a>
        </nav>

        <aside v-if="isGuidesIndex" class="guide-availability-note">
          <strong>Platform availability</strong>
          <p>
            Android, Linux, and Windows have authenticated alpha builds. macOS packaging is early
            and does not yet have supported Keychain login. iPhone and iPad builds are not available.
          </p>
        </aside>

        <div class="guide-library-toolbar">
          <label class="guide-library-search">
            <MagnifyingGlass :size="20" aria-hidden="true" />
            <span class="sr-only">Search this guide library</span>
            <input v-model="guideSearch" type="search" placeholder="Find a workflow, app, or topic" />
          </label>
          <p role="status">{{ filteredGuideLibrary.length }} {{ filteredGuideLibrary.length === 1 ? 'guide' : 'guides' }}{{ guideSearch.trim() ? ' found' : ' to explore' }}</p>
        </div>

        <div v-if="filteredGuideLibrary.length" class="guides-featured-grid">
          <a
            v-for="(guide, guideIndex) in filteredGuideLibrary"
            :key="guide.path"
            class="guide-index-card"
            :href="guide.path"
          >
            <div class="guide-index-media">
              <img
                :src="guideHeroCapture(guide)"
                :alt="guide.imageAlt"
                :width="guide.imageWidth"
                :height="guide.imageHeight"
                :loading="guideIndex < 2 ? 'eager' : 'lazy'"
                :fetchpriority="guideIndex === 0 ? 'high' : 'auto'"
              />
              <span>{{ guide.platform }} · {{ guide.category }}</span>
            </div>
            <div class="guide-index-copy">
              <div class="guide-index-meta">
                <span>{{ guide.durationMinutes }} min</span>
                <span>{{ guide.steps.length }} steps</span>
                <span>{{ guide.difficulty }}</span>
              </div>
              <h2>{{ guide.title }}</h2>
              <p>{{ guide.description }}</p>
              <ul aria-label="Supported platforms">
                <li v-for="platform in guide.platforms" :key="platform">{{ platform }}</li>
              </ul>
              <strong>Open guide <ArrowRight :size="16" weight="bold" aria-hidden="true" /></strong>
            </div>
          </a>
        </div>
        <div v-else class="guide-library-empty">
          <h2>No matching guides</h2>
          <p>Try another topic or browse all the guides for this platform.</p>
          <button type="button" class="native-button" @click="guideSearch = ''">Clear search</button>
        </div>
      </section>

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
        v-else-if="currentGuide"
        class="guide-page section-width"
      >
        <a class="doc-back guide-back" :href="`/guides/${currentGuide.platformSlug}/`">
          All {{ currentGuide.platform }} guides
        </a>
        <header class="guide-page-heading">
          <div>
            <p class="eyebrow">{{ currentGuide.platform }} · {{ currentGuide.device }} · {{ currentGuide.category }}</p>
            <h1>{{ currentGuide.title }}</h1>
            <p>{{ currentGuide.description }}</p>
          </div>
          <dl class="guide-facts">
            <div><dt>Time</dt><dd>{{ currentGuide.durationMinutes }} minutes</dd></div>
            <div><dt>Steps</dt><dd>{{ currentGuide.steps.length }}</dd></div>
            <div><dt>Level</dt><dd>{{ currentGuide.difficulty }}</dd></div>
            <div><dt>Updated</dt><dd>{{ currentGuide.lastUpdated }}</dd></div>
          </dl>
          <ul class="guide-platforms" aria-label="Supported platforms">
            <li v-for="platform in currentGuide.platforms" :key="platform">{{ platform }}</li>
          </ul>
        </header>

        <div class="guide-layout">
          <aside class="guide-toc" aria-label="Guide steps and prerequisites">
            <div>
              <p>In this guide</p>
              <nav>
                <a
                  v-for="step in currentGuide.steps"
                  :key="step.number"
                  :href="`#step-${step.number}`"
                >
                  <span>{{ step.number }}</span>
                  {{ step.title }}
                </a>
              </nav>
            </div>
            <div class="guide-prerequisites">
              <p>Before you start</p>
              <ul>
                <li v-for="item in currentGuide.prerequisites" :key="item">{{ item }}</li>
              </ul>
            </div>
          </aside>

          <article class="guide-article">
            <div class="guide-introduction" v-html="currentGuide.introductionHtml"></div>
            <ol class="guide-step-list">
              <li
                v-for="step in currentGuide.steps"
                :id="`step-${step.number}`"
                :key="step.number"
                class="guide-step"
              >
                <header>
                  <span>Step {{ step.number }}</span>
                  <h2>{{ step.title }}</h2>
                </header>
                <figure>
                  <a
                    :href="guideCapture(step)"
                    target="_blank"
                    rel="noreferrer"
                    :aria-label="`Open the step ${step.number} capture at full size`"
                  >
                    <img
                      :src="guideCapture(step)"
                      :alt="step.imageAlt"
                      :width="step.imageWidth"
                      :height="step.imageHeight"
                      loading="lazy"
                    />
                  </a>
                  <figcaption>
                    <span>{{ step.imageCaption }}</span>
                    <span class="capture-provenance"><ShieldCheck :size="15" weight="fill" aria-hidden="true" /> Synthetic data in real Compose UI</span>
                  </figcaption>
                </figure>
                <div class="markdown-body guide-step-body" v-html="step.html"></div>
                <a v-if="step.number < currentGuide.steps.length" class="guide-next-step" :href="`#step-${step.number + 1}`">
                  Next: {{ currentGuide.steps[step.number].title }}
                  <ArrowRight :size="16" weight="bold" aria-hidden="true" />
                </a>
              </li>
            </ol>

            <aside class="guide-complete" aria-labelledby="guide-complete-title">
              <CloudCheck :size="28" weight="duotone" aria-hidden="true" />
              <div>
                <p class="eyebrow">Guide complete</p>
                <h2 id="guide-complete-title">Keep learning in the same workspace.</h2>
                <p>Your account state stays in the app. Choose another guide without changing files, settings, or sync pairs.</p>
              </div>
            </aside>

            <section class="related-guides" aria-labelledby="related-guides-title">
              <div>
                <p class="eyebrow">Continue learning</p>
                <h2 id="related-guides-title">Related guides</h2>
              </div>
              <div>
                <a v-for="guide in relatedGuides" :key="guide.path" :href="guide.path">
                  <span><small>{{ guide.category }}</small><strong>{{ guide.title }}</strong></span>
                  <ArrowRight :size="16" weight="bold" aria-hidden="true" />
                </a>
              </div>
            </section>
          </article>
        </div>
      </section>

      <section
        v-else-if="currentPost"
        class="article-page section-width"
      >
        <article class="news-article">
          <a class="doc-back" href="/news/">Project news</a>
          <header class="doc-heading" data-reveal>
            <p class="eyebrow">Dated product story</p>
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
          <PageOutline :headings="currentPost.headings" label="In this article" />
          <div class="markdown-body" data-reveal v-html="currentPost.html"></div>
          <ArticleRoadmap :slug="currentPost.path.split('/').filter(Boolean).at(-1)" />
          <aside class="article-related" aria-labelledby="article-related-title">
            <div>
              <p class="eyebrow">Continue exploring</p>
              <h2 id="article-related-title">Related product stories</h2>
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
          <p class="eyebrow">nati.ve news</p>
          <h1>What your Nextcloud can do in one native client.</h1>
          <p>Dated product and design notes explain decisions made while building the client. Use current guides, compatibility notes, and release notes for support decisions.</p>
          <div class="page-record">
            <span><strong>Stories</strong> {{ news.length }} dated articles</span>
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
              <a :href="`${githubUrl}/blob/main/CHANGELOG.md`" target="_blank" rel="noreferrer">
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
              :aria-current="doc.path === currentDoc.path ? 'page' : undefined"
            >
              {{ doc.shortTitle }}
            </a>
          </nav>
          <PageOutline :headings="currentDoc.headings" :expanded="true" label="On this page" />
        </aside>

        <article class="doc-article">
          <details class="doc-mobile-navigation">
            <summary>Documentation <CaretDown :size="16" aria-hidden="true" /></summary>
            <nav aria-label="Browse documentation">
              <a v-for="doc in docs" :key="doc.path" :href="doc.path" :aria-current="doc.path === currentDoc.path ? 'page' : undefined">{{ doc.shortTitle }}</a>
            </nav>
          </details>
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
            <a class="doc-back" href="/#docs">nati.ve documentation</a>
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
            <PageOutline class="doc-inline-outline" :headings="currentDoc.headings" label="On this page" />
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
      <div class="footer-identity">
        <a class="brand footer-brand" href="/">
          <span class="native-brand-mark"><img :src="resolvedTheme === 'light' ? '/brand/native-mark.svg' : '/brand/native-mark-dark.svg'" alt="" width="38" height="38" /></span>
          <span class="brand-copy"><strong>nati.ve</strong></span>
        </a>
        <p>Your Nextcloud, in a native workspace.</p>
      </div>
      <nav class="footer-directory" aria-label="Footer navigation">
        <div>
          <h2>Explore</h2>
          <a href="/#apps">Native apps</a>
          <a href="/#downloads">Downloads</a>
          <a href="/guides/">Guides</a>
          <a href="/compatibility/">Compatibility</a>
        </div>
        <div>
          <h2>Project</h2>
          <a href="/architecture/">Documentation</a>
          <a href="/roadmap/">Roadmap</a>
          <a href="/news/">Journal</a>
          <a href="/changelog/">Changelog</a>
        </div>
        <div>
          <h2>Get involved</h2>
          <a :href="githubUrl">GitHub</a>
          <a href="/contributing/">Contributing</a>
          <a href="/security/">Security</a>
          <a :href="`${githubUrl}/blob/main/LICENSE`">License</a>
        </div>
      </nav>
      <p class="footer-legal">An independent AGPL-3.0-or-later project by Obiente.</p>
    </footer>
  </div>
</template>
