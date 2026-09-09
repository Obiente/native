<script setup>
import {
  PhArrowSquareOut,
  PhCaretDown,
  PhCheckCircle,
  PhCircle,
  PhClock,
} from "@phosphor-icons/vue";
import { computed } from "vue";
import { roadmap } from "../generated/roadmap.js";

const props = defineProps({
  slug: {
    type: String,
    required: true,
  },
});

const scopes = {
  "adaptive-native-apps": {
    title: "Adaptive native apps",
    epic: "EPIC-DYN",
    areas: ["Adaptive apps", "UX"],
  },
  "sync-obsidian-notes": {
    title: "Files and folder sync",
    epic: "EPIC-SYNC",
    areas: ["Files and sync"],
  },
  "native-folder-sync": {
    title: "Files and folder sync",
    epic: "EPIC-SYNC",
    areas: ["Files and sync"],
  },
  "media-sync-foundations": {
    title: "Safe photo backup and Memories",
    epic: "EPIC-MEDIA",
    areas: ["Media", "Photos and Memories"],
  },
};

const scope = computed(() => scopes[props.slug] ?? scopes["adaptive-native-apps"]);
const epic = computed(() =>
  roadmap.epics.find((item) => item.taskId === scope.value.epic),
);

function statusKey(item) {
  if (item?.status === "Done") return "shipped";
  if (item?.status === "In Progress") return "active";
  return "planned";
}

function statusLabel(item) {
  return {
    shipped: "Shipped",
    active: "In progress",
    planned: "Planned",
  }[statusKey(item)];
}

function formatDate(value) {
  if (!value) return "Not available";
  return new Intl.DateTimeFormat("en", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  }).format(new Date(value));
}

const linkedItems = computed(() => {
  const byNumber = new Map();
  for (const item of [...(roadmap.shipped ?? []), ...roadmap.priorities]) {
    if (scope.value.areas.includes(item.area)) byNumber.set(item.number, item);
  }
  const statusOrder = { active: 0, planned: 1, shipped: 2 };
  return [...byNumber.values()]
    .sort(
      (left, right) =>
        statusOrder[statusKey(left)] - statusOrder[statusKey(right)] ||
        (left.taskId ?? "").localeCompare(right.taskId ?? ""),
    )
    .slice(0, 8);
});

const sourceLabel = computed(() => {
  if (roadmap.source === "github") return "GitHub Project and issues";
  if (roadmap.source === "github-snapshot") return "Bundled GitHub Project snapshot";
  return "Bundled roadmap document";
});
const syncLabel = computed(() => {
  if (roadmap.syncState === "live" && roadmap.updatedAt) {
    return formatDate(roadmap.updatedAt);
  }
  if (roadmap.syncState === "snapshot" && roadmap.updatedAt) {
    return `Snapshot from ${formatDate(roadmap.updatedAt)}`;
  }
  return "Live sync unavailable";
});
</script>

<template>
  <details id="delivery-roadmap" class="article-roadmap">
    <summary>
      <span class="summary-copy">
        <span class="summary-kicker">Delivery roadmap</span>
        <strong>{{ scope.title }}</strong>
        <small v-if="epic">{{ epic.milestone || "Target release not assigned" }}</small>
      </span>
      <span v-if="epic" class="quiet-status" :class="statusKey(epic)">
        <component
          :is="statusKey(epic) === 'shipped' ? PhCheckCircle : statusKey(epic) === 'active' ? PhClock : PhCircle"
          :size="14"
          weight="duotone"
          aria-hidden="true"
        />
        {{ statusLabel(epic) }}
      </span>
      <span class="summary-action">
        View linked issues
        <PhCaretDown :size="15" weight="bold" aria-hidden="true" />
      </span>
    </summary>

    <div class="article-roadmap-body">
      <div class="article-roadmap-source">
        <span><strong>Source</strong> {{ sourceLabel }}</span>
        <span><strong>Last synced</strong> {{ syncLabel }}</span>
      </div>

      <a
        v-if="epic"
        class="epic-row"
        :href="epic.url"
        target="_blank"
        rel="noreferrer"
      >
        <span>
          <small>Workstream</small>
          <strong>{{ epic.title }}</strong>
        </span>
        <span>{{ epic.taskId }}</span>
        <span>{{ epic.milestone || "Unscheduled" }}</span>
        <span class="issue-reference">
          #{{ epic.number }}
          <PhArrowSquareOut :size="13" weight="bold" aria-hidden="true" />
        </span>
      </a>

      <div v-if="linkedItems.length" class="linked-issues" aria-label="Related roadmap issues">
        <div class="linked-head issue-columns" aria-hidden="true">
          <span>Feature or capability</span>
          <span>Status</span>
          <span>Target</span>
          <span>Issue</span>
        </div>
        <a
          v-for="item in linkedItems"
          :key="item.number"
          class="linked-row issue-columns"
          :href="item.url"
          target="_blank"
          rel="noreferrer"
        >
          <span class="linked-title">
            <strong>{{ item.title }}</strong>
            <small>{{ item.taskId || `Issue #${item.number}` }}</small>
          </span>
          <span class="quiet-status" :class="statusKey(item)">
            <component
              :is="statusKey(item) === 'shipped' ? PhCheckCircle : statusKey(item) === 'active' ? PhClock : PhCircle"
              :size="14"
              weight="duotone"
              aria-hidden="true"
            />
            {{ statusLabel(item) }}
          </span>
          <span>{{ item.milestone || "Unscheduled" }}</span>
          <span class="issue-reference">
            #{{ item.number }}
            <PhArrowSquareOut :size="13" weight="bold" aria-hidden="true" />
          </span>
        </a>
      </div>

      <footer>
        <span>Showing {{ linkedItems.length }} directly related issues.</span>
        <a :href="roadmap.projectUrl" target="_blank" rel="noreferrer">
          Open the complete project
          <PhArrowSquareOut :size="14" weight="bold" aria-hidden="true" />
        </a>
      </footer>
    </div>
  </details>
