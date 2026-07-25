<script setup>
import { computed, reactive, ref } from "vue";
import {
  PhAppWindow,
  PhArrowLeft,
  PhCalendarBlank,
  PhCaretDown,
  PhChartBar,
  PhChatCircleDots,
  PhCheckCircle,
  PhClock,
  PhCloudArrowDown,
  PhDotsThreeVertical,
  PhEnvelope,
  PhFilePdf,
  PhFileText,
  PhFolder,
  PhForkKnife,
  PhImage,
  PhMagnifyingGlass,
  PhMicrophone,
  PhMusicNotes,
  PhPause,
  PhPhone,
  PhPlay,
  PhPlus,
  PhShareNetwork,
  PhShieldCheck,
  PhSkipBack,
  PhSkipForward,
  PhSquaresFour,
  PhStar,
  PhTable,
  PhTag,
  PhUsers,
  PhVideoCamera,
} from "@phosphor-icons/vue";

const props = defineProps({
  app: {
    type: String,
    required: true,
  },
});

defineEmits(["open-switcher"]);

const query = ref("");
const selectedFile = ref(1);
const photoView = ref("Timeline");
const selectedPhoto = ref("forest");
const conversation = ref("Design team");
const mailbox = ref("Inbox");
const selectedMail = ref(1);
const selectedRecipe = ref(1);
const servings = ref(8);
const selectedTableRow = ref(2);
const analyticsOpen = ref(true);
const musicPlaying = ref(false);
const adminView = ref("Users");
const feedback = ref("");

const appMeta = {
  files: { title: "Files", subtitle: "Projects / Field work", icon: PhFolder },
  photos: { title: "Photos & Memories", subtitle: "Timeline", icon: PhImage },
  talk: { title: "Talk", subtitle: "Conversations", icon: PhChatCircleDots },
  mail: { title: "Mail", subtitle: "Obiente", icon: PhEnvelope },
  tables: { title: "Tables", subtitle: "Community inventory", icon: PhTable },
  deck: { title: "Deck", subtitle: "Launch board", icon: PhSquaresFour },
  cookbook: { title: "Cookbook", subtitle: "Recipes", icon: PhForkKnife },
  cospend: { title: "Cospend", subtitle: "Studio budget", icon: PhChartBar },
  music: { title: "Music", subtitle: "Library", icon: PhMusicNotes },
  calendar: { title: "Calendar", subtitle: "July 2026", icon: PhCalendarBlank },
  admin: { title: "Administration", subtitle: "Server overview", icon: PhShieldCheck },
};
const activeMeta = computed(() => appMeta[props.app] ?? appMeta.files);

const files = [
  { id: 1, name: "Project photos", kind: "Folder", size: "24 items", changed: "Now", icon: PhFolder },
  { id: 2, name: "Field notes.md", kind: "Markdown", size: "18 KB", changed: "12 min", icon: PhFileText },
  { id: 3, name: "Creative brief.pdf", kind: "PDF", size: "2.4 MB", changed: "Tue", icon: PhFilePdf },
];
const currentFile = computed(() => files.find((file) => file.id === selectedFile.value) ?? files[0]);

const photos = [
  { id: "forest", src: "/demo-media/forest-trail.webp", title: "Forest walk", meta: "RAW + JPEG" },
  { id: "shore", src: "/demo-media/north-sea.webp", title: "North Sea", meta: "Live Photo" },
  { id: "notes", src: "/demo-media/field-notes.webp", title: "Field notes", meta: "Favourite" },
];
const currentPhoto = computed(() => photos.find((photo) => photo.id === selectedPhoto.value) ?? photos[0]);

const conversations = [
  { name: "Design team", preview: "Mina shared 3 photos", unread: 2 },
  { name: "Family", preview: "Call ended · 24 min", unread: 0 },
  { name: "Project updates", preview: "Ready for review", unread: 0 },
];

const messages = [
  { sender: "Mina", body: "The new gallery flow is ready to review.", time: "14:28" },
  { sender: "Mina", body: "Shared 3 photos from Project photos", time: "14:29", attachment: true },
  { sender: "You", body: "Nice. I'll check the full-quality view.", time: "14:31", own: true },
];

const mails = [
  { id: 1, sender: "Mina", subject: "Gallery review", preview: "The updated flow is ready.", time: "10:42", unread: true },
  { id: 2, sender: "Workshop", subject: "Saturday schedule", preview: "Doors open at nine.", time: "Yesterday" },
  { id: 3, sender: "Nextcloud", subject: "Weekly activity", preview: "Your server summary.", time: "Mon" },
];
const currentMail = computed(() => mails.find((mail) => mail.id === selectedMail.value) ?? mails[0]);

const recipes = [
  { id: 1, name: "Ultimate meringue", time: "1 h 20 min", tags: "Dessert · 8 servings" },
  { id: 2, name: "Roasted tomato soup", time: "45 min", tags: "Dinner · 4 servings" },
  { id: 3, name: "Lemon pasta", time: "25 min", tags: "Quick · 2 servings" },
];
const currentRecipe = computed(() => recipes.find((recipe) => recipe.id === selectedRecipe.value) ?? recipes[0]);

const tableRows = [
  { id: 1, item: "Field recorder", category: "Audio", value: "€ 219.00", status: "Available", updated: "24 Jul" },
  { id: 2, item: "Tripod", category: "Camera", value: "€ 84.50", status: "On loan", updated: "23 Jul" },
  { id: 3, item: "USB-C hub", category: "Computer", value: "€ 49.95", status: "Available", updated: "22 Jul" },
  { id: 4, item: "Lighting kit", category: "Camera", value: "€ 135.00", status: "Reserved", updated: "21 Jul" },
  { id: 5, item: "Studio monitor", category: "Audio", value: "€ 175.00", status: "Available", updated: "19 Jul" },
];
const currentTableRow = computed(
  () => tableRows.find((row) => row.id === selectedTableRow.value) ?? tableRows[0],
);

const columns = reactive([
  { title: "Ideas", cards: ["Photo story", "Community interview"] },
  { title: "In progress", cards: ["Launch page", "Desktop navigation"] },
  { title: "Review", cards: ["Android release", "Recipe import"] },
]);

const transactions = [
  { title: "Studio rent", payer: "Obiente", amount: "€ 450.00", split: "3 people" },
  { title: "Workshop supplies", payer: "Mina", amount: "€ 86.40", split: "Everyone" },
  { title: "Travel", payer: "Noah", amount: "€ 42.00", split: "2 people" },
];

