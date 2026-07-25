<script setup>
import { computed, reactive, ref } from "vue";
import {
  PhArrowLeft,
  PhCamera,
  PhChatCircleDots,
  PhCheck,
  PhDotsThree,
  PhDownloadSimple,
  PhFile,
  PhFileText,
  PhFilmStrip,
  PhFolder,
  PhGridFour,
  PhImage,
  PhListBullets,
  PhMagnifyingGlass,
  PhPaperPlaneRight,
  PhPhone,
  PhShareNetwork,
  PhSquaresFour,
  PhVideoCamera,
  PhX,
} from "@phosphor-icons/vue";

const activeApp = ref("files");
const fileLayout = ref("list");
const fileQuery = ref("");
const selectedFileId = ref("launch-film");
const fileMenuOpen = ref(false);
const photoView = ref("timeline");
const selectedPhotoId = ref(null);
const activeConversationId = ref("design");
const messageDraft = ref("");
const feedback = ref("");

const navigation = [
  { id: "files", label: "Files", icon: PhFolder },
  { id: "photos", label: "Photos", icon: PhCamera },
  { id: "talk", label: "Talk", icon: PhChatCircleDots },
  { id: "apps", label: "All apps", icon: PhSquaresFour, disabled: true },
];

const files = [
  {
    id: "photos",
    name: "Project photos",
    kind: "Folder",
    meta: "24 items",
    modified: "Just now",
    icon: PhFolder,
  },
  {
    id: "notes",
    name: "Field notes.md",
    kind: "Markdown",
    meta: "18 KB",
    modified: "12 min ago",
    icon: PhFileText,
  },
  {
    id: "launch-film",
    name: "Launch film.mov",
    kind: "Video",
    meta: "1.2 GB",
    modified: "Yesterday",
    icon: PhFilmStrip,
  },
  {
    id: "brief",
    name: "Creative brief.pdf",
    kind: "PDF",
    meta: "2.4 MB",
    modified: "Tuesday",
    icon: PhFile,
  },
];

const visibleFiles = computed(() => {
  const query = fileQuery.value.toLowerCase().trim();
  if (!query) return files;
  return files.filter((file) => `${file.name} ${file.kind}`.toLowerCase().includes(query));
});
const selectedFile = computed(
  () => files.find((file) => file.id === selectedFileId.value) ?? files[0],
);

const photos = [
  {
    id: "forest",
    src: "/demo-media/forest-trail.webp",
    alt: "A wet forest trail surrounded by green trees",
    title: "Forest walk",
    meta: "RAW + JPEG · Today",
  },
  {
    id: "shore",
    src: "/demo-media/north-sea.webp",
    alt: "A quiet shoreline with low dunes and beach grass",
    title: "Evening at the shore",
    meta: "Album · 18 photos",
  },
  {
    id: "notes",
    src: "/demo-media/field-notes.webp",
    alt: "A notebook and cup on a wooden desk beside a window",
    title: "Field notes",
    meta: "Favourite · Yesterday",
  },
];
const selectedPhoto = computed(
  () => photos.find((photo) => photo.id === selectedPhotoId.value) ?? null,
);

const conversations = [
  { id: "design", name: "Design team", preview: "Mina shared 3 photos", time: "Now", unread: 2 },
  { id: "family", name: "Family", preview: "Call ended · 24 min", time: "14:20", unread: 0 },
  { id: "updates", name: "Project updates", preview: "You: Ready for review", time: "Tue", unread: 0 },
];

const messages = reactive({
  design: [
    { id: 1, sender: "Mina", body: "The new gallery flow is ready to review.", time: "14:28" },
    { id: 2, sender: "Mina", body: "Shared 3 photos from Project photos", time: "14:29", attachment: true },
    { id: 3, sender: "You", body: "Nice. I’ll check the full-quality view.", time: "14:31", own: true },
  ],
  family: [
    { id: 1, sender: "System", body: "Call ended · 24 minutes", time: "14:20", system: true },
    { id: 2, sender: "Noah", body: "I added the recording to the shared folder.", time: "14:22" },
  ],
  updates: [
    { id: 1, sender: "You", body: "Ready for review.", time: "Tuesday", own: true },
    { id: 2, sender: "Mina", body: "I’ll take a look this afternoon.", time: "Tuesday" },
  ],
});

