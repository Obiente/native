<script setup>
import {
  PhArrowSquareOut,
  PhCaretDown,
  PhCheckCircle,
  PhCircle,
  PhClock,
  PhMagnifyingGlass,
} from "@phosphor-icons/vue";
import { computed, ref, watch } from "vue";
import { roadmap } from "../generated/roadmap.js";

const query = ref("");
const area = ref("All areas");
const status = ref("All statuses");
const platform = ref("All platforms");
const visibleCount = ref(24);

const outcomeByTask = {
  "EPIC-MEDIA": "Verified media backup, storage recovery, and cloud-only sharing.",
  "EPIC-SYNC": "Files, offline access, transfers, and revision-safe folder sync.",
  "EPIC-DAV": "Accounts, calendars, contacts, tasks, and device synchronization.",
  "EPIC-TALK": "Messages, attachments, calls, notifications, and moderation.",
  "EPIC-PHOTO": "Albums, people, RAW, Live Photos, editing, and sharing.",
  "EPIC-DYN": "Useful native interfaces for installed and unfamiliar Nextcloud apps.",
  "EPIC-PLATFORM": "Responsive UX, accessibility, performance, packaging, and releases.",
};

const sourceLabel = computed(() =>
  roadmap.source === "github" ? "GitHub Project and issues" : "Bundled roadmap document",
);
const syncLabel = computed(() =>
  roadmap.syncState === "live" && roadmap.updatedAt
    ? formatDate(roadmap.updatedAt)
    : "Live sync unavailable",
);

function formatDate(value) {
  if (!value) return "Not available";
  return new Intl.DateTimeFormat("en", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(value));
}

function statusKey(item) {
  if (item.status === "Done") return "shipped";
  if (item.status === "In Progress") return "active";
  return "planned";
}

function statusLabel(item) {
  return { shipped: "Shipped", active: "In progress", planned: "Planned" }[statusKey(item)];
}

function milestoneStatus(milestone) {
  if (milestone.state === "closed") return "shipped";
  if (milestone.closed > 0) return "active";
  return "planned";
}

function milestoneStatusLabel(milestone) {
  return { shipped: "Shipped", active: "In progress", planned: "Planned" }[
    milestoneStatus(milestone)
  ];
}

function platformScope(item) {
  const value = `${item.taskId ?? ""} ${item.title} ${item.area ?? ""}`.toLowerCase();
  const mobile =
    /android|ios|mobile|phone|mediastore|camera|photo permission|background sync/.test(value);
  const desktop = /desktop|linux|windows|macos|keyboard|mouse/.test(value);
  if (mobile && !desktop) return "Mobile";
  if (desktop && !mobile) return "Desktop";
  return "Cross-platform";
}

function issueReference(item) {
  return `#${item.number}`;
}

function workstreamOutcome(item) {
  return (
    outcomeByTask[item.taskId] ??
    (item.number === 185
      ? "Users, apps, security, jobs, and server settings."
      : "Tracked capability with acceptance work linked in GitHub.")
  );
}

const productIssues = computed(() => {
  const byNumber = new Map();
  for (const item of [...(roadmap.shipped ?? []), ...roadmap.priorities]) {
    byNumber.set(item.number, item);
  }
  return [...byNumber.values()].sort((left, right) => {
    const statusOrder = { active: 0, planned: 1, shipped: 2 };
    return (
      statusOrder[statusKey(left)] - statusOrder[statusKey(right)] ||
      (left.milestone ?? "zz").localeCompare(right.milestone ?? "zz") ||
      (left.taskId ?? "").localeCompare(right.taskId ?? "")
    );
  });
});

const progress = computed(() => {
  const counts = { shipped: 0, active: 0, planned: 0 };
  for (const issue of productIssues.value) counts[statusKey(issue)] += 1;
  const total = productIssues.value.length;
  return {
    ...counts,
    total,
    percent: total ? Math.round((counts.shipped / total) * 100) : 0,
  };
});