const albums = [
  { title: "Field recordings", artist: "Obiente", tracks: 8, artwork: "/demo-media/field-notes.webp" },
  { title: "Quiet mornings", artist: "Studio library", tracks: 11, artwork: "/demo-media/north-sea.webp" },
  { title: "Workshop mix", artist: "Community", tracks: 16, artwork: "/demo-media/forest-trail.webp" },
];

const monthDays = Array.from({ length: 35 }, (_, index) => {
  const day = index - 2;
  return day > 0 && day <= 31 ? day : null;
});

const users = [
  { name: "Obiente", role: "Administrator", quota: "18 GB of 100 GB", status: "Active" },
  { name: "Mina", role: "User", quota: "6 GB of 25 GB", status: "Active" },
  { name: "Noah", role: "User", quota: "2 GB of 25 GB", status: "Active" },
];

function notify(message) {
  feedback.value = message;
  window.setTimeout(() => {
    if (feedback.value === message) feedback.value = "";
  }, 1700);
}
</script>

<template>
  <section class="app-surface">
    <header class="app-toolbar">
      <button class="mobile-apps" type="button" aria-label="Choose an app" @click="$emit('open-switcher')">
        <PhSquaresFour :size="18" weight="duotone" aria-hidden="true" />
      </button>
      <div>
        <h2>{{ activeMeta.title }}</h2>
        <span><PhCheckCircle :size="14" weight="duotone" aria-hidden="true" /> Connected to Nextcloud · {{ activeMeta.subtitle }}</span>
      </div>
      <label>
        <PhMagnifyingGlass :size="16" weight="bold" aria-hidden="true" />
        <input
          v-model="query"
          type="search"
          :aria-label="`Search ${activeMeta.title}`"
          :placeholder="`Search ${activeMeta.title}`"
        />
      </label>
      <button type="button" aria-label="More app actions"><PhDotsThreeVertical :size="18" weight="bold" aria-hidden="true" /></button>
    </header>

    <div v-if="app === 'files'" class="files-layout">
      <aside class="mini-source-list">
        <strong>Files</strong>
        <button class="active"><PhFolder :size="16" weight="duotone" /> All files</button>
        <button><PhStar :size="16" weight="duotone" /> Favourites</button>
        <button><PhCloudArrowDown :size="16" weight="duotone" /> Offline</button>
        <span>Shared</span>
        <button><PhUsers :size="16" weight="duotone" /> With others</button>
      </aside>
      <div class="file-browser">
        <div class="file-breadcrumb"><PhArrowLeft :size="15" /> Projects / Field work <button><PhPlus :size="15" /> New</button></div>
        <div class="file-head"><span>Name</span><span>Size</span><span>Modified</span></div>
        <button
          v-for="file in files"
          :key="file.id"
          :class="{ selected: file.id === selectedFile }"
          @click="selectedFile = file.id"
        >
          <component :is="file.icon" :size="20" weight="duotone" />
          <span><strong>{{ file.name }}</strong><small>{{ file.kind }}</small></span>
          <span>{{ file.size }}</span>
          <span>{{ file.changed }}</span>
        </button>
      </div>
      <aside class="semantic-inspector">
        <span class="large-icon"><component :is="currentFile.icon" :size="34" weight="duotone" /></span>
        <h3>{{ currentFile.name }}</h3>
        <p>{{ currentFile.kind }} · {{ currentFile.size }}</p>
        <div class="inline-actions">
          <button @click="notify('Shared through the system share sheet')"><PhShareNetwork :size="16" /> Share</button>
          <button type="button" aria-label="More file actions"><PhDotsThreeVertical :size="16" aria-hidden="true" /></button>
        </div>
        <dl><div><dt>Available</dt><dd>Online + cached</dd></div><div><dt>Modified</dt><dd>{{ currentFile.changed }}</dd></div></dl>
      </aside>
    </div>

    <div v-else-if="app === 'photos'" class="photos-layout">
      <div class="photo-tabs">
        <button v-for="view in ['Timeline', 'Albums', 'People']" :key="view" :class="{ active: photoView === view }" @click="photoView = view">{{ view }}</button>
      </div>
      <template v-if="photoView === 'Timeline'">
        <div class="photo-day"><strong>Today</strong><small>3 items · originals available</small></div>
        <div class="photo-grid">
          <button v-for="photo in photos" :key="photo.id" :class="{ selected: photo.id === selectedPhoto }" @click="selectedPhoto = photo.id">
            <img :src="photo.src" :alt="photo.title" />
            <span><strong>{{ photo.title }}</strong><small>{{ photo.meta }}</small></span>
          </button>
        </div>
        <aside class="photo-inspector">
          <img :src="currentPhoto.src" :alt="currentPhoto.title" />
          <h3>{{ currentPhoto.title }}</h3><p>{{ currentPhoto.meta }} · Full quality</p>
          <div class="inline-actions"><button><PhShareNetwork :size="16" aria-hidden="true" /> Share</button><button type="button" aria-label="More photo actions"><PhDotsThreeVertical :size="16" aria-hidden="true" /></button></div>
        </aside>
      </template>
      <div v-else class="collection-cards">
        <article v-for="item in photoView === 'Albums' ? ['Field work', 'Shared workshop', 'Favourites'] : ['Mina', 'Noah', 'Review suggestions']" :key="item">
          <span><component :is="photoView === 'Albums' ? PhImage : PhUsers" :size="25" weight="duotone" /></span>
          <strong>{{ item }}</strong><small>{{ photoView === 'Albums' ? '12 photos' : 'Named photos' }}</small>
        </article>
      </div>
    </div>

    <div v-else-if="app === 'talk'" class="talk-layout">
      <aside class="conversation-list">
        <strong>Conversations</strong>
        <button v-for="item in conversations" :key="item.name" :class="{ active: conversation === item.name }" @click="conversation = item.name">
          <span class="avatar">{{ item.name.slice(0, 1) }}</span>
          <span><strong>{{ item.name }}</strong><small>{{ item.preview }}</small></span>
          <b v-if="item.unread">{{ item.unread }}</b>
        </button>
      </aside>
      <section class="thread">
        <header><div><strong>{{ conversation }}</strong><small>3 participants · active</small></div><button type="button" aria-label="Start audio call"><PhPhone :size="17" aria-hidden="true" /></button><button type="button" aria-label="Start video call"><PhVideoCamera :size="17" aria-hidden="true" /></button></header>
        <div class="message-list">
          <article v-for="message in messages" :key="message.time" :class="{ own: message.own }">
            <small>{{ message.sender }}</small><p>{{ message.body }}</p>
            <button v-if="message.attachment" class="attachment"><PhImage :size="17" /> Project photos · 3 items</button>
            <time>{{ message.time }}</time>
          </article>
          <article class="call-card"><PhPhone :size="20" weight="duotone" /><span><strong>Group call ended</strong><small>24 minutes · 3 participants</small></span></article>
        </div>
        <form @submit.prevent="notify('Message sent in the preview')"><input aria-label="Message" placeholder="Write a message" /><button type="submit" aria-label="Send message"><PhMicrophone :size="17" aria-hidden="true" /></button></form>
      </section>
    </div>

    <div v-else-if="app === 'mail'" class="mail-layout">
      <aside class="mail-folders"><button class="compose"><PhPlus :size="15" /> New message</button><strong>Mailboxes</strong><button :class="{ active: mailbox === 'Inbox' }" @click="mailbox = 'Inbox'">Inbox <b>3</b></button><button>Starred</button><button>Sent</button><button>Drafts</button><button>Archive</button></aside>
      <section class="mail-list"><header><strong>{{ mailbox }}</strong><small>{{ mails.length }} messages</small></header><button v-for="mail in mails" :key="mail.id" :class="{ active: selectedMail === mail.id, unread: mail.unread }" @click="selectedMail = mail.id"><span><strong>{{ mail.sender }}</strong><time>{{ mail.time }}</time></span><b>{{ mail.subject }}</b><small>{{ mail.preview }}</small></button></section>
      <article class="mail-message"><header><h3>{{ currentMail.subject }}</h3><button type="button" aria-label="More message actions"><PhDotsThreeVertical :size="17" aria-hidden="true" /></button><p>From {{ currentMail.sender }} · to Obiente</p></header><div class="mail-body"><p>Hello Obiente,</p><p>The updated gallery flow is ready. The layout now keeps actions contextual and gives full-quality media room to breathe.</p><p>Could you review it before the workshop?</p><p>Thanks,<br />{{ currentMail.sender }}</p></div><footer><button @click="notify('Reply editor opened')">Reply</button><button>Forward</button></footer></article>
    </div>

    <div v-else-if="app === 'tables'" class="tables-layout">
      <aside class="table-facets">
        <strong>Community inventory</strong>
        <button class="active"><PhTable :size="16" weight="duotone" /> All items <b>5</b></button>
        <span>Status</span>
        <button>Available <b>3</b></button>
        <button>On loan <b>1</b></button>
        <button>Reserved <b>1</b></button>
        <span>Categories</span>
        <button>Audio <b>2</b></button>
        <button>Camera <b>2</b></button>
        <button>Computer <b>1</b></button>
      </aside>
      <section class="semantic-table">
        <header>
          <div><strong>Community inventory</strong><small>5 records · 5 typed columns</small></div>
          <button><PhPlus :size="15" /> New item</button>
        </header>
        <div class="semantic-table-head">
          <span>Item</span><span>Category</span><span>Value</span><span>Status</span><span>Updated</span>
        </div>
        <button
          v-for="row in tableRows"
          :key="row.id"
          :class="{ selected: selectedTableRow === row.id }"
          @click="selectedTableRow = row.id"
        >
          <strong>{{ row.item }}</strong>
          <span>{{ row.category }}</span>
          <span>{{ row.value }}</span>
          <span class="record-state" :class="row.status.toLowerCase().replace(' ', '-')">{{ row.status }}</span>
          <time>{{ row.updated }}</time>
        </button>
      </section>
      <aside class="table-inspector">
        <header><h3>{{ currentTableRow.item }}</h3><button aria-label="More record actions"><PhDotsThreeVertical :size="17" /></button></header>
        <p>Selected record</p>
        <dl>
          <div><dt>Category</dt><dd>{{ currentTableRow.category }}</dd></div>
          <div><dt>Value</dt><dd>{{ currentTableRow.value }}</dd></div>
          <div><dt>Status</dt><dd>{{ currentTableRow.status }}</dd></div>
          <div><dt>Updated</dt><dd>{{ currentTableRow.updated }}</dd></div>
        </dl>
        <button class="edit-record" @click="notify('Typed record editor opened')">Edit record</button>
      </aside>
    </div>

    <div v-else-if="app === 'deck'" class="deck-layout">
      <div class="board-toolbar"><span><strong>Launch board</strong><small>8 cards · 3 lists</small></span><button><PhPlus :size="15" /> Add card</button></div>
      <div class="board-columns">
        <section v-for="column in columns" :key="column.title"><header><strong>{{ column.title }}</strong><span>{{ column.cards.length }}</span></header><article v-for="card in column.cards" :key="card"><span class="card-label"></span><strong>{{ card }}</strong><small><PhClock :size="13" /> This week · Obiente</small></article><button><PhPlus :size="14" /> Add a card</button></section>
      </div>
    </div>

    <div v-else-if="app === 'cookbook'" class="cookbook-layout">
      <aside class="recipe-list"><header><strong>Recipes</strong><button @click="notify('Recipe URL importer opened')">Import URL</button></header><button v-for="recipe in recipes" :key="recipe.id" :class="{ active: selectedRecipe === recipe.id }" @click="selectedRecipe = recipe.id"><span><PhForkKnife :size="20" weight="duotone" /></span><span><strong>{{ recipe.name }}</strong><small>{{ recipe.time }}</small></span></button></aside>
      <article class="recipe-detail"><p class="kicker">Dessert · Community cookbook</p><h3>{{ currentRecipe.name }}</h3><p>{{ currentRecipe.tags }}</p><div class="servings"><button aria-label="Decrease servings" @click="servings = Math.max(1, servings - 1)">-</button><span><strong>{{ servings }}</strong><small>servings</small></span><button aria-label="Increase servings" @click="servings += 1">+</button></div><section><h4>Ingredients</h4><ul><li>{{ servings / 2 }} egg whites</li><li>{{ Math.round(servings * 27.5) }} g caster sugar</li><li>{{ (servings / 8).toFixed(1) }} tsp vanilla</li><li>Fresh berries to serve</li></ul></section><section><h4>Method</h4><ol><li>Whisk the egg whites until firm.</li><li>Add sugar gradually and fold gently.</li><li>Bake until crisp outside and soft inside.</li></ol></section></article>
    </div>

    <div v-else-if="app === 'cospend'" class="cospend-layout">
      <header class="budget-summary"><div><small>Your balance</small><strong>€ 34.20 owed to you</strong></div><button><PhPlus :size="15" /> Add expense</button></header>
      <details :open="analyticsOpen" @toggle="analyticsOpen = $event.target.open"><summary><span><PhChartBar :size="18" weight="duotone" /><strong>Spending overview</strong><small>€ 578.40 this month</small></span><PhCaretDown :size="17" /></summary><div class="budget-metrics"><article><small>Shared</small><strong>€ 402.00</strong></article><article><small>Your share</small><strong>€ 176.40</strong></article><article><small>Largest category</small><strong>Studio</strong></article></div></details>
      <section class="transaction-list"><header><strong>Recent transactions</strong><button>Filter</button></header><article v-for="item in transactions" :key="item.title"><span><PhTag :size="18" weight="duotone" /></span><div><strong>{{ item.title }}</strong><small>Paid by {{ item.payer }} · {{ item.split }}</small></div><b>{{ item.amount }}</b></article></section>
    </div>

    <div v-else-if="app === 'music'" class="music-layout">
      <nav><button class="active">Albums</button><button>Artists</button><button>Playlists</button><button>Songs</button></nav>
      <div class="album-grid"><article v-for="album in albums" :key="album.title"><img :src="album.artwork" :alt="`${album.title} album artwork`" /><strong>{{ album.title }}</strong><small>{{ album.artist }} · {{ album.tracks }} tracks</small><button type="button" :aria-label="`Play ${album.title}`" @click="musicPlaying = true"><PhPlay :size="15" weight="fill" aria-hidden="true" /></button></article></div>
      <footer class="music-player"><img src="/demo-media/field-notes.webp" alt="" /><div><strong>Rain on glass</strong><small>Field recordings</small></div><button type="button" aria-label="Previous track"><PhSkipBack :size="16" weight="fill" aria-hidden="true" /></button><button class="play" type="button" :aria-label="musicPlaying ? 'Pause Rain on glass' : 'Play Rain on glass'" @click="musicPlaying = !musicPlaying"><component :is="musicPlaying ? PhPause : PhPlay" :size="17" weight="fill" aria-hidden="true" /></button><button type="button" aria-label="Next track"><PhSkipForward :size="16" weight="fill" aria-hidden="true" /></button><time>02:18 / 04:36</time></footer>
    </div>

    <div v-else-if="app === 'calendar'" class="calendar-layout">
      <aside><button class="new-event"><PhPlus :size="15" /> New event</button><strong>Calendars</strong><label><input checked type="checkbox" /> Personal</label><label><input checked type="checkbox" /> Obiente</label><label><input type="checkbox" /> Birthdays</label></aside>
      <section class="calendar-month"><header><button type="button" aria-label="Previous month">‹</button><h3>July 2026</h3><button type="button" aria-label="Next month">›</button><button type="button">Today</button></header><div class="weekdays"><span v-for="day in ['Mon','Tue','Wed','Thu','Fri','Sat','Sun']" :key="day">{{ day }}</span></div><div class="month-grid"><button v-for="(day, index) in monthDays" :key="index" type="button" :disabled="!day" :aria-label="day ? `July ${day}, 2026${day === 25 ? ', Workshop' : day === 28 ? ', Review call' : ''}` : 'Outside July 2026'" :class="{ muted: !day, today: day === 25 }"><span>{{ day }}</span><b v-if="day === 25">Workshop</b><b v-if="day === 28">Review call</b></button></div></section>
      <aside class="event-inspector"><p class="kicker">Friday 25 July</p><h3>Community workshop</h3><p>09:30-12:30 · Studio</p><dl><div><dt>Calendar</dt><dd>Obiente</dd></div><div><dt>Guests</dt><dd>4 attending</dd></div><div><dt>Reminder</dt><dd>30 minutes before</dd></div></dl><button>Edit event</button></aside>
    </div>

    <div v-else-if="app === 'admin'" class="admin-layout">
      <aside><strong>Administration</strong><button v-for="item in ['Overview','Users','Apps','Security','Sharing','Background jobs']" :key="item" :class="{ active: adminView === item }" @click="adminView = item">{{ item }}</button></aside>
      <section><header><div><h3>{{ adminView }}</h3><p>Manage your Nextcloud server from the native workspace.</p></div><button><PhPlus :size="15" /> {{ adminView === 'Users' ? 'New user' : 'Add' }}</button></header><div v-if="adminView === 'Users'" class="user-table"><div class="user-head"><span>User</span><span>Role</span><span>Storage</span><span>Status</span></div><article v-for="user in users" :key="user.name"><span class="avatar"><img v-if="user.name === 'Obiente'" src="/obiente-avatar.png" alt="" /><template v-else>{{ user.name.slice(0, 1) }}</template></span><strong>{{ user.name }}</strong><span>{{ user.role }}</span><span>{{ user.quota }}</span><span class="online">{{ user.status }}</span><button type="button" :aria-label="`More actions for ${user.name}`"><PhDotsThreeVertical :size="16" aria-hidden="true" /></button></article></div><div v-else class="admin-cards"><article><PhShieldCheck :size="24" weight="duotone" /><strong>Security check</strong><span>All critical checks pass</span></article><article><PhAppWindow :size="24" weight="duotone" /><strong>Installed apps</strong><span>42 enabled · 3 updates</span></article><article><PhClock :size="24" weight="duotone" /><strong>Background jobs</strong><span>Last run 4 minutes ago</span></article></div></section>
    </div>

    <div v-if="feedback" class="app-feedback" role="status">{{ feedback }}</div>
  </section>
