<script setup>
import {
  PhArrowRight as ArrowRight,
  PhCheckCircle as CheckCircle,
  PhFlag as Flag,
  PhGitBranch as GitBranch,
  PhPulse as Pulse,
} from "@phosphor-icons/vue";
import { computed } from "vue";
import { roadmap } from "../generated/roadmap.js";

const milestoneProgress = (milestone) => {
  const total = milestone.open + milestone.closed;
  return total === 0 ? 0 : Math.round((milestone.closed / total) * 100);
};

const epicProgress = (epic) => epic.progress?.percent_completed ?? 0;
const issueMetadata = (item) => [item.area, item.milestone].filter(Boolean).join(" · ");
const priorityPreview = computed(() => roadmap.priorities.slice(0, 10));
const verificationPreview = computed(() => roadmap.verification.slice(0, 6));
</script>

<template>
  <section class="roadmap-dashboard" aria-labelledby="roadmap-dashboard-title">
    <header class="roadmap-dashboard-heading">
      <div>
        <p class="eyebrow">Live delivery plan</p>
        <h2 id="roadmap-dashboard-title">From direction to reviewable work.</h2>
        <p>
          The public roadmap mirrors GitHub issues, milestones, and acceptance criteria.
          Every implementation moves through an issue branch and pull request.
        </p>
      </div>
      <a class="button button-secondary" :href="roadmap.projectUrl" target="_blank" rel="noreferrer">
        Open the project
        <ArrowRight :size="18" weight="bold" aria-hidden="true" />
      </a>
    </header>

    <div class="roadmap-summary" aria-label="Roadmap summary">
      <article>
        <Flag :size="22" weight="duotone" aria-hidden="true" />
        <strong>{{ roadmap.milestones.length }}</strong>
        <span>release tracks</span>
      </article>
      <article>
        <Pulse :size="22" weight="duotone" aria-hidden="true" />
        <strong>{{ roadmap.priorities.length }}</strong>
        <span>open P0/P1 issues</span>
      </article>
      <article>
        <CheckCircle :size="22" weight="duotone" aria-hidden="true" />
        <strong>{{ roadmap.verification.length }}</strong>
        <span>awaiting verification</span>
      </article>
    </div>

    <div v-if="roadmap.milestones.length" class="roadmap-block">
      <div class="roadmap-block-heading">
        <div>
          <span>Release path</span>
          <h3>Milestones keep the order visible.</h3>
        </div>
        <a href="https://github.com/Obiente/nc-native/milestones" target="_blank" rel="noreferrer">
          All milestones
        </a>
      </div>
      <div class="milestone-track">
        <a
          v-for="milestone in roadmap.milestones"
          :key="milestone.number"
          class="milestone-card"
          :href="milestone.url"
          target="_blank"
          rel="noreferrer"
        >
          <span>{{ milestone.title }}</span>
          <strong>{{ milestoneProgress(milestone) }}%</strong>
          <div class="roadmap-progress" aria-hidden="true">
            <span :style="{ width: `${milestoneProgress(milestone)}%` }"></span>
          </div>
          <small>{{ milestone.closed }} complete · {{ milestone.open }} open</small>
        </a>
      </div>
    </div>

    <div class="roadmap-block">
      <div class="roadmap-block-heading">
        <div>
          <span>Workstreams</span>
          <h3>Seven connected parts of one client.</h3>
        </div>
        <a
          href="https://github.com/orgs/Obiente/projects/4/views/5"
          target="_blank"
          rel="noreferrer"
        >
          Epic progress
        </a>
      </div>
      <div class="epic-grid">
        <a
          v-for="epic in roadmap.epics"
          :key="epic.taskId"
          class="epic-card"
          :href="epic.url"
          target="_blank"
          rel="noreferrer"
        >
          <span class="roadmap-pill">{{ epic.area }}</span>
          <h4>{{ epic.title }}</h4>
          <div class="roadmap-progress" aria-hidden="true">
            <span :style="{ width: `${epicProgress(epic)}%` }"></span>
          </div>
          <small v-if="epic.progress">
            {{ epic.progress.completed }} of {{ epic.progress.total }} issues complete
          </small>
          <small v-else>Open the epic for current acceptance criteria</small>
        </a>
      </div>
    </div>

    <div v-if="priorityPreview.length" class="roadmap-columns">
      <section class="roadmap-block roadmap-list-block">
        <div class="roadmap-block-heading">
          <div>
            <span>Current priorities</span>
            <h3>Safety and parity first.</h3>
          </div>
          <a
            href="https://github.com/orgs/Obiente/projects/4/views/3"
            target="_blank"
            rel="noreferrer"
          >
            Full view
          </a>
        </div>
        <div class="roadmap-issue-list">
          <a
            v-for="item in priorityPreview"
            :key="item.taskId"
            :href="item.url"
            target="_blank"
            rel="noreferrer"
          >
            <span class="roadmap-issue-id">{{ item.taskId }}</span>
            <span>
              <strong>{{ item.title }}</strong>
              <small v-if="issueMetadata(item)">{{ issueMetadata(item) }}</small>
            </span>
            <span class="roadmap-priority">{{ item.priority }}</span>
          </a>
        </div>
      </section>

      <section class="roadmap-block roadmap-list-block">
        <div class="roadmap-block-heading">
          <div>
            <span>Verification queue</span>
            <h3>Implemented is not the same as proven.</h3>
          </div>
          <a
            href="https://github.com/orgs/Obiente/projects/4/views/4"
            target="_blank"
            rel="noreferrer"
          >
            Full view
          </a>
        </div>
        <div class="roadmap-issue-list compact">
          <a
            v-for="item in verificationPreview"
            :key="item.taskId"
            :href="item.url"
            target="_blank"
            rel="noreferrer"
          >
            <GitBranch :size="18" weight="duotone" aria-hidden="true" />
            <span>
              <strong>{{ item.title }}</strong>
              <small>{{ item.taskId }} · {{ item.area }}</small>
            </span>
          </a>
        </div>
      </section>
    </div>

    <p v-if="roadmap.source !== 'github'" class="roadmap-fallback-note">
      Live GitHub data was unavailable during this build. The workstream links remain current,
      and the repository roadmap continues below.
    </p>
  </section>
</template>