</template>

<style scoped>
.article-roadmap {
  grid-area: roadmap;
  margin-top: 58px;
  border-block: 1px solid var(--outline);
}

.article-roadmap summary {
  min-height: 76px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 24px;
  padding: 12px 4px;
  cursor: pointer;
  list-style: none;
}

.article-roadmap summary::-webkit-details-marker {
  display: none;
}

.summary-copy {
  min-width: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: baseline;
  gap: 3px 14px;
}

.summary-kicker {
  color: var(--primary);
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.summary-copy strong {
  overflow: hidden;
  color: var(--text);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-copy small {
  grid-column: 2;
  color: var(--muted);
  font-size: 10px;
}

.summary-action,
.quiet-status,
.issue-reference {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.summary-action {
  color: var(--primary);
  font-size: 10px;
  font-weight: 700;
}

.article-roadmap[open] .summary-action svg {
  transform: rotate(180deg);
}

.quiet-status {
  color: var(--status-planned);
  font-size: 10px;
}

.quiet-status.shipped {
  color: var(--success);
}

.quiet-status.active {
  color: var(--status-active);
}

.article-roadmap-body {
  border-top: 1px solid var(--outline);
}

.article-roadmap-source {
  min-height: 40px;
  display: flex;
  align-items: center;
  gap: 22px;
  color: var(--muted);
  font-size: 9px;
}

.article-roadmap-source strong {
  margin-right: 4px;
  color: var(--text);
}

.epic-row {
  min-height: 58px;
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 90px minmax(150px, 0.7fr) 70px;
  align-items: center;
  gap: 18px;
  border-block: 1px solid var(--outline);
  color: var(--muted);
  font-size: 10px;
}

.epic-row > span:first-child {
  display: grid;
  gap: 3px;
}

.epic-row small {
  color: var(--text-subtle);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.epic-row strong {
  color: var(--text);
  font-size: 11px;
}

.issue-columns {
  display: grid;
  grid-template-columns: minmax(250px, 1.35fr) 100px minmax(150px, 0.75fr) 70px;
  align-items: center;
  gap: 18px;
}

.linked-head {
  min-height: 34px;
  border-bottom: 1px solid var(--outline);
  color: var(--text-subtle);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.linked-row {
  min-height: 54px;
  border-bottom: 1px solid var(--outline);
  color: var(--muted);
  font-size: 10px;
}

.linked-row:hover,
.linked-row:focus-visible,
.epic-row:hover,
.epic-row:focus-visible {
  background: var(--surface-low);
}

.linked-title {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.linked-title strong {
  overflow: hidden;
  color: var(--text);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.linked-title small {
  color: var(--text-subtle);
  font-size: 8px;
}

.issue-reference {
  color: var(--primary);
  font-size: 9px;
  font-weight: 700;
}

.article-roadmap footer {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  color: var(--muted);
  font-size: 9px;
}

.article-roadmap footer a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--primary);
  font-weight: 700;
}

@media (max-width: 700px) {
  .article-roadmap {
    margin-top: 42px;
  }

  .article-roadmap summary {
    min-height: 0;
    grid-template-columns: 1fr auto;
    gap: 8px 14px;
    padding: 16px 0;
  }

  .summary-copy {
    grid-template-columns: 1fr;
  }

  .summary-copy small {
    grid-column: 1;
  }

  .article-roadmap summary > .quiet-status {
    grid-column: 2;
    grid-row: 1;
  }

  .summary-action {
    grid-column: 1;
    grid-row: 2;
  }

  .article-roadmap-source {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
    padding: 10px 0;
  }

  .linked-head {
    display: none;
  }

  .epic-row,
  .linked-row {
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 6px 12px;
    padding: 13px 0;
  }

  .epic-row > span:first-child,
  .linked-title {
    grid-column: 1;
    grid-row: 1;
  }

  .epic-row > span:nth-child(2),
  .linked-row > .quiet-status {
    grid-column: 2;
    grid-row: 1;
  }

  .epic-row > span:nth-child(3),
  .linked-row > span:nth-child(3) {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .epic-row > .issue-reference,
  .linked-row > .issue-reference {
    grid-column: 1;
    grid-row: 3;
  }

  .article-roadmap footer {
    align-items: flex-start;
    flex-direction: column;
    padding-block: 12px;
  }
}
</style>