</template>

<style scoped>
.app-surface { min-width:0; min-height:640px; display:flex; flex-direction:column; background:#0d1015; }
button,input { font:inherit; color:inherit; }
button { cursor:pointer; }
.app-toolbar { min-height:79px; display:grid; grid-template-columns:minmax(0,1fr) 180px auto; align-items:center; gap:8px; padding:13px 16px; border-bottom:1px solid #292c33; }
.app-toolbar h2 { margin:0; font-size:19px; letter-spacing:-.035em; }
.app-toolbar > div span { display:flex; align-items:center; gap:5px; margin-top:4px; color:#aaa8b1; font-size:8px; }
.app-toolbar > div span svg { color:#5de0c0; }
.app-toolbar label { height:35px; display:flex; align-items:center; gap:7px; padding:0 10px; border:1px solid #343740; border-radius:8px; color:#aaa8b1; }
.app-toolbar input { width:100%; min-width:0; border:0; outline:0; background:transparent; font-size:9px; }
.app-toolbar > button { width:35px; height:35px; display:grid; place-items:center; border:1px solid #343740; border-radius:8px; background:#11141a; }
.mobile-apps { display:none !important; }
.mini-source-list,.mail-folders,.calendar-layout > aside:first-child,.admin-layout > aside { display:flex; flex-direction:column; gap:4px; padding:17px 11px; border-right:1px solid #292c33; background:#101319; }
.mini-source-list > strong,.mail-folders > strong,.calendar-layout > aside strong,.admin-layout > aside strong { margin:0 8px 8px; color:#aaa8b1; font-size:8px; text-transform:uppercase; letter-spacing:.08em; }
.mini-source-list button,.mail-folders button,.admin-layout > aside button { min-height:34px; display:flex; align-items:center; gap:8px; padding:0 9px; border:0; border-radius:7px; background:transparent; color:#d4d1d8; font-size:9px; text-align:left; }
.mini-source-list button.active,.mail-folders button.active,.admin-layout > aside button.active { background:#30283d; color:#dcc8ff; }
.mini-source-list > span { margin:14px 8px 4px; color:#777680; font-size:8px; text-transform:uppercase; }
.files-layout { flex:1; display:grid; grid-template-columns:130px minmax(250px,1fr) 180px; }
.file-browser { min-width:0; padding:15px; border-right:1px solid #292c33; }
.file-breadcrumb { min-height:38px; display:flex; align-items:center; gap:7px; color:#aaa8b1; font-size:9px; }
.file-breadcrumb button,.board-toolbar button,.recipe-list header button,.budget-summary button,.transaction-list header button,.admin-layout section > header button { display:inline-flex; align-items:center; gap:5px; margin-left:auto; padding:7px 10px; border:0; border-radius:7px; background:#8d64dc; color:#fff; font-size:8px; font-weight:700; }
.file-head { min-height:32px; display:grid; grid-template-columns:minmax(0,1fr) 65px 65px; align-items:center; padding:0 8px 0 36px; border-bottom:1px solid #292c33; color:#8c8a94; font-size:8px; }
.file-browser > button { width:100%; min-height:57px; display:grid; grid-template-columns:24px minmax(0,1fr) 65px 65px; align-items:center; gap:8px; padding:0 8px; border:1px solid transparent; border-bottom-color:#292c33; background:transparent; font-size:9px; text-align:left; }
.file-browser > button.selected { border-color:#8f72c5; border-radius:7px; background:#211c2a; }
.file-browser > button > span:nth-child(2) { min-width:0; display:grid; }
.file-browser strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.file-browser small { margin-top:2px; color:#8c8a94; font-size:8px; }
.semantic-inspector,.photo-inspector,.event-inspector { padding:18px 14px; }
.large-icon { width:52px; height:52px; display:grid; place-items:center; border-radius:11px; background:#25232e; color:#cbb3fd; }
.semantic-inspector h3,.photo-inspector h3 { margin:13px 0 0; font-size:12px; }
.semantic-inspector p,.photo-inspector p,.event-inspector p { margin:5px 0 14px; color:#aaa8b1; font-size:8px; line-height:1.5; }
.inline-actions { display:flex; gap:5px; }
.inline-actions button { min-height:31px; display:flex; align-items:center; gap:5px; padding:0 9px; border:0; border-radius:7px; background:#2d263a; color:#d1b7ff; font-size:8px; }
.semantic-inspector dl,.event-inspector dl { margin-top:18px; }
.semantic-inspector dl div,.event-inspector dl div { padding:10px 0; border-top:1px solid #292c33; }
dt { color:#8c8a94; font-size:8px; } dd { margin:5px 0 0; font-size:9px; }
.photos-layout { position:relative; flex:1; padding:16px 198px 24px 16px; }
.photo-tabs { display:flex; gap:4px; margin-bottom:18px; }
.photo-tabs button,.music-layout nav button { padding:7px 11px; border:0; border-radius:999px; background:transparent; color:#aaa8b1; font-size:9px; }
.photo-tabs button.active,.music-layout nav button.active { background:#30283d; color:#dcc8ff; }
.photo-day { display:flex; align-items:end; justify-content:space-between; margin-bottom:9px; }
.photo-day strong { font-size:11px; }.photo-day small { color:#8c8a94; font-size:8px; }
.photo-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
.photo-grid button { position:relative; aspect-ratio:1.25; overflow:hidden; padding:0; border:2px solid transparent; border-radius:10px; background:#171a20; }
.photo-grid button.selected { border-color:#a785e5; }
.photo-grid img { width:100%; height:100%; object-fit:cover; }
.photo-grid button > span { position:absolute; right:0; bottom:0; left:0; display:grid; padding:24px 9px 8px; background:linear-gradient(transparent,rgb(0 0 0 / 80%)); text-align:left; }
.photo-grid strong { font-size:9px; }.photo-grid small { color:#ddd8e3; font-size:7px; }
.photo-inspector { position:absolute; top:0; right:0; bottom:0; width:182px; border-left:1px solid #292c33; }
.photo-inspector img { width:100%; aspect-ratio:1; object-fit:cover; border-radius:10px; }
.collection-cards,.album-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:9px; }
.collection-cards article,.album-grid article { display:grid; gap:7px; padding:16px; border:1px solid #292c33; border-radius:10px; background:#14171d; }
.collection-cards article > span { width:48px; height:48px; display:grid; place-items:center; border-radius:9px; background:#25232e; color:#cbb3fd; }
.collection-cards strong,.album-grid strong { font-size:10px; }.collection-cards small,.album-grid small { color:#8c8a94; font-size:8px; }
.talk-layout { flex:1; display:grid; grid-template-columns:190px minmax(0,1fr); }
.conversation-list { padding:14px 9px; border-right:1px solid #292c33; background:#101319; }
.conversation-list > strong { display:block; margin:4px 8px 12px; font-size:11px; }
.conversation-list > button { width:100%; min-height:57px; display:grid; grid-template-columns:32px minmax(0,1fr) auto; align-items:center; gap:8px; padding:7px; border:0; border-radius:8px; background:transparent; text-align:left; }
.conversation-list > button.active { background:#30283d; }
.avatar { width:30px; height:30px; display:grid; place-items:center; border-radius:8px; background:#27212f; color:#d8c0ff; font-size:10px; font-weight:700; }
.conversation-list button > span:nth-child(2) { min-width:0; display:grid; }
.conversation-list strong { font-size:9px; }.conversation-list small { overflow:hidden; color:#8c8a94; font-size:7px; text-overflow:ellipsis; white-space:nowrap; }.conversation-list b { width:16px; height:16px; display:grid; place-items:center; border-radius:999px; background:#8d64dc; font-size:7px; }
.thread { min-width:0; display:flex; flex-direction:column; }
.thread > header { min-height:53px; display:grid; grid-template-columns:minmax(0,1fr) auto auto; align-items:center; gap:5px; padding:0 13px; border-bottom:1px solid #292c33; }
.thread > header div { display:grid; }.thread > header strong { font-size:10px; }.thread > header small { color:#8c8a94; font-size:7px; }
.thread > header button { width:30px; height:30px; display:grid; place-items:center; border:1px solid #343740; border-radius:7px; background:#15181e; }
.message-list { flex:1; display:flex; flex-direction:column; gap:8px; padding:14px; overflow:auto; }
.message-list article { max-width:72%; align-self:flex-start; padding:9px 10px; border-radius:4px 10px 10px; background:#1a1d23; }
.message-list article.own { align-self:flex-end; border-radius:10px 4px 10px 10px; background:#30283d; }
.message-list article small,.message-list time { color:#9b98a3; font-size:7px; }.message-list p { margin:4px 0; font-size:9px; line-height:1.45; }
.attachment { display:flex; align-items:center; gap:6px; margin-top:7px; padding:7px; border:1px solid #3b3e47; border-radius:7px; background:#11141a; font-size:8px; }
.message-list article.call-card { max-width:100%; display:flex; align-items:center; gap:9px; align-self:stretch; border:1px solid #343740; border-radius:9px; background:#11141a; }
.call-card span { display:grid; }.call-card strong { font-size:9px; }
.thread form { display:grid; grid-template-columns:1fr auto; gap:6px; padding:10px 13px; border-top:1px solid #292c33; }
.thread form input { min-width:0; height:34px; padding:0 11px; border:1px solid #343740; border-radius:8px; background:#15181e; font-size:9px; }
.thread form button { width:34px; border:0; border-radius:8px; background:#8d64dc; }
.mail-layout { flex:1; display:grid; grid-template-columns:125px 180px minmax(0,1fr); }
.mail-folders .compose { margin-bottom:12px; justify-content:center; background:#8d64dc; color:#fff; }.mail-folders button b { margin-left:auto; }
.mail-list { border-right:1px solid #292c33; }
.mail-list header { display:grid; padding:14px 12px 10px; border-bottom:1px solid #292c33; }.mail-list header strong { font-size:11px; }.mail-list header small { color:#8c8a94; font-size:8px; }
.mail-list > button { width:100%; min-height:73px; display:grid; gap:3px; padding:9px 11px; border:0; border-bottom:1px solid #292c33; background:transparent; text-align:left; }
.mail-list > button.active { background:#211c2a; }.mail-list > button.unread b { color:#fff; }
.mail-list > button > span { display:flex; justify-content:space-between; gap:6px; }.mail-list strong,.mail-list b { overflow:hidden; font-size:8px; text-overflow:ellipsis; white-space:nowrap; }.mail-list time,.mail-list small { color:#8c8a94; font-size:7px; }
.mail-message { min-width:0; padding:18px; }
.mail-message header { border-bottom:1px solid #292c33; }.mail-message h3 { display:inline; margin:0; font-size:16px; }.mail-message header button { float:right; border:0; background:transparent; }.mail-message header p { color:#8c8a94; font-size:8px; }
.mail-body { max-width:520px; padding:16px 0; font-size:10px; line-height:1.65; }.mail-message footer { display:flex; gap:6px; }.mail-message footer button { padding:7px 12px; border:1px solid #3b3e47; border-radius:7px; background:#171a20; font-size:8px; }
.tables-layout { flex:1; display:grid; grid-template-columns:138px minmax(330px,1fr) 172px; }
.table-facets { display:flex; flex-direction:column; gap:3px; padding:16px 9px; border-right:1px solid #292c33; background:#101319; }
.table-facets > strong { margin:2px 8px 10px; font-size:9px; line-height:1.35; }
.table-facets > span { margin:14px 8px 4px; color:#777680; font-size:7px; font-weight:700; letter-spacing:.07em; text-transform:uppercase; }
.table-facets button { min-height:31px; display:flex; align-items:center; gap:7px; padding:0 8px; border:0; border-radius:6px; background:transparent; color:#aaa8b1; font-size:8px; text-align:left; }
.table-facets button.active { background:#30283d; color:#dcc8ff; }
.table-facets button b { margin-left:auto; color:#8c8a94; font-size:7px; }
.semantic-table { min-width:0; padding:13px; border-right:1px solid #292c33; }
.semantic-table > header { min-height:48px; display:flex; align-items:center; gap:10px; }
.semantic-table > header div { display:grid; }
.semantic-table > header strong { font-size:11px; }
.semantic-table > header small { margin-top:3px; color:#8c8a94; font-size:7px; }
.semantic-table > header button,.edit-record { display:inline-flex; align-items:center; gap:5px; margin-left:auto; padding:7px 10px; border:0; border-radius:7px; background:#8d64dc; color:#fff; font-size:8px; font-weight:700; }
.semantic-table-head,.semantic-table > button { display:grid; grid-template-columns:minmax(92px,1.2fr) minmax(62px,.8fr) 65px 70px 54px; align-items:center; gap:7px; padding:0 8px; }
.semantic-table-head { min-height:29px; border-bottom:1px solid #292c33; color:#777680; font-size:7px; }
.semantic-table > button { width:100%; min-height:52px; border:1px solid transparent; border-bottom-color:#292c33; background:transparent; font-size:8px; text-align:left; }
.semantic-table > button.selected { border-color:#8f72c5; border-radius:7px; background:#211c2a; }
.semantic-table > button strong { overflow:hidden; font-size:8px; text-overflow:ellipsis; white-space:nowrap; }
.semantic-table time { color:#8c8a94; font-size:7px; }
.record-state { width:max-content; padding:4px 6px; border-radius:999px; background:#15372f; color:#72dfc6; font-size:7px; }
.record-state.on-loan { background:#3a3019; color:#f1c866; }
.record-state.reserved { background:#232c42; color:#8bbdff; }
.table-inspector { padding:18px 14px; }
.table-inspector header { display:flex; align-items:center; gap:8px; }
.table-inspector h3 { margin:0; font-size:13px; }
.table-inspector header button { margin-left:auto; border:0; background:transparent; }
.table-inspector > p { margin:5px 0 16px; color:#8c8a94; font-size:8px; }
.table-inspector dl { margin:0; }
.table-inspector dl div { padding:10px 0; border-top:1px solid #292c33; }
.table-inspector .edit-record { width:100%; justify-content:center; margin:14px 0 0; background:#30283d; color:#dcc8ff; }
.deck-layout { flex:1; padding:14px; overflow:auto; }.board-toolbar { display:flex; align-items:center; margin-bottom:12px; }.board-toolbar > span { display:grid; }.board-toolbar strong { font-size:11px; }.board-toolbar small { color:#8c8a94; font-size:8px; }
.board-columns { display:grid; grid-template-columns:repeat(3,minmax(150px,1fr)); gap:9px; }.board-columns section { min-height:430px; padding:10px; border:1px solid #292c33; border-radius:10px; background:#11141a; }
.board-columns section > header { display:flex; justify-content:space-between; padding:4px 2px 11px; }.board-columns header strong { font-size:9px; }.board-columns header span { color:#8c8a94; font-size:8px; }
.board-columns article { display:grid; gap:8px; margin-bottom:8px; padding:11px; border:1px solid #31343c; border-radius:8px; background:#191c22; }.card-label { width:32px; height:4px; border-radius:4px; background:#9b7bd7; }.board-columns article strong { font-size:9px; }.board-columns article small { display:flex; align-items:center; gap:5px; color:#8c8a94; font-size:7px; }.board-columns section > button { border:0; background:transparent; color:#bba3e8; font-size:8px; }
.cookbook-layout { flex:1; display:grid; grid-template-columns:210px minmax(0,1fr); }.recipe-list { padding:13px 9px; border-right:1px solid #292c33; }.recipe-list header { display:flex; align-items:center; padding:2px 4px 12px; }.recipe-list header strong { font-size:11px; }.recipe-list > button { width:100%; min-height:60px; display:grid; grid-template-columns:35px minmax(0,1fr); align-items:center; gap:8px; padding:7px; border:0; border-radius:8px; background:transparent; text-align:left; }.recipe-list > button.active { background:#30283d; }.recipe-list > button > span:first-child { width:34px; height:34px; display:grid; place-items:center; border-radius:8px; background:#25232e; color:#cbb3fd; }.recipe-list button span:last-child { display:grid; }.recipe-list strong { font-size:9px; }.recipe-list small { color:#8c8a94; font-size:7px; }
.recipe-detail { max-width:590px; padding:25px 28px; }.kicker { color:#cbb3fd !important; font-size:8px !important; font-weight:700; letter-spacing:.08em; text-transform:uppercase; }.recipe-detail h3 { margin:8px 0 0; font-size:21px; }.recipe-detail > p { color:#8c8a94; font-size:9px; }.servings { width:max-content; display:flex; align-items:center; gap:12px; margin:20px 0; padding:7px; border:1px solid #343740; border-radius:9px; }.servings button { width:27px; height:27px; border:0; border-radius:6px; background:#30283d; }.servings span { display:grid; min-width:48px; text-align:center; }.servings strong { font-size:11px; }.servings small { color:#8c8a94; font-size:7px; }.recipe-detail section { margin-top:18px; }.recipe-detail h4 { margin:0 0 8px; font-size:11px; }.recipe-detail ul,.recipe-detail ol { margin:0; padding-left:18px; color:#d5d2d9; font-size:9px; line-height:1.7; }
.cospend-layout { flex:1; padding:16px; overflow:auto; }.budget-summary { display:flex; align-items:center; padding:14px; border:1px solid #343740; border-radius:10px; background:#15181e; }.budget-summary div { display:grid; }.budget-summary small { color:#8c8a94; font-size:8px; }.budget-summary strong { margin-top:3px; font-size:15px; }
.cospend-layout details { margin-top:10px; border:1px solid #343740; border-radius:10px; background:#11141a; }.cospend-layout summary { display:flex; align-items:center; justify-content:space-between; padding:12px; cursor:pointer; list-style:none; }.cospend-layout summary > span { display:grid; grid-template-columns:auto 1fr; align-items:center; gap:2px 8px; }.cospend-layout summary svg { grid-row:1/3; color:#cbb3fd; }.cospend-layout summary strong { font-size:10px; }.cospend-layout summary small { color:#8c8a94; font-size:7px; }
.budget-metrics { display:grid; grid-template-columns:repeat(3,1fr); gap:7px; padding:0 12px 12px; }.budget-metrics article { display:grid; padding:10px; border-radius:8px; background:#1a1d23; }.budget-metrics small { color:#8c8a94; font-size:7px; }.budget-metrics strong { margin-top:5px; font-size:10px; }
.transaction-list { margin-top:14px; }.transaction-list header { display:flex; align-items:center; justify-content:space-between; margin-bottom:4px; }.transaction-list header strong { font-size:11px; }.transaction-list header button { margin:0; background:transparent; color:#cbb3fd; }
.transaction-list article { min-height:55px; display:grid; grid-template-columns:34px minmax(0,1fr) auto; align-items:center; gap:9px; border-bottom:1px solid #292c33; }.transaction-list article > span { width:32px; height:32px; display:grid; place-items:center; border-radius:8px; background:#25232e; color:#cbb3fd; }.transaction-list article div { display:grid; }.transaction-list article strong,.transaction-list article b { font-size:9px; }.transaction-list article small { color:#8c8a94; font-size:7px; }
.music-layout { flex:1; position:relative; padding:14px 14px 70px; }.music-layout nav { display:flex; gap:4px; margin-bottom:14px; }.album-grid { grid-template-columns:repeat(3,minmax(0,1fr)); }.album-grid article { position:relative; }.album-grid article > img { width:100%; height:80px; object-fit:cover; border-radius:7px; }.album-grid article > button { position:absolute; right:10px; bottom:10px; width:27px; height:27px; display:grid; place-items:center; border:0; border-radius:999px; background:#9b72e6; }
.music-player { position:absolute; right:0; bottom:0; left:0; min-height:58px; display:grid; grid-template-columns:34px minmax(0,1fr) auto auto auto auto; align-items:center; gap:7px; padding:7px 14px; border-top:1px solid #343740; background:#181b21; }.music-player > img { width:32px; height:32px; object-fit:cover; border-radius:7px; }.music-player > div { display:grid; }.music-player strong { font-size:9px; }.music-player small,.music-player time { color:#8c8a94; font-size:7px; }.music-player button { width:27px; height:27px; display:grid; place-items:center; border:0; border-radius:999px; background:transparent; }.music-player button.play { background:#cbb3fd; color:#29183d; }
.calendar-layout { flex:1; display:grid; grid-template-columns:120px minmax(260px,1fr) 175px; }.calendar-layout > aside:first-child { gap:8px; }.calendar-layout .new-event { min-height:33px; margin-bottom:10px; border:0; border-radius:7px; background:#8d64dc; color:#fff; font-size:8px; }.calendar-layout label { display:flex; align-items:center; gap:7px; color:#d4d1d8; font-size:8px; }.calendar-layout input { accent-color:#9b72e6; }
.calendar-month { min-width:0; padding:12px; border-right:1px solid #292c33; }.calendar-month > header { display:flex; align-items:center; gap:5px; margin-bottom:10px; }.calendar-month h3 { margin:0 auto 0 4px; font-size:12px; }.calendar-month header button { min-width:28px; height:28px; border:1px solid #343740; border-radius:6px; background:#15181e; font-size:8px; }.weekdays,.month-grid { display:grid; grid-template-columns:repeat(7,1fr); }.weekdays span { padding:5px; color:#8c8a94; font-size:7px; text-align:center; }.month-grid button { min-width:0; min-height:56px; display:flex; flex-direction:column; align-items:flex-start; gap:5px; padding:5px; border:1px solid #292c33; background:transparent; font-size:7px; }.month-grid button.today { background:#211c2a; box-shadow:inset 0 0 0 1px #9b72e6; }.month-grid button.muted { opacity:.25; }.month-grid b { max-width:100%; overflow:hidden; padding:3px 4px; border-radius:4px; background:#43365a; color:#dfceff; font-size:6px; text-overflow:ellipsis; white-space:nowrap; }
.event-inspector h3 { margin:6px 0 0; font-size:14px; }.event-inspector > button { width:100%; min-height:31px; border:0; border-radius:7px; background:#30283d; color:#d1b7ff; font-size:8px; }
.admin-layout { flex:1; display:grid; grid-template-columns:140px minmax(0,1fr); }.admin-layout > section { padding:16px; }.admin-layout section > header { display:flex; align-items:center; margin-bottom:16px; }.admin-layout section > header h3 { margin:0; font-size:16px; }.admin-layout section > header p { margin:4px 0 0; color:#8c8a94; font-size:8px; }
.user-head { min-height:30px; display:grid; grid-template-columns:minmax(100px,1fr) 90px 110px 65px; align-items:center; padding:0 8px 0 47px; border-bottom:1px solid #292c33; color:#8c8a94; font-size:7px; }.user-table article { min-height:54px; display:grid; grid-template-columns:30px minmax(60px,1fr) 90px 110px 65px auto; align-items:center; gap:8px; padding:0 8px; border-bottom:1px solid #292c33; font-size:8px; }.user-table article .avatar { width:28px; height:28px; }.user-table article button { border:0; background:transparent; }.online { color:#5de0c0; }
.user-table .avatar img { width:100%; height:100%; object-fit:cover; border-radius:inherit; }
.admin-cards { display:grid; grid-template-columns:repeat(3,1fr); gap:9px; }.admin-cards article { display:grid; gap:9px; padding:16px; border:1px solid #343740; border-radius:9px; background:#15181e; }.admin-cards svg { color:#cbb3fd; }.admin-cards strong { font-size:10px; }.admin-cards span { color:#8c8a94; font-size:8px; }
.app-feedback { position:absolute; right:18px; bottom:18px; padding:9px 12px; border:1px solid #4a3e5d; border-radius:8px; background:#272030; color:#e6d9ff; font-size:8px; box-shadow:0 12px 34px rgb(0 0 0 / 40%); }
@media (max-width:960px) {
  .files-layout { grid-template-columns:115px minmax(0,1fr); }.semantic-inspector,.photo-inspector,.mail-message,.event-inspector { display:none; }
  .photos-layout { padding-right:16px; }.photo-grid { grid-template-columns:repeat(3,1fr); }.mail-layout { grid-template-columns:120px minmax(0,1fr); }
  .tables-layout { grid-template-columns:120px minmax(0,1fr); }.table-inspector { display:none; }
  .calendar-layout { grid-template-columns:110px minmax(0,1fr); }
}
@media (max-width:700px) {
  .app-surface { min-height:720px; }.app-toolbar { grid-template-columns:auto minmax(0,1fr) auto; padding:12px 14px; }.mobile-apps { display:grid !important; }.app-toolbar label { display:none; }
  .app-toolbar h2 { font-size:17px; }
  .files-layout,.talk-layout,.mail-layout,.tables-layout,.cookbook-layout,.calendar-layout,.admin-layout { grid-template-columns:1fr; }.mini-source-list,.mail-folders,.table-facets,.calendar-layout > aside:first-child,.admin-layout > aside,.conversation-list { display:none; }
  .file-browser { padding:12px; border:0; }.file-head { display:none; }.file-browser > button { min-height:68px; grid-template-columns:25px minmax(0,1fr) auto; grid-template-rows:auto auto; }.file-browser > button > svg { grid-row:1/3; }.file-browser > button > span:nth-child(2) { grid-row:1/3; }.file-browser > button > span:nth-child(3) { grid-column:3; grid-row:1; color:#d9d6dd; }.file-browser > button > span:nth-child(4) { grid-column:3; grid-row:2; color:#8c8a94; font-size:7px; }.semantic-inspector { display:none; }
  .photos-layout { padding:14px; }.photo-grid { grid-template-columns:repeat(2,1fr); }.photo-inspector { display:none; }
  .collection-cards,.album-grid { grid-template-columns:repeat(2,1fr); }.thread { min-height:640px; }.message-list article { max-width:88%; }
  .mail-list { border:0; }.recipe-list { display:none; }.recipe-detail { padding:22px; }
  .semantic-table { padding:10px; border:0; }.semantic-table-head { display:none; }.semantic-table > button { min-height:72px; grid-template-columns:minmax(110px,1.1fr) 70px 72px; grid-template-rows:auto auto; }.semantic-table > button > span:nth-of-type(1) { grid-column:1; grid-row:2; color:#8c8a94; }.semantic-table > button > span:nth-of-type(2) { grid-column:2; grid-row:1/3; }.semantic-table > button > span:nth-of-type(3) { grid-column:3; grid-row:1/3; }.semantic-table time { display:none; }
  .board-columns { grid-template-columns:repeat(3,240px); }.budget-metrics { grid-template-columns:1fr; }
  .calendar-month { border:0; }.month-grid button { min-height:64px; }.event-inspector { display:none; }
  .admin-layout > section { padding:13px; }.user-head { display:none; }.user-table article { grid-template-columns:30px minmax(70px,1fr) auto; }.user-table article > span:nth-of-type(2),.user-table article > span:nth-of-type(3) { display:none; }
}
</style>
