#!/usr/bin/env node
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  assertSafeScreenshotOutput,
  readSyntheticScreenshotFixture,
  screenshotDirectory,
} from "./screenshot-fixtures.mjs";

const fixture = await readSyntheticScreenshotFixture();

function escapeXml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function shell(active, content) {
  const apps = ["Files", "Photos", "Talk", "Calendar", "Music"];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="750" viewBox="0 0 1200 750">
  <rect width="1200" height="750" fill="#0d0f13"/>
  <rect x="24" y="24" width="1152" height="702" rx="24" fill="#111319" stroke="#3d3d47"/>
  <rect x="24" y="24" width="1152" height="66" rx="24" fill="#1a1c22"/>
  <path d="M24 66h1152v24H24z" fill="#1a1c22"/>
  <circle cx="57" cy="57" r="7" fill="#cbb3fd"/><circle cx="80" cy="57" r="7" fill="#6fd5c3"/>
  <text x="108" y="53" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="15" font-weight="700">Nextcloud Native</text>
  <text x="108" y="71" fill="#8f8d98" font-family="Inter,system-ui,sans-serif" font-size="10">Synthetic product preview</text>
  <rect x="42" y="108" width="205" height="598" rx="16" fill="#101217"/>
  <text x="64" y="143" fill="#777680" font-family="Inter,system-ui,sans-serif" font-size="10" font-weight="700" letter-spacing="1.4">WORKSPACE</text>
  ${apps
    .map(
      (app, index) => `<rect x="54" y="${160 + index * 52}" width="181" height="42" rx="10" fill="${
        app === active ? "#373044" : "transparent"
      }"/>
  <circle cx="76" cy="${181 + index * 52}" r="8" fill="${app === active ? "#cbb3fd" : "#56545e"}"/>
  <text x="96" y="${186 + index * 52}" fill="${app === active ? "#f7f5fa" : "#a8a6b0"}" font-family="Inter,system-ui,sans-serif" font-size="13" font-weight="600">${app}</text>`,
    )
    .join("")}
  <circle cx="74" cy="665" r="17" fill="#373044"/>
  <text x="74" y="671" text-anchor="middle" fill="#cbb3fd" font-family="Inter,system-ui,sans-serif" font-size="13" font-weight="700">${escapeXml(fixture.account.avatar)}</text>
  <text x="100" y="660" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="11" font-weight="700">${escapeXml(fixture.account.displayName)}</text>
  <text x="100" y="678" fill="#6fd5c3" font-family="Inter,system-ui,sans-serif" font-size="9">${escapeXml(fixture.account.serverLabel)} · connected</text>
  ${content}
</svg>`;
}

function filesScreenshot() {
  const rows = fixture.files
    .map((file, index) => {
      const y = 247 + index * 82;
      return `<rect x="286" y="${y}" width="590" height="66" rx="13" fill="${index === 2 ? "#24232e" : "#1a1c22"}" stroke="${index === 2 ? "#6f5c87" : "#292b31"}"/>
      <rect x="304" y="${y + 15}" width="36" height="36" rx="10" fill="#373044"/>
      <text x="354" y="${y + 27}" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="13" font-weight="700">${escapeXml(file.name)}</text>
      <text x="354" y="${y + 47}" fill="#a8a6b0" font-family="Inter,system-ui,sans-serif" font-size="10">${escapeXml(file.kind)} · ${escapeXml(file.detail)}</text>
      <text x="852" y="${y + 37}" text-anchor="end" fill="${file.status.includes("Uploading") ? "#cbb3fd" : "#8f8d98"}" font-family="Inter,system-ui,sans-serif" font-size="10">${escapeXml(file.status)}</text>`;
    })
    .join("");
  return shell(
    "Files",
    `<text x="286" y="141" fill="#8f8d98" font-family="Inter,system-ui,sans-serif" font-size="10">Files / Projects</text>
    <text x="286" y="178" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="28" font-weight="700">Project workspace</text>
    <rect x="286" y="197" width="590" height="38" rx="11" fill="#1a1c22" stroke="#292b31"/>
    <text x="306" y="221" fill="#777680" font-family="Inter,system-ui,sans-serif" font-size="11">Search this folder</text>
    ${rows}
    <rect x="900" y="130" width="245" height="526" rx="16" fill="#1a1c22" stroke="#292b31"/>
    <rect x="926" y="163" width="193" height="145" rx="14" fill="#24232e"/>
    <text x="1022" y="243" text-anchor="middle" fill="#cbb3fd" font-family="Inter,system-ui,sans-serif" font-size="42">▶</text>
    <text x="926" y="346" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="15" font-weight="700">${escapeXml(fixture.files[2].name)}</text>
    <text x="926" y="369" fill="#a8a6b0" font-family="Inter,system-ui,sans-serif" font-size="10">Video · ${escapeXml(fixture.files[2].detail)}</text>
    <text x="926" y="420" fill="#777680" font-family="Inter,system-ui,sans-serif" font-size="9">TRANSFER</text>
    <rect x="926" y="435" width="193" height="7" rx="4" fill="#292b31"/><rect x="926" y="435" width="131" height="7" rx="4" fill="#cbb3fd"/>
    <text x="926" y="466" fill="#cbb3fd" font-family="Inter,system-ui,sans-serif" font-size="11">Uploading 68%</text>`,
  );
}