const activeConversation = computed(
  () => conversations.find((conversation) => conversation.id === activeConversationId.value),
);
const activeMessages = computed(() => messages[activeConversationId.value]);

function selectFile(file) {
  selectedFileId.value = file.id;
  fileMenuOpen.value = false;
  feedback.value = "";
}

function runFileAction(action) {
  feedback.value = `${action}: ${selectedFile.value.name}`;
  fileMenuOpen.value = false;
}

function sendMessage() {
  const body = messageDraft.value.trim();
  if (!body) return;
  activeMessages.value.push({
    id: Date.now(),
    sender: "You",
    body,
    time: "Now",
    own: true,
  });
  messageDraft.value = "";
  feedback.value = "Message added to the local preview";
}

function startCall(kind) {
  feedback.value = `${kind} call preview started with ${activeConversation.value.name}`;
}
</script>

<template>
  <section class="product-frame native-preview" aria-label="Interactive native client preview">
    <div class="window-bar">
      <div class="window-brand">
        <span class="mini-mark">
          <img src="/cloud.svg" alt="" width="20" height="20" />
        </span>
        <span>
          <strong>Nextcloud Native</strong>
          <small>Desktop workspace</small>
        </span>
      </div>
      <span class="connection-state"><span></span> Synced just now</span>
    </div>

    <div class="native-workspace">
      <aside class="native-sidebar" aria-label="Preview applications">
        <p>Workspace</p>
        <button
          v-for="item in navigation"
          :key="item.id"
          type="button"
          :class="{ active: activeApp === item.id }"
          :disabled="item.disabled"
          @click="!item.disabled && (activeApp = item.id)"
        >
          <component :is="item.icon" :size="18" weight="duotone" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </button>
        <div class="preview-account">
          <img src="/obiente-avatar.png" alt="Obiente" width="29" height="29" />
          <div>
            <strong>Obiente</strong>
            <small>Connected to Nextcloud</small>
          </div>
        </div>
      </aside>

      <div class="native-content">
        <template v-if="activeApp === 'files'">
          <header class="screen-toolbar">
            <div>
              <small>Files / Projects</small>
              <h2>Project workspace</h2>
            </div>
            <div class="toolbar-actions">
              <button type="button" aria-label="Download selected file" @click="runFileAction('Download')">
                <PhDownloadSimple :size="18" weight="bold" />
              </button>
              <button type="button" aria-label="Share selected file" @click="runFileAction('Share')">
                <PhShareNetwork :size="18" weight="bold" />
              </button>
              <div class="overflow-wrap">
                <button
                  type="button"
                  aria-label="More actions for selected file"
                  :aria-expanded="fileMenuOpen"
                  @click="fileMenuOpen = !fileMenuOpen"
                >
                  <PhDotsThree :size="20" weight="bold" />
                </button>
                <div v-if="fileMenuOpen" class="overflow-menu">
                  <button type="button" @click="runFileAction('Rename')">Rename</button>
                  <button type="button" @click="runFileAction('Move')">Move to…</button>
                  <button type="button" class="danger" @click="runFileAction('Move to trash')">
                    Move to trash
                  </button>
                </div>
              </div>
            </div>
          </header>

          <div class="content-controls">
            <label>
              <PhMagnifyingGlass :size="16" weight="bold" aria-hidden="true" />
              <input v-model="fileQuery" type="search" placeholder="Search this folder" />
            </label>
            <div class="layout-toggle" aria-label="File layout">
              <button
                type="button"
                :class="{ active: fileLayout === 'list' }"
                aria-label="List view"
                @click="fileLayout = 'list'"
              >
                <PhListBullets :size="17" weight="bold" />
              </button>
              <button
                type="button"
                :class="{ active: fileLayout === 'grid' }"
                aria-label="Grid view"
                @click="fileLayout = 'grid'"
              >
                <PhGridFour :size="17" weight="bold" />
              </button>
            </div>
          </div>

          <div class="files-workspace">
            <div class="file-surface" :class="fileLayout">
              <button
                v-for="file in visibleFiles"
                :key="file.id"
                type="button"
                class="file-item"
                :class="{ selected: selectedFileId === file.id }"
                @click="selectFile(file)"
              >
                <span class="file-icon">
                  <component :is="file.icon" :size="21" weight="duotone" />
                </span>
                <span class="file-primary">
                  <strong>{{ file.name }}</strong>
                  <small>{{ file.kind }} · {{ file.meta }}</small>
                </span>
                <span class="file-modified">{{ file.modified }}</span>
                <PhCheck
                  v-if="selectedFileId === file.id"
                  class="selected-check"
                  :size="16"
                  weight="bold"
                />
              </button>
            </div>

            <aside class="file-inspector">
              <span class="inspector-preview">
                <component :is="selectedFile.icon" :size="36" weight="duotone" />
              </span>
              <strong>{{ selectedFile.name }}</strong>
              <small>{{ selectedFile.kind }} · {{ selectedFile.meta }}</small>
              <dl>
                <div><dt>Modified</dt><dd>{{ selectedFile.modified }}</dd></div>
                <div><dt>Available</dt><dd>Online + cached</dd></div>
                <div><dt>Shared</dt><dd>Project team</dd></div>
              </dl>
            </aside>
          </div>
        </template>

        <template v-else-if="activeApp === 'photos'">
          <header class="screen-toolbar">
            <div>
              <small>Photos / Memories</small>
              <h2>Your timeline</h2>
            </div>
            <button class="text-action" type="button" @click="feedback = 'Selection mode enabled'">
              Select
            </button>
          </header>

          <div class="photo-filters" role="tablist" aria-label="Photo views">
            <button
              v-for="view in ['timeline', 'albums', 'people']"
              :key="view"
              type="button"
              :class="{ active: photoView === view }"
              @click="photoView = view"
            >
              {{ view }}
            </button>
          </div>

          <div v-if="photoView === 'timeline'" class="photo-timeline">
            <div class="timeline-label">
              <strong>Today</strong>
              <small>3 items · originals available</small>
            </div>
            <div class="photo-grid">
              <button
                v-for="photo in photos"
                :key="photo.id"
                type="button"
                @click="selectedPhotoId = photo.id"
              >
                <img :src="photo.src" :alt="photo.alt" />
                <span>
                  <strong>{{ photo.title }}</strong>
                  <small>{{ photo.meta }}</small>
                </span>
              </button>
            </div>
          </div>

          <div v-else class="collection-preview">
            <span class="collection-icon">
              <PhImage v-if="photoView === 'albums'" :size="32" weight="duotone" />
              <PhCamera v-else :size="32" weight="duotone" />
            </span>
            <h3>{{ photoView === "albums" ? "Shared albums" : "Recognised people" }}</h3>
            <p>
              {{
                photoView === "albums"
                  ? "Group photos into local and shared collections."
                  : "Browse named people first, then review and merge suggestions."
              }}
            </p>
            <button type="button" @click="photoView = 'timeline'">Back to timeline</button>
          </div>

          <div v-if="selectedPhoto" class="photo-viewer">
            <button type="button" aria-label="Close photo" @click="selectedPhotoId = null">
              <PhX :size="18" weight="bold" />
            </button>
            <img :src="selectedPhoto.src" :alt="selectedPhoto.alt" />
            <div>
              <span>
                <strong>{{ selectedPhoto.title }}</strong>
                <small>{{ selectedPhoto.meta }} · Full quality ready</small>
              </span>
              <button type="button" @click="feedback = `Share: ${selectedPhoto.title}`">
                <PhShareNetwork :size="17" weight="bold" />
                Share
              </button>
            </div>
          </div>
        </template>

        <template v-else-if="activeApp === 'talk'">
          <div class="talk-workspace">
            <aside class="conversation-list">
              <div class="conversation-title">
                <span>
                  <small>Talk</small>
                  <strong>Conversations</strong>
                </span>
                <button type="button" aria-label="Search conversations">
                  <PhMagnifyingGlass :size="16" weight="bold" />
                </button>
              </div>
              <button
                v-for="conversation in conversations"
                :key="conversation.id"
                type="button"
                :class="{ active: activeConversationId === conversation.id }"
                @click="activeConversationId = conversation.id"
              >
                <span class="avatar">{{ conversation.name.slice(0, 1) }}</span>
                <span>
                  <strong>{{ conversation.name }}</strong>
                  <small>{{ conversation.preview }}</small>
                </span>
                <span class="conversation-meta">
                  <small>{{ conversation.time }}</small>
                  <b v-if="conversation.unread">{{ conversation.unread }}</b>
                </span>
              </button>
            </aside>

            <section class="chat-thread">
              <header>
                <span>
                  <strong>{{ activeConversation.name }}</strong>
                  <small>3 participants · active now</small>
                </span>
                <div>
                  <button type="button" aria-label="Start audio call" @click="startCall('Audio')">
                    <PhPhone :size="17" weight="bold" />
                  </button>
                  <button type="button" aria-label="Start video call" @click="startCall('Video')">
                    <PhVideoCamera :size="18" weight="bold" />
                  </button>
                </div>
              </header>

              <div class="message-list">
                <article
                  v-for="message in activeMessages"
                  :key="message.id"
                  :class="{ own: message.own, system: message.system }"
                >
                  <small v-if="!message.system">{{ message.sender }}</small>
                  <p>{{ message.body }}</p>
                  <button
                    v-if="message.attachment"
                    type="button"
                    class="message-attachment"
                    @click="activeApp = 'photos'"
                  >
                    <PhImage :size="18" weight="duotone" />
                    Open shared photos
                  </button>
                  <time>{{ message.time }}</time>
                </article>
              </div>

              <form class="message-composer" @submit.prevent="sendMessage">
                <input v-model="messageDraft" type="text" placeholder="Write a message" />
                <button type="submit" aria-label="Send message">
                  <PhPaperPlaneRight :size="18" weight="fill" />
                </button>
              </form>
            </section>
          </div>
        </template>

        <div v-if="feedback" class="preview-feedback" role="status">
          <PhCheck :size="15" weight="bold" />
          {{ feedback }}
          <button type="button" aria-label="Dismiss" @click="feedback = ''">
            <PhX :size="13" weight="bold" />
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.native-preview {
  position: relative;
  min-height: 600px;
}