const currentMilestone = computed(
  () =>
    roadmap.milestones.find((milestone) => milestone.state === "open" && milestone.closed > 0) ??
    roadmap.milestones.find((milestone) => milestone.state === "open") ??
    roadmap.milestones[0],
);

const areas = computed(() => [
  "All areas",
  ...new Set(productIssues.value.map((item) => item.area).filter(Boolean)),
]);
const platforms = computed(() => [
  "All platforms",
  ...new Set(productIssues.value.map(platformScope)),
]);

const filteredIssues = computed(() => {
  const term = query.value.trim().toLowerCase();
  return productIssues.value.filter((item) => {
    if (area.value !== "All areas" && item.area !== area.value) return false;
    if (platform.value !== "All platforms" && platformScope(item) !== platform.value) return false;
    if (status.value !== "All statuses" && statusLabel(item) !== status.value) return false;
    if (!term) return true;
    return `${item.taskId ?? ""} ${item.title} ${item.area ?? ""} ${item.milestone ?? ""} ${item.number}`
      .toLowerCase()
      .includes(term);
  });
});

const visibleIssues = computed(() => filteredIssues.value.slice(0, visibleCount.value));

watch([query, area, status, platform], () => {
  visibleCount.value = 24;
});
</script>

<template>
  <section class="roadmap-ledger" aria-labelledby="roadmap-ledger-title">
    <header class="ledger-heading">
      <div>
        <p class="eyebrow">Public delivery plan</p>
        <h2 id="roadmap-ledger-title">Release and feature status</h2>
        <p>Project status derived from the public GitHub issues and milestones.</p>
      </div>
      <a :href="roadmap.projectUrl" target="_blank" rel="noreferrer">
        Open GitHub Project
        <PhArrowSquareOut :size="16" weight="bold" aria-hidden="true" />
      </a>
    </header>

    <div class="ledger-source">
      <span><strong>Source</strong> {{ sourceLabel }}</span>
      <span><strong>Last synced</strong> {{ syncLabel }}</span>
    </div>

    <section
      v-if="roadmap.source === 'github'"
      class="progress-overview"
      aria-label="Tracked issue progress"
    >
      <div class="progress-focus">
        <span>Current release focus</span>
        <strong>{{ currentMilestone?.title || "Unscheduled" }}</strong>
        <small>{{ currentMilestone?.description || "Scope is tracked in the linked milestone." }}</small>
      </div>
      <div class="progress-measure">
        <div>
          <strong>{{ progress.shipped }} of {{ progress.total }}</strong>
          <span>tracked P0/P1 issues shipped</span>
        </div>
        <div class="progress-track" aria-hidden="true">
          <span :style="{ width: `${progress.percent}%` }"></span>
        </div>
        <ul aria-label="Issue counts by status">
          <li class="shipped"><strong>{{ progress.shipped }}</strong> shipped</li>
          <li class="active"><strong>{{ progress.active }}</strong> in progress</li>
          <li class="planned"><strong>{{ progress.planned }}</strong> planned</li>
        </ul>
      </div>
    </section>

    <details v-if="roadmap.source === 'github'" class="ledger-disclosure release-sequence">
      <summary>
        <span>
          <strong>Release milestones</strong>
          <small>{{ roadmap.milestones.length }} targets · focus {{ currentMilestone?.title }}</small>
        </span>
        <PhCaretDown :size="16" weight="bold" aria-hidden="true" />
      </summary>
      <div class="disclosure-body">
        <div class="section-tool">
          <p>Target milestones from the public repository.</p>
          <a href="https://github.com/Obiente/nc-native/milestones" target="_blank" rel="noreferrer">
            All milestones
            <PhArrowSquareOut :size="14" weight="bold" aria-hidden="true" />
          </a>
        </div>
        <div class="release-table" aria-label="Release milestones">
          <div class="table-head release-columns" aria-hidden="true">
            <span>Release</span>
            <span>Status</span>
            <span>Delivery scope</span>
            <span>Repository</span>
          </div>
          <a
            v-for="milestone in roadmap.milestones"
            :key="milestone.number"
            class="release-row release-columns"
            :href="milestone.url"
            target="_blank"
            rel="noreferrer"
          >
            <strong>{{ milestone.title }}</strong>
            <span class="quiet-status" :class="milestoneStatus(milestone)">
              <component
                :is="milestoneStatus(milestone) === 'shipped' ? PhCheckCircle : milestoneStatus(milestone) === 'active' ? PhClock : PhCircle"
                :size="14"
                weight="duotone"
                aria-hidden="true"
              />
              {{ milestoneStatusLabel(milestone) }}
            </span>
            <span>
              {{ milestone.description || `${milestone.closed} linked issues closed · ${milestone.open} open` }}
            </span>
            <span class="issue-link">
              Milestone {{ milestone.number }}
              <PhArrowSquareOut :size="13" weight="bold" aria-hidden="true" />
            </span>
          </a>
        </div>
      </div>
    </details>

    <section
      v-if="roadmap.source === 'github'"
      class="workstream-section"
      aria-labelledby="workstream-title"
    >
      <header>
        <div>
          <h3 id="workstream-title">Product workstreams</h3>
          <p>Capabilities, outcomes, and current target releases.</p>
        </div>
      </header>
      <div class="workstream-table" aria-label="Product workstreams">
        <div class="table-head workstream-columns" aria-hidden="true">
          <span>Capability and outcome</span>
          <span>Status</span>
          <span>Target</span>
          <span>Issue</span>
        </div>
        <a
          v-for="epic in roadmap.epics"
          :key="epic.number"
          class="workstream-row workstream-columns"
          :href="epic.url"
          target="_blank"
          rel="noreferrer"
        >
          <span class="feature-name">
            <strong>{{ epic.title }}</strong>
            <small>{{ workstreamOutcome(epic) }}</small>
          </span>
          <span class="quiet-status" :class="statusKey(epic)">
            <component
              :is="statusKey(epic) === 'shipped' ? PhCheckCircle : statusKey(epic) === 'active' ? PhClock : PhCircle"
              :size="14"
              weight="duotone"
              aria-hidden="true"
            />
            {{ statusLabel(epic) }}
          </span>
          <span>{{ epic.milestone || "Unscheduled" }}</span>
          <span class="issue-link">
            {{ issueReference(epic) }}
            <PhArrowSquareOut :size="13" weight="bold" aria-hidden="true" />
          </span>
        </a>
      </div>
    </section>

    <details v-if="roadmap.source === 'github'" class="ledger-disclosure issue-browser">
      <summary>
        <span>
          <strong>Feature issue browser</strong>
          <small>{{ productIssues.length }} tracked issues · search and filter</small>
        </span>
        <PhCaretDown :size="16" weight="bold" aria-hidden="true" />
      </summary>
      <div class="disclosure-body">
        <form class="roadmap-filters" role="search" @submit.prevent>
          <label class="roadmap-search">
            <span>Search features and issues</span>
            <span>
              <PhMagnifyingGlass :size="16" weight="bold" aria-hidden="true" />
              <input v-model="query" type="search" placeholder="Feature, task ID, or issue" />
            </span>
          </label>
          <label>
            <span>Product area</span>
            <select v-model="area">
              <option v-for="option in areas" :key="option">{{ option }}</option>
            </select>
          </label>
          <label>
            <span>Platform</span>
            <select v-model="platform">
              <option v-for="option in platforms" :key="option">{{ option }}</option>
            </select>
          </label>
          <label>
            <span>Status</span>
            <select v-model="status">
              <option>All statuses</option>
              <option>Shipped</option>
              <option>In progress</option>
              <option>Planned</option>
            </select>
          </label>
        </form>

        <div class="issue-table" aria-label="Feature roadmap issues">
          <div class="table-head issue-columns">
            <span>Feature or capability</span>
            <span>Status</span>
            <span>Target release</span>
            <span>Issue</span>
          </div>
          <details v-for="item in visibleIssues" :key="item.number" class="issue-record">
            <summary class="issue-columns">
              <span class="feature-name">
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
              <span class="record-reference">
                {{ issueReference(item) }}
                <PhCaretDown :size="13" weight="bold" aria-hidden="true" />
              </span>
            </summary>
            <div class="issue-record-details">
              <span><strong>Area</strong> {{ item.area || "Product" }}</span>
              <span><strong>Platform</strong> {{ platformScope(item) }}</span>
              <span><strong>Task ID</strong> {{ item.taskId || "Not assigned" }}</span>
              <a :href="item.url" target="_blank" rel="noreferrer">
                Open issue {{ issueReference(item) }}
                <PhArrowSquareOut :size="13" weight="bold" aria-hidden="true" />
              </a>
            </div>
          </details>
        </div>

        <div v-if="visibleIssues.length < filteredIssues.length" class="table-more">
          <span>Showing {{ visibleIssues.length }} of {{ filteredIssues.length }}</span>
          <button type="button" @click="visibleCount += 24">Show 24 more</button>
        </div>
        <p v-else-if="filteredIssues.length === 0" class="table-empty">
          No roadmap issues match these filters.
        </p>
      </div>
    </details>

    <p v-if="roadmap.source !== 'github'" class="roadmap-fallback-note">
      Live GitHub data was unavailable during this build, so no potentially stale issue status
      or progress totals are shown. Read the detailed roadmap below or open the canonical project.
    </p>
  </section>
