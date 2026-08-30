<script setup>
import { computed, nextTick, ref } from "vue";
import {
  PhAndroidLogo as AndroidLogo,
  PhArrowRight as ArrowRight,
  PhLinuxLogo as LinuxLogo,
  PhWindowsLogo as WindowsLogo,
} from "@phosphor-icons/vue";
import { marketingCaptures } from "../generated/captures.js";

const props = defineProps({
  theme: { type: String, required: true },
  appFamilies: { type: Array, required: true },
});
defineEmits(["download"]);
const captures = new Map(marketingCaptures.map((capture) => [capture.scenario, capture]));
const heroDesktopCapture = computed(() => captures.get(props.theme === "light"
  ? "homepage-overview-desktop-light" : "homepage-overview-desktop-dark")
  ?? captures.get("desktop-home"));
const mobileHomeCapture = computed(() => captures.get(props.theme === "light"
  ? "homepage-overview-mobile-light" : "homepage-overview-mobile-dark")
  ?? captures.get("mobile-home"));
const activeAppFamily = ref(0);
const labels = ["Files", "Photos", "Talk", "Planning", "More apps"];
const selectedAppFamily = computed(() => props.appFamilies[activeAppFamily.value]);
const selectedAppCapture = computed(() => captures.get(props.theme === "light"
  ? selectedAppFamily.value.captureLight : selectedAppFamily.value.captureDark)
  ?? captures.get(selectedAppFamily.value.captureFallback));
async function onAppTabKeydown(event) {
  if (!["ArrowRight", "ArrowLeft", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  if (event.key === "Home") activeAppFamily.value = 0;
  else if (event.key === "End") activeAppFamily.value = props.appFamilies.length - 1;
  else activeAppFamily.value = (activeAppFamily.value + (event.key === "ArrowRight" ? 1 : -1) + props.appFamilies.length) % props.appFamilies.length;
  await nextTick();
  document.getElementById(`app-tab-${activeAppFamily.value}`)?.focus();
}
</script>

<template>
  <div class="native-home">
    <section id="product" class="native-hero section-width" aria-labelledby="home-title">
      <div class="native-hero-copy">
        <p class="native-eyebrow">Independent. Open source.</p>
        <h1 id="home-title">Your whole Nextcloud.<br />One native workspace.</h1>
        <p class="native-lede">Files, photos, conversations and planning,<br class="desktop-break" /> together in a real app.</p>
        <div class="native-hero-actions">
          <button class="native-button" type="button" @click="$emit('download')">Get Nextcloud Native</button>
          <a class="native-text-link" href="#apps">Explore the app <ArrowRight :size="20" aria-hidden="true" /></a>
        </div>
        <p class="native-alpha">Alpha for Android, Linux and Windows</p>
      </div>
      <figure class="native-hero-visual">
        <img class="native-hero-desktop" :src="heroDesktopCapture.websitePath"
          alt="Nextcloud Native desktop home view with account status, recent files, activity, events, and photo backup"
          :width="heroDesktopCapture.width" :height="heroDesktopCapture.height" fetchpriority="high" />
        <img class="native-hero-mobile" :src="mobileHomeCapture.websitePath"
          alt="Nextcloud Native mobile home with files, conversations, events, and sync status"
          :width="mobileHomeCapture.width" :height="mobileHomeCapture.height" />
        <figcaption class="sr-only">Real native UI. Synthetic private data.</figcaption>
      </figure>
    </section>

    <section class="native-platforms" aria-label="Platform availability">
      <div class="section-width native-platform-row">
        <button type="button" @click="$emit('download')"><AndroidLogo :size="29" weight="fill" aria-hidden="true" />Android</button>
        <button type="button" @click="$emit('download')"><LinuxLogo :size="29" aria-hidden="true" />Linux</button>
        <button type="button" @click="$emit('download')"><WindowsLogo :size="29" weight="fill" aria-hidden="true" />Windows</button>
        <a href="/platforms/" class="native-platform-preview">macOS preview <span aria-hidden="true">/</span> iOS planned</a>
      </div>
    </section>

    <section id="apps" class="native-apps section-width" aria-labelledby="apps-title">
      <div class="native-apps-copy">
        <h2 id="apps-title">Every app keeps<br />what makes it useful.</h2>
        <p class="native-app-intro">Mail is a mailbox, Deck is a board, Tables is a table, and Memories is a photo library. Shared native building blocks make navigation and actions predictable without flattening everything into a generic data screen.</p>
        <div class="native-app-tabs" role="tablist" aria-label="Explore Nextcloud apps" @keydown="onAppTabKeydown">
          <button v-for="(family, index) in appFamilies" :id="`app-tab-${index}`" :key="family.title"
            type="button" role="tab" :aria-selected="activeAppFamily === index" aria-controls="app-preview"
            :tabindex="activeAppFamily === index ? 0 : -1" @click="activeAppFamily = index">
            <component :is="family.icon" :size="17" aria-hidden="true" />{{ labels[index] }}
          </button>
        </div>
        <p class="native-app-description">{{ selectedAppFamily.body }}</p>
        <a class="native-text-link" href="/compatibility/">See all apps <ArrowRight :size="18" aria-hidden="true" /></a>
      </div>
      <figure id="app-preview" class="native-app-preview" role="tabpanel" :aria-labelledby="`app-tab-${activeAppFamily}`" tabindex="0">
        <div class="native-app-capture" :style="{ aspectRatio: `${selectedAppCapture.width} / ${selectedAppCapture.height}` }">
          <Transition name="capture-swap">
            <img :key="selectedAppCapture.scenario" :src="selectedAppCapture.websitePath"
              :alt="`${selectedAppFamily.title} shown in the real Nextcloud Native Compose interface with synthetic data`"
              :width="selectedAppCapture.width" :height="selectedAppCapture.height" loading="lazy" />
          </Transition>
        </div>
        <figcaption>{{ selectedAppFamily.apps }}. Synthetic data, captured from the app.</figcaption>
      </figure>
    </section>

    <section class="native-personal section-width">
      <p>Arrange your workspace. Make it <a href="/guides/desktop/switch-apps/">your own.</a></p>
      <p class="native-personal-detail">Pin your apps, arrange your home, and choose light or dark.</p>
    </section>

    <slot />
  </div>
</template>