.connection-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--muted);
  font-size: 10px;
  font-weight: 650;
}

.connection-state > span {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--success);
}

.native-workspace {
  min-height: 528px;
  display: grid;
  grid-template-columns: 148px minmax(0, 1fr);
}

.native-sidebar {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 18px 11px 12px;
  border-right: 1px solid var(--outline);
  background: #101217;
}

.native-sidebar > p {
  margin: 0 9px 7px;
  color: #777680;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.native-sidebar > button {
  min-height: 40px;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 11px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;
}

.native-sidebar > button:hover,
.native-sidebar > button:focus-visible {
  background: var(--surface);
  color: var(--text);
}

.native-sidebar > button.active {
  background: var(--primary-container);
  color: var(--primary);
}

.native-sidebar > button:disabled {
  cursor: default;
  opacity: 0.45;
}

.preview-account {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: auto;
  padding: 10px 7px 2px;
  border-top: 1px solid var(--outline);
}

.preview-account > img,
.avatar {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  border-radius: 999px;
  background: var(--primary-container);
  color: var(--primary);
  font-weight: 750;
}

.preview-account > img {
  width: 29px;
  height: 29px;
  object-fit: cover;
}

.preview-account > div {
  min-width: 0;
  display: grid;
}

.preview-account strong {
  overflow: hidden;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-account small {
  color: var(--success);
  font-size: 8px;
}

.native-content {
  position: relative;
  min-width: 0;
  min-height: 528px;
  overflow: hidden;
  background: var(--background);
}

.screen-toolbar {
  min-height: 74px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--outline);
}