</template>

<style scoped>
.roadmap-ledger {
  margin-top: 30px;
  overflow: hidden;
  border: 1px solid var(--outline);
  border-radius: var(--radius-large);
  background: var(--surface-low);
}

.ledger-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 30px;
  padding: 22px 26px;
  border-bottom: 1px solid var(--outline);
}

.ledger-heading > div {
  max-width: 700px;
}

.ledger-heading .eyebrow {
  margin-bottom: 6px;
}

.ledger-heading h2 {
  margin: 0;
  font-size: clamp(24px, 2.4vw, 34px);
  letter-spacing: -0.04em;
}

.ledger-heading p:not(.eyebrow),
.workstream-section header p,
.section-tool p {
  margin: 7px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}

.ledger-heading > a,
.section-tool > a {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  flex: 0 0 auto;
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
}

.ledger-source {
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 26px;
  border-bottom: 1px solid var(--outline);
  color: var(--muted);
  font-size: 10px;
}

.ledger-source strong {
  margin-right: 5px;
  color: var(--text);
  font-weight: 650;
}

.progress-overview {
  display: grid;
  grid-template-columns: minmax(250px, 0.7fr) minmax(420px, 1.3fr);
  gap: 34px;
  padding: 20px 26px;
  border-bottom: 1px solid var(--outline);
}

