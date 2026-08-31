<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { PhCaretDown, PhListBullets } from "@phosphor-icons/vue";
import { pageSections } from "../page-outline.js";

const props = defineProps({
  headings: { type: Array, default: () => [] },
  label: { type: String, default: "On this page" },
  expanded: { type: Boolean, default: false },
});
const sections = computed(() => pageSections(props.headings));
const currentAnchor = ref("");
function updateAnchor() {
  try {
    currentAnchor.value = decodeURIComponent(window.location.hash.slice(1));
  } catch {
    currentAnchor.value = "";
  }
}
onMounted(() => {
  updateAnchor();
  window.addEventListener("hashchange", updateAnchor);
});
onBeforeUnmount(() => window.removeEventListener("hashchange", updateAnchor));
</script>

<template>
  <details v-if="sections.length > 1" class="page-outline" :open="expanded">
    <summary><PhListBullets :size="17" aria-hidden="true" /><span>{{ label }}</span><PhCaretDown :size="15" aria-hidden="true" /></summary>
    <nav :aria-label="label">
      <a v-for="section in sections" :key="section.anchor" :href="`#${section.anchor}`" :aria-current="currentAnchor === section.anchor ? 'location' : undefined">{{ section.title }}</a>
    </nav>
  </details>
</template>