.screen-toolbar > div:first-child {
  min-width: 0;
}

.screen-toolbar small {
  display: block;
  margin-bottom: 3px;
  color: var(--muted);
  font-size: 9px;
}

.screen-toolbar h2 {
  margin: 0;
  overflow: hidden;
  font-size: 18px;
  letter-spacing: -0.025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toolbar-actions {
  display: flex;
  gap: 5px;
}

.toolbar-actions > button,
.overflow-wrap > button,
.layout-toggle button,
.conversation-title button,
.chat-thread header button {
  width: 34px;
  height: 34px;
  display: inline-grid;
  place-items: center;
  border: 1px solid var(--outline);
  border-radius: 9px;
  background: var(--surface-low);
  color: var(--muted);
  cursor: pointer;
}

.toolbar-actions button:hover,
.toolbar-actions button:focus-visible,
.layout-toggle button:hover,
.layout-toggle button:focus-visible,
.conversation-title button:hover,
.conversation-title button:focus-visible,
.chat-thread header button:hover,
.chat-thread header button:focus-visible {
  border-color: var(--primary);
  color: var(--primary);
}

.overflow-wrap {
  position: relative;
}

.overflow-menu {
  position: absolute;
  z-index: 10;
  top: 39px;
  right: 0;
  width: 135px;
  display: grid;
  padding: 5px;
  border: 1px solid var(--outline-strong);
  border-radius: 10px;
  background: var(--surface-high);
  box-shadow: 0 14px 36px rgb(0 0 0 / 40%);
}

.overflow-menu button {
  min-height: 31px;
  padding: 0 9px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text);
  font-size: 10px;
  text-align: left;
  cursor: pointer;
}