.progress-focus,
.progress-measure {
  min-width: 0;
  display: grid;
  align-content: center;
}

.progress-focus span,
.progress-measure span,
.progress-focus small {
  color: var(--muted);
  font-size: 10px;
}

.progress-focus strong {
  margin: 4px 0 3px;
  color: var(--text);
  font-size: 15px;
}

.progress-measure > div:first-child {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.progress-measure > div:first-child strong {
  font-size: 16px;
}

.progress-track {
  height: 3px;
  margin: 10px 0 9px;
  overflow: hidden;
  border-radius: 99px;
  background: var(--outline);
}

.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--success);
}

.progress-measure ul {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
  color: var(--muted);
  font-size: 10px;
}

.progress-measure li::before {
  content: "";
  width: 6px;
  height: 6px;
  display: inline-block;
  margin-right: 6px;
  border-radius: 50%;
  background: currentColor;
}

.progress-measure li.shipped {
  color: var(--success);
}

.progress-measure li.active {
  color: var(--status-active);
}

.progress-measure li.planned {
  color: var(--status-planned);
}

.progress-measure li strong {
  color: inherit;
}

.ledger-disclosure {
  border-bottom: 1px solid var(--outline);
}

.ledger-disclosure > summary {
  min-height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 0 26px;
  cursor: pointer;
  list-style: none;
}