function photosScreenshot() {
  const cards = fixture.photos
    .map((photo, index) => {
      const x = 286 + (index % 2) * 278;
      const y = 242 + Math.floor(index / 2) * 210;
      return `<rect x="${x}" y="${y}" width="258" height="188" rx="16" fill="${escapeXml(photo.color)}"/>
      <rect x="${x}" y="${y + 134}" width="258" height="54" rx="0" fill="#111319" fill-opacity=".88"/>
      <text x="${x + 15}" y="${y + 156}" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="12" font-weight="700">${escapeXml(photo.label)}</text>
      <text x="${x + 15}" y="${y + 176}" fill="#c8c5cf" font-family="Inter,system-ui,sans-serif" font-size="9">${escapeXml(photo.status)}</text>`;
    })
    .join("");
  return shell(
    "Photos",
    `<text x="286" y="141" fill="#8f8d98" font-family="Inter,system-ui,sans-serif" font-size="10">Photos / Memories</text>
    <text x="286" y="178" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="28" font-weight="700">Your timeline</text>
    <rect x="286" y="198" width="82" height="30" rx="15" fill="#373044"/><text x="327" y="217" text-anchor="middle" fill="#cbb3fd" font-family="Inter,system-ui,sans-serif" font-size="10" font-weight="700">Timeline</text>
    <text x="389" y="217" fill="#a8a6b0" font-family="Inter,system-ui,sans-serif" font-size="10">Albums</text>
    <text x="458" y="217" fill="#a8a6b0" font-family="Inter,system-ui,sans-serif" font-size="10">People</text>
    ${cards}
    <rect x="870" y="130" width="275" height="510" rx="16" fill="#1a1c22" stroke="#292b31"/>
    <text x="896" y="168" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="15" font-weight="700">Backup status</text>
    <text x="896" y="193" fill="#a8a6b0" font-family="Inter,system-ui,sans-serif" font-size="10">This device · Camera</text>
    <text x="896" y="251" fill="#6fd5c3" font-family="Inter,system-ui,sans-serif" font-size="25" font-weight="700">128</text>
    <text x="896" y="273" fill="#8f8d98" font-family="Inter,system-ui,sans-serif" font-size="9">BACKED UP</text>
    <text x="1010" y="251" fill="#cbb3fd" font-family="Inter,system-ui,sans-serif" font-size="25" font-weight="700">3</text>
    <text x="1010" y="273" fill="#8f8d98" font-family="Inter,system-ui,sans-serif" font-size="9">PENDING</text>
    <rect x="896" y="310" width="223" height="44" rx="12" fill="#24232e"/>
    <text x="912" y="337" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="11">Review pending uploads</text>`,
  );
}

function talkScreenshot() {
  const chats = fixture.conversations
    .map((chat, index) => {
      const y = 204 + index * 72;
      return `<rect x="274" y="${y}" width="285" height="62" rx="12" fill="${index === 0 ? "#373044" : "#1a1c22"}"/>
      <circle cx="303" cy="${y + 31}" r="17" fill="#24232e"/>
      <text x="329" y="${y + 26}" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="11" font-weight="700">${escapeXml(chat.name)}</text>
      <text x="329" y="${y + 44}" fill="#a8a6b0" font-family="Inter,system-ui,sans-serif" font-size="9">${escapeXml(chat.message)}</text>
      ${chat.unread ? `<circle cx="538" cy="${y + 31}" r="10" fill="#cbb3fd"/><text x="538" y="${y + 35}" text-anchor="middle" fill="#2c1746" font-family="Inter,system-ui,sans-serif" font-size="9" font-weight="700">${chat.unread}</text>` : ""}`;
    })
    .join("");
  return shell(
    "Talk",
    `<text x="274" y="143" fill="#8f8d98" font-family="Inter,system-ui,sans-serif" font-size="10">Talk</text>
    <text x="274" y="176" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="24" font-weight="700">Conversations</text>
    ${chats}
    <line x1="580" y1="112" x2="580" y2="690" stroke="#292b31"/>
    <text x="610" y="154" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="15" font-weight="700">${escapeXml(fixture.conversations[0].name)}</text>
    <text x="610" y="175" fill="#6fd5c3" font-family="Inter,system-ui,sans-serif" font-size="9">3 participants · active now</text>
    <rect x="620" y="225" width="345" height="74" rx="16" fill="#1a1c22"/>
    <text x="638" y="250" fill="#cbb3fd" font-family="Inter,system-ui,sans-serif" font-size="9">RIVER</text>
    <text x="638" y="274" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="11">The new gallery flow is ready to review.</text>
    <rect x="741" y="325" width="365" height="82" rx="16" fill="#373044"/>
    <text x="759" y="353" fill="#f7f5fa" font-family="Inter,system-ui,sans-serif" font-size="11">I will check the full-quality view.</text>
    <text x="1087" y="390" text-anchor="end" fill="#a8a6b0" font-family="Inter,system-ui,sans-serif" font-size="9">14:31</text>
    <rect x="610" y="611" width="500" height="48" rx="15" fill="#1a1c22" stroke="#3d3d47"/>
    <text x="630" y="640" fill="#777680" font-family="Inter,system-ui,sans-serif" font-size="11">Write a message</text>
    <circle cx="1082" cy="635" r="16" fill="#cbb3fd"/>`,
  );
}

await mkdir(screenshotDirectory, { recursive: true });
const outputs = [
  ["files-workspace.svg", filesScreenshot()],
  ["photos-timeline.svg", photosScreenshot()],
  ["talk-conversation.svg", talkScreenshot()],
];
for (const [name, source] of outputs) {
  await writeFile(assertSafeScreenshotOutput(path.join(screenshotDirectory, name)), `${source}\n`);
}
console.log(`Generated ${outputs.length} fixture-only screenshots.`);