.overflow-menu button:hover,
.overflow-menu button:focus-visible {
  background: var(--surface-bright);
}

.overflow-menu button.danger {
  color: #ffb4ab;
}

.content-controls {
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 9px 20px;
}

.content-controls label {
  min-width: 0;
  max-width: 260px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  padding: 0 11px;
  border: 1px solid var(--outline);
  border-radius: 9px;
  color: var(--muted);
}

.content-controls input {
  min-width: 0;
  height: 34px;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font-size: 10px;
}

.layout-toggle {
  display: flex;
  gap: 4px;
}

.layout-toggle button {
  width: 32px;
  height: 32px;
}

.layout-toggle button.active {
  border-color: transparent;
  background: var(--primary-container);
  color: var(--primary);
}

.files-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 164px;
  gap: 10px;
  padding: 0 20px 20px;
}

.file-surface {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 5px;
}

.file-item {
  min-width: 0;
  display: grid;
  grid-template-columns: 37px minmax(0, 1fr) auto 16px;
  align-items: center;
  gap: 10px;
  min-height: 54px;
  padding: 7px 9px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}

.file-item:hover,
.file-item:focus-visible {
  background: var(--surface-low);
}

.file-item.selected {
  border-color: #4a3d5b;
  background: var(--primary-container);
}

.file-icon {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: var(--surface-bright);
  color: var(--primary);
}

.file-primary {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.file-primary strong {
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-primary small,
.file-modified {
  color: var(--muted);
  font-size: 8px;
}

.file-modified {
  white-space: nowrap;
}

.selected-check {
  color: var(--primary);
}

.file-surface.grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.file-surface.grid .file-item {
  min-height: 110px;
  grid-template-columns: 1fr auto;
  grid-template-rows: auto auto;
  align-content: space-between;
}

.file-surface.grid .file-icon {
  width: 42px;
  height: 42px;
}

.file-surface.grid .file-primary {
  grid-column: 1 / -1;
}

.file-surface.grid .file-modified {
  display: none;
}

.file-inspector {
  min-width: 0;
  min-height: 300px;
  display: flex;
  align-items: center;
  flex-direction: column;
  padding: 17px 13px;
  border: 1px solid var(--outline);
  border-radius: 12px;
  background: var(--surface-low);
  text-align: center;
}

.inspector-preview {
  width: 72px;
  height: 84px;
  display: grid;
  place-items: center;
  margin-bottom: 13px;
  border-radius: 12px;
  background: var(--surface-bright);
  color: var(--primary);
}

.file-inspector > strong {
  max-width: 100%;
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-inspector > small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 8px;
}

.file-inspector dl {
  width: 100%;
  display: grid;
  gap: 8px;
  margin: 18px 0 0;
  padding-top: 14px;
  border-top: 1px solid var(--outline);
  text-align: left;
}

.file-inspector dl div {
  display: grid;
  gap: 2px;
}

.file-inspector dt {
  color: #777680;
  font-size: 7px;
  text-transform: uppercase;
}

.file-inspector dd {
  margin: 0;
  color: var(--muted);
  font-size: 8px;
}

.text-action,
.collection-preview button {
  min-height: 32px;
  padding: 0 13px;
  border: 1px solid var(--outline-strong);
  border-radius: 9px;
  background: var(--surface-low);
  color: var(--text);
  font-size: 10px;
  font-weight: 650;
  cursor: pointer;
}

.photo-filters {
  display: flex;
  gap: 5px;
  padding: 12px 20px 4px;
}

.photo-filters button {
  min-height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--muted);
  font-size: 9px;
  font-weight: 650;
  text-transform: capitalize;
  cursor: pointer;
}

.photo-filters button.active {
  background: var(--primary-container);
  color: var(--primary);
}

.photo-timeline {
  padding: 15px 20px 20px;
}

.timeline-label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.timeline-label strong {
  font-size: 12px;
}

.timeline-label small {
  color: var(--muted);
  font-size: 8px;
}

.photo-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
}

