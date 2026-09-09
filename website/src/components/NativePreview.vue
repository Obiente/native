<script setup>
import { computed, ref } from "vue";
import {
  PhAppWindow,
  PhCalendarBlank,
  PhCamera,
  PhChartBar,
  PhChatCircleDots,
  PhCheckCircle,
  PhEnvelope,
  PhFolder,
  PhForkKnife,
  PhMusicNotes,
  PhSquaresFour,
  PhTable,
} from "@phosphor-icons/vue";
import PreviewAppSurface from "./PreviewAppSurface.vue";

const apps = [
  {
    id: "files",
    label: "Files",
    description: "Browsing, previews, sharing, and offline files",
    icon: PhFolder,
  },
  {
    id: "photos",
    label: "Photos",
    description: "Memories, albums, people, RAW, and Live Photos",
    icon: PhCamera,
  },
  {
    id: "talk",
    label: "Talk",
    description: "Conversations, attachments, and calls",
    icon: PhChatCircleDots,
  },
  {
    id: "mail",
    label: "Mail",
    description: "Mailboxes, message lists, and readable mail",
    icon: PhEnvelope,
  },
  {
    id: "tables",
    label: "Tables",
    description: "Typed columns, rows, filters, and record details",
    icon: PhTable,
  },
  {
    id: "deck",
    label: "Deck",
    description: "Boards, stacks, cards, labels, and assignments",
    icon: PhSquaresFour,
  },
  {
    id: "cookbook",
    label: "Cookbook",
    description: "Recipe discovery, URL import, and serving sizes",
    icon: PhForkKnife,
  },
  {
    id: "cospend",
    label: "Cospend",
    description: "Balances, expenses, participants, and settlement",
    icon: PhChartBar,
  },
  {
    id: "music",
    label: "Music",
    description: "Albums, artists, queue, and media controls",
    icon: PhMusicNotes,
  },
  {
    id: "calendar",
    label: "Calendar",
    description: "CalDAV calendars, events, guests, and reminders",
    icon: PhCalendarBlank,
  },
  {
    id: "admin",
    label: "Administration",
    description: "Users, apps, security, jobs, and server settings",
    icon: PhAppWindow,
  },
];

const activeApp = ref("files");
const appSwitcher = ref(null);
const activeAppMeta = computed(
  () => apps.find((app) => app.id === activeApp.value) ?? apps[0],
);

function focusAppSwitcher() {
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  appSwitcher.value?.scrollIntoView({
    behavior: reduceMotion ? "auto" : "smooth",
    block: "nearest",
  });
  appSwitcher.value?.querySelector('[aria-pressed="true"]')?.focus();
}
</script>

<template>
  <section id="product-tour" class="native-preview" aria-label="Interactive nati.ve product tour">
    <header class="preview-titlebar">
      <div>
        <strong>nati.ve</strong>
        <span>
          <PhCheckCircle :size="14" weight="duotone" aria-hidden="true" />
          Connected to Nextcloud
        </span>
      </div>
      <span class="sample-label">Interactive sample workspace</span>
    </header>

    <div class="preview-workspace">
      <aside ref="appSwitcher" class="preview-apps" aria-label="Choose an app preview">
        <div class="preview-apps-heading">
          <span>Apps</span>
          <small>Native views from one client</small>
        </div>
        <button
          v-for="app in apps"
          :key="app.id"
          type="button"
          :class="{ active: activeApp === app.id }"
          :aria-pressed="activeApp === app.id"
          @click="activeApp = app.id"
        >
          <component :is="app.icon" :size="17" weight="duotone" aria-hidden="true" />
          <span>{{ app.label }}</span>
        </button>
      </aside>

      <div class="preview-product">
        <PreviewAppSurface :app="activeApp" @open-switcher="focusAppSwitcher" />
      </div>
    </div>

    <footer class="preview-caption">
      <div>
        <component :is="activeAppMeta.icon" :size="18" weight="duotone" aria-hidden="true" />
        <span>
          <strong>{{ activeAppMeta.label }}</strong>
          <small>{{ activeAppMeta.description }}</small>
        </span>
      </div>
      <p>Safe sample content, modeled on the app's real resources and workflows.</p>
    </footer>
  </section>
</template>

<style scoped>
.native-preview {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #33363e;
  border-radius: 14px;
  background: #0d1015;
  box-shadow: 0 24px 70px rgb(0 0 0 / 28%);
}

.preview-titlebar {
  min-height: 61px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 10px 16px;
  border-bottom: 1px solid #2a2d34;
  background: #11141a;
}

.preview-titlebar > div {
  display: grid;
  gap: 4px;
}

.preview-titlebar strong {
  font-size: 12px;
  letter-spacing: -0.01em;
}

.preview-titlebar div span {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #9d9ca5;
  font-size: 8px;
}

.preview-titlebar svg {
  color: #65d6bd;
}

.sample-label {
  color: #7f7e87;
  font-size: 8px;
}

.preview-workspace {
  min-height: 620px;
  display: grid;
  grid-template-columns: 122px minmax(0, 1fr);
}

.preview-apps {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 13px 8px;
  border-right: 1px solid #2a2d34;
  background: #101319;
}

.preview-apps-heading {
  display: grid;
  gap: 2px;
  padding: 2px 8px 10px;
}

.preview-apps-heading span {
  color: #d9d7dc;
  font-size: 9px;
  font-weight: 700;
}

.preview-apps-heading small {
  color: #777680;
  font-size: 6px;
  line-height: 1.35;
}

.preview-apps button {
  min-width: 0;
  min-height: 31px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #aaa8b1;
  font: inherit;
  font-size: 8px;
  text-align: left;
  cursor: pointer;
}

.preview-apps button:hover,
.preview-apps button:focus-visible {
  background: #191c22;
  color: #f6f3f8;
}

.preview-apps button.active {
  background: #30283d;
  color: #ddcbff;
}

.preview-apps button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-product {
  min-width: 0;
  overflow: hidden;
}

.preview-caption {
  min-height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
  padding: 9px 16px;
  border-top: 1px solid #2a2d34;
  background: #11141a;
}

.preview-caption > div {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #cbb3fd;
}

.preview-caption div span {
  min-width: 0;
  display: grid;
  gap: 1px;
}

.preview-caption strong {
  color: #ece9ef;
  font-size: 8px;
}

.preview-caption small,
.preview-caption p {
  color: #888690;
  font-size: 7px;
  line-height: 1.45;
}

.preview-caption p {
  max-width: 260px;
  margin: 0;
  text-align: right;
}

@media (max-width: 760px) {
  .native-preview {
    border-radius: 11px;
  }

  .preview-titlebar {
    min-height: 56px;
  }

  .sample-label {
    display: none;
  }

  .preview-workspace {
    min-height: 720px;
    display: flex;
    flex-direction: column;
  }

  .preview-apps {
    display: flex;
    flex-direction: row;
    flex: 0 0 auto;
    overflow-x: auto;
    padding: 8px;
    border-right: 0;
    border-bottom: 1px solid #2a2d34;
    scrollbar-width: none;
  }

  .preview-apps::-webkit-scrollbar {
    display: none;
  }

  .preview-apps-heading {
    display: none;
  }

  .preview-apps button {
    min-width: max-content;
    min-height: 36px;
    padding-inline: 10px;
  }

  .preview-product {
    flex: 1;
  }

  .preview-caption {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
    padding-block: 12px;
  }

  .preview-caption p {
    text-align: left;
  }
}
</style>