.ledger-disclosure > summary::-webkit-details-marker,
.issue-record > summary::-webkit-details-marker {
  display: none;
}

.ledger-disclosure > summary:hover,
.ledger-disclosure > summary:focus-visible {
  background: #15181e;
}

.ledger-disclosure > summary > span {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.ledger-disclosure > summary strong {
  color: var(--text);
  font-size: 14px;
}

.ledger-disclosure > summary small {
  color: var(--muted);
  font-size: 10px;
}

.ledger-disclosure > summary > svg {
  transition: transform 160ms ease;
}

.ledger-disclosure[open] > summary > svg {
  transform: rotate(180deg);
}

.disclosure-body {
  padding: 0 26px 24px;
}

.section-tool,
.workstream-section > header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
}

.section-tool p {
  margin: 0;
}

.workstream-section {
  padding: 22px 26px 24px;
  border-bottom: 1px solid var(--outline);
}

.workstream-section h3 {
  margin: 0;
  font-size: 16px;
  letter-spacing: -0.025em;
}

.release-table,
.workstream-table,
.issue-table {
  border-top: 1px solid var(--outline);
}

.release-columns {
  display: grid;
  grid-template-columns: minmax(140px, 0.7fr) 108px minmax(280px, 1.6fr) 115px;
  gap: 18px;
}

.workstream-columns {
  display: grid;
  grid-template-columns: minmax(300px, 1.7fr) 108px minmax(145px, 0.8fr) 66px;
  gap: 18px;
}

.issue-columns {
  display: grid;
  grid-template-columns: minmax(280px, 1.7fr) 108px minmax(145px, 0.8fr) 68px;
  gap: 16px;
}

.table-head {
  min-height: 34px;
  align-items: center;
  border-bottom: 1px solid var(--outline);
  color: #85838d;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.table-head > span:first-child {
  padding-left: 8px;
}

.release-row,
.workstream-row,
.issue-record > summary {
  min-height: 54px;
  align-items: center;
  border-bottom: 1px solid var(--outline);
  color: #c7c4cc;
  font-size: 10px;
  line-height: 1.4;
}

.release-row:last-child,
.workstream-row:last-child,
.issue-record:last-child > summary,
.issue-record:last-child .issue-record-details {
  border-bottom: 0;
}

.release-row:hover,
.release-row:focus-visible,
.workstream-row:hover,
.workstream-row:focus-visible,
.issue-record > summary:hover,
.issue-record > summary:focus-visible {
  background: #15181e;
}

.release-row > strong,
.feature-name {
  padding-left: 8px;
}

.release-row > strong {
  color: var(--text);
  font-size: 11px;
}

