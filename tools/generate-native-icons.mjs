#!/usr/bin/env node
// SVG is the editable source. Only platform-required formats are rasterized.
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const require = createRequire(path.join(root, "website/package.json"));
const { Resvg } = require("@resvg/resvg-js");
const source = await readFile(path.join(root, "design/app-icon/native-mark.svg"), "utf8");
const arch = source.match(/id="arch"[^>]*d="([^"]+)"/)[1];
const circle = source.match(/<circle\b[^>]*id="signal-dot"[^>]*\/>/)[0];
const number = (name) => Number(circle.match(new RegExp(`${name}="([^"]+)"`))[1]);
const radius = number("r");
const diameter = radius * 2;
const dot = `M${number("cx") - radius} ${number("cy")}a${radius} ${radius} 0 1 0 ${diameter} 0a${radius} ${radius} 0 1 0-${diameter} 0`;
const shapes = `<path fill="#101418" d="${arch}"/><path fill="#36C69C" d="${dot}"/>`;
const square = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 160 160"><rect width="160" height="160" fill="#F5F7F8"/><g transform="translate(16 24)">${shapes}</g></svg>`;
async function save(relative, data) {
  const target = path.join(root, relative);
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, data);
}
function raster(svg, size) {
  return new Resvg(svg, { fitTo: { mode: "width", value: size } }).render().asPng();
}
for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
  await save(`androidApp/src/main/res/mipmap-${density}/ic_launcher.png`, raster(square, size));
}
await save("ui/src/desktopMain/resources/nextcloud-native.png", raster(square, 512));
const sizes = [16, 32, 48, 64, 128, 256];
const images = sizes.map((size) => raster(square, size));
const header = Buffer.alloc(6 + 16 * sizes.length);
header.writeUInt16LE(1, 2);
header.writeUInt16LE(sizes.length, 4);
let offset = header.length;
sizes.forEach((size, index) => {
  const entry = 6 + index * 16;
  header[entry] = header[entry + 1] = size === 256 ? 0 : size;
  header.writeUInt16LE(1, entry + 4);
  header.writeUInt16LE(32, entry + 6);
  header.writeUInt32LE(images[index].length, entry + 8);
  header.writeUInt32LE(offset, entry + 12);
  offset += images[index].length;
});
await save("ui/src/desktopMain/resources/nextcloud-native.ico", Buffer.concat([header, ...images]));
for (const [name, size] of Object.entries({ "favicon-32.png": 32, "apple-touch-icon.png": 180, "icon-192.png": 192, "icon-512.png": 512 })) {
  await save(`website/public/${name}`, raster(square, size));
}
await save("website/public/favicon.svg", square);
await save("website/public/brand/native-mark.svg", source);
await save("website/public/brand/native-mark-dark.svg", source.replace('fill="#101418"', 'fill="#F5F7F8"'));
await save("androidApp/src/main/res/drawable/ic_launcher_foreground_vector.xml", `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="192" android:viewportHeight="192">
    <group android:translateX="32" android:translateY="40">
        <path android:fillColor="#101418" android:pathData="${arch}" />
        <path android:fillColor="#36C69C" android:pathData="${dot}" />
    </group>
</vector>
`);
await save("ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/design/NativeBrandVector.kt", `// Generated from design/app-icon/native-mark.svg by tools/generate-native-icons.mjs.
package dev.obiente.nextcloudnative.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal fun nativeBrandVector(ink: Color): ImageVector = ImageVector.Builder(
    name = "nati.ve", defaultWidth = 32.dp, defaultHeight = 28.dp,
    viewportWidth = 128f, viewportHeight = 112f,
).addPath(PathParser().parsePathString("${arch}").toNodes(), fill = SolidColor(ink))
    .addPath(PathParser().parsePathString("${dot}").toNodes(), fill = SolidColor(Color(0xFF36C69C)))
    .build()
`);
const banner = await readFile(path.join(root, "design/brand/banner.svg"), "utf8");
await save("website/public/brand/banner.svg", banner);
for (const target of ["website/public/social-preview.png", ".github/social-preview.png"]) {
  await save(target, raster(banner, 1536));
}
console.log("Generated platform exports from editable nati.ve SVG masters.");