.photo-grid button {
  min-width: 0;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--outline);
  border-radius: 11px;
  background: var(--surface-low);
  color: var(--text);
  text-align: left;
  cursor: pointer;
}

.photo-grid button:hover,
.photo-grid button:focus-visible {
  border-color: var(--primary);
}

.photo-grid img {
  width: 100%;
  aspect-ratio: 1.25;
  object-fit: cover;
}

.photo-grid button > span {
  display: grid;
  gap: 2px;
  padding: 9px;
}

.photo-grid strong {
  overflow: hidden;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-grid small {
  color: var(--muted);
  font-size: 7px;
}

.collection-preview {
  max-width: 340px;
  display: grid;
  justify-items: center;
  margin: 60px auto 0;
  text-align: center;
}

.collection-icon {
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  background: var(--surface-bright);
  color: var(--primary);
}

.collection-preview h3 {
  margin: 16px 0 0;
  font-size: 16px;
}

.collection-preview p {
  margin: 8px 0 15px;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.55;
}

.photo-viewer {
  position: absolute;
  z-index: 8;
  inset: 0;
  display: grid;
  grid-template-rows: 1fr auto;
  padding: 16px;
  background: rgb(9 11 14 / 96%);
}

.photo-viewer > button:first-child {
  position: absolute;
  z-index: 1;
  top: 12px;
  right: 12px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid var(--outline-strong);
  border-radius: 10px;
  background: rgb(13 15 19 / 80%);
  color: var(--text);
  cursor: pointer;
}

.photo-viewer > img {
  width: 100%;
  min-height: 0;
  height: 100%;
  object-fit: contain;
}

.photo-viewer > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 4px 0;
}

.photo-viewer > div > span {
  display: grid;
  gap: 2px;
}

.photo-viewer strong {
  font-size: 11px;
}

.photo-viewer small {
  color: var(--muted);
  font-size: 8px;
}

.photo-viewer > div button {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  border: 1px solid var(--outline-strong);
  border-radius: 9px;
  background: var(--surface);
  color: var(--text);
  font-size: 9px;
  font-weight: 650;
  cursor: pointer;
}

.talk-workspace {
  min-height: 528px;
  display: grid;
  grid-template-columns: 174px minmax(0, 1fr);
}

.conversation-list {
  padding: 11px 8px;
  border-right: 1px solid var(--outline);
  background: var(--surface-low);
}

.conversation-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 5px 13px;
}

.conversation-title > span {
  display: grid;
  gap: 2px;
}

.conversation-title small,
.chat-thread header small {
  color: var(--muted);
  font-size: 8px;
}

.conversation-title strong,
.chat-thread header strong {
  font-size: 11px;
}

.conversation-title button {
  width: 30px;
  height: 30px;
}

.conversation-list > button {
  width: 100%;
  min-width: 0;
  min-height: 58px;
  display: grid;
  grid-template-columns: 31px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 7px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}

.conversation-list > button:hover,
.conversation-list > button:focus-visible {
  background: var(--surface);
}

.conversation-list > button.active {
  background: var(--primary-container);
}

.avatar {
  width: 31px;
  height: 31px;
  font-size: 10px;
}