.feature-name {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.feature-name strong {
  overflow: hidden;
  color: var(--text);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.feature-name small {
  overflow: hidden;
  color: #85838d;
  font-size: 9px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quiet-status,
.issue-link,
.record-reference {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.quiet-status svg {
  flex: 0 0 auto;
}

.quiet-status.shipped {
  color: var(--success);
}

.quiet-status.active {
  color: var(--status-active);
}

.quiet-status.planned {
  color: var(--status-planned);
}

.issue-link,
.record-reference {
  color: var(--primary);
  font-size: 10px;
  font-weight: 700;
}

.issue-record > summary {
  cursor: pointer;
  list-style: none;
}

.record-reference svg {
  transition: transform 160ms ease;
}

.issue-record[open] .record-reference svg {
  transform: rotate(180deg);
}

.issue-record-details {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 22px;
  padding: 11px 16px;
  border-bottom: 1px solid var(--outline);
  background: #111319;
  color: var(--muted);
  font-size: 9px;
}

.issue-record-details strong {
  margin-right: 4px;
  color: #b7b4bd;
}

.issue-record-details a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  color: var(--primary);
  font-weight: 700;
}

.roadmap-filters {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) repeat(3, minmax(130px, 0.44fr));
  gap: 10px;
  margin-bottom: 14px;
}

.roadmap-filters label {
  min-width: 0;
  display: grid;
  gap: 6px;
  color: #85838d;
  font-size: 9px;
  font-weight: 700;
}

.roadmap-filters select,
.roadmap-search > span:last-child {
  min-width: 0;
  height: 38px;
  border: 1px solid var(--outline-strong);
  border-radius: var(--radius-control);
  background: var(--background);
  color: var(--text);
  font: inherit;
  font-size: 10px;
}

.roadmap-filters select {
  padding: 0 10px;
}

.roadmap-search > span:last-child {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  color: var(--muted);
}

.roadmap-search input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 10px;
}

.table-more {
  min-height: 46px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
  border-top: 1px solid var(--outline);
  color: var(--muted);
  font-size: 10px;
}

.table-more button {
  padding: 7px 10px;
  border: 1px solid var(--outline-strong);
  border-radius: var(--radius-control);
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 10px;
  cursor: pointer;
}

.table-empty,
.roadmap-fallback-note {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
}

.table-empty {
  padding: 26px 8px;
  border-top: 1px solid var(--outline);
}

.roadmap-fallback-note {
  padding: 13px 26px;
}

@media (max-width: 900px) {
  .progress-overview {
    grid-template-columns: 1fr;
    gap: 17px;
  }

  .release-columns {
    grid-template-columns: minmax(130px, 0.8fr) 100px minmax(230px, 1.5fr) 105px;
  }

  .workstream-columns,
  .issue-columns {
    grid-template-columns: minmax(240px, 1.6fr) 100px 135px 62px;
  }

  .roadmap-filters {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 780px) {
  .roadmap-ledger {
    margin-top: 22px;
    border-radius: var(--radius-surface);
  }

  .ledger-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
    padding: 20px 17px;
  }

  .ledger-source {
    min-height: 0;
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
    padding: 11px 17px;
  }

  .progress-overview,
  .workstream-section {
    padding: 18px 17px;
  }

  .ledger-disclosure > summary {
    min-height: 62px;
    padding: 0 17px;
  }

  .ledger-disclosure > summary > span {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .disclosure-body {
    padding: 0 17px 20px;
  }

  .section-tool,
  .workstream-section > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .table-head {
    display: none;
  }

  .release-row,
  .workstream-row,
  .issue-record > summary {
    min-height: 0;
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 7px 12px;
    padding: 13px 4px;
  }

  .release-row > strong,
  .feature-name {
    padding-left: 0;
  }

  .release-row > strong,
  .feature-name {
    grid-column: 1;
    grid-row: 1;
  }

  .release-row > .quiet-status,
  .workstream-row > .quiet-status,
  .issue-record > summary > .quiet-status {
    grid-column: 2;
    grid-row: 1;
  }

  .release-row > :nth-child(3) {
    grid-column: 1 / -1;
    grid-row: 2;
  }

  .release-row > .issue-link {
    grid-column: 1;
    grid-row: 3;
  }

  .workstream-row > :nth-child(3),
  .issue-record > summary > :nth-child(3) {
    grid-column: 1;
    grid-row: 2;
    color: var(--muted);
  }

  .workstream-row > .issue-link,
  .record-reference {
    grid-column: 1;
    grid-row: 3;
  }

  .feature-name strong,
  .feature-name small {
    overflow: visible;
    text-overflow: clip;
    white-space: normal;
  }

  .issue-record-details {
    align-items: flex-start;
    flex-direction: column;
    gap: 7px;
  }

  .issue-record-details a {
    margin-left: 0;
  }

  .roadmap-filters {
    grid-template-columns: 1fr;
  }
}
</style>