.conversation-list > button > span:nth-child(2) {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.conversation-list > button strong,
.conversation-list > button small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-list > button strong {
  font-size: 9px;
}

.conversation-list > button small {
  color: var(--muted);
  font-size: 7px;
}

.conversation-meta {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.conversation-meta b {
  min-width: 16px;
  height: 16px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: var(--primary);
  color: var(--on-primary);
  font-size: 7px;
}

.chat-thread {
  min-width: 0;
  display: grid;
  grid-template-rows: auto 1fr auto;
}

.chat-thread header {
  min-height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--outline);
}

.chat-thread header > span {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.chat-thread header > div {
  display: flex;
  gap: 4px;
}

.chat-thread header button {
  width: 31px;
  height: 31px;
}

.message-list {
  min-height: 0;
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  padding: 15px;
}

.message-list article {
  max-width: 78%;
  padding: 8px 10px;
  border: 1px solid var(--outline);
  border-radius: 11px 11px 11px 3px;
  background: var(--surface);
}

.message-list article.own {
  align-self: flex-end;
  border-color: #4a3d5b;
  border-radius: 11px 11px 3px 11px;
  background: var(--primary-container);
}

.message-list article.system {
  max-width: none;
  align-self: center;
  border: 0;
  background: transparent;
  color: var(--muted);
}

.message-list article > small {
  display: block;
  margin-bottom: 3px;
  color: var(--primary);
  font-size: 7px;
  font-weight: 700;
}

.message-list p {
  margin: 0;
  font-size: 9px;
  line-height: 1.45;
}

.message-list time {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 6px;
  text-align: right;
}

.message-attachment {
  width: 100%;
  min-height: 34px;
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 7px;
  padding: 0 9px;
  border: 1px solid #564765;
  border-radius: 8px;
  background: var(--surface-high);
  color: var(--primary);
  font-size: 8px;
  font-weight: 650;
  cursor: pointer;
}

.message-composer {
  display: flex;
  gap: 7px;
  padding: 10px 13px 13px;
  border-top: 1px solid var(--outline);
}

.message-composer input {
  min-width: 0;
  height: 36px;
  flex: 1;
  padding: 0 12px;
  border: 1px solid var(--outline);
  border-radius: 10px;
  outline: 0;
  background: var(--surface-low);
  color: var(--text);
  font-size: 9px;
}

.message-composer input:focus {
  border-color: var(--primary);
}

.message-composer button {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 10px;
  background: var(--primary);
  color: var(--on-primary);
  cursor: pointer;
}

.preview-feedback {
  position: absolute;
  z-index: 14;
  right: 13px;
  bottom: 13px;
  max-width: calc(100% - 26px);
  min-height: 34px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 8px 0 11px;
  border: 1px solid #3c625d;
  border-radius: 10px;
  background: #152a27;
  color: #a5ece0;
  box-shadow: 0 12px 30px rgb(0 0 0 / 35%);
  font-size: 8px;
  font-weight: 650;
}

.preview-feedback button {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

@media (max-width: 760px) {
  .native-preview {
    min-height: 660px;
  }

  .native-workspace {
    min-height: 588px;
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }

  .native-sidebar {
    flex-direction: row;
    overflow-x: auto;
    padding: 8px;
    border-right: 0;
    border-bottom: 1px solid var(--outline);
  }

  .native-sidebar > p,
  .preview-account {
    display: none;
  }

  .native-sidebar > button {
    min-width: max-content;
    flex: 1;
    justify-content: center;
  }

  .native-content {
    min-height: 536px;
  }

  .file-inspector {
    display: none;
  }

  .files-workspace {
    grid-template-columns: 1fr;
  }

  .talk-workspace {
    min-height: 536px;
    grid-template-columns: 120px minmax(0, 1fr);
  }

  .conversation-list > button {
    grid-template-columns: 28px minmax(0, 1fr);
  }

  .conversation-meta {
    display: none;
  }
}

@media (max-width: 500px) {
  .connection-state {
    display: none;
  }

  .native-sidebar > button {
    gap: 0;
  }

  .native-sidebar > button span {
    display: none;
  }

  .screen-toolbar {
    padding-inline: 14px;
  }

  .content-controls,
  .files-workspace,
  .photo-timeline {
    padding-inline: 14px;
  }

  .file-modified {
    display: none;
  }

  .file-item {
    grid-template-columns: 37px minmax(0, 1fr) 16px;
  }

  .photo-grid {
    grid-template-columns: 1fr;
    max-height: 350px;
    overflow-y: auto;
  }

  .photo-grid img {
    aspect-ratio: 1.8;
  }

  .talk-workspace {
    grid-template-columns: 1fr;
  }

  .conversation-list {
    display: flex;
    gap: 5px;
    overflow-x: auto;
    padding: 8px;
    border-right: 0;
    border-bottom: 1px solid var(--outline);
  }

  .conversation-title {
    display: none;
  }

  .conversation-list > button {
    min-width: 130px;
    min-height: 45px;
  }

  .chat-thread {
    min-height: 450px;
  }
}
</style>
