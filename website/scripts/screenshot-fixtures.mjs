import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const fixtureDirectory = path.join(websiteRoot, "screenshots", "fixtures");
export const screenshotDirectory = path.join(websiteRoot, "public", "screenshots");
export const fixturePath = path.join(fixtureDirectory, "demo-workspace.json");

const forbiddenKeys = /(?:password|passphrase|secret|token|credential|session|cookie|authorization|endpoint|serverUrl)/i;
const forbiddenValues =
  /(?:https?:\/\/|file:\/\/|content:\/\/|\/home\/|\/Users\/|[A-Z]:\\|@[\p{Letter}\p{Number}.-]+\.[\p{Letter}]{2,})/iu;

function inspect(value, location = "fixture") {
  if (Array.isArray(value)) {
    value.forEach((item, index) => inspect(item, `${location}[${index}]`));
    return;
  }
  if (value && typeof value === "object") {
    Object.entries(value).forEach(([key, child]) => {
      if (forbiddenKeys.test(key)) {
        throw new Error(`Screenshot fixture contains forbidden key at ${location}.${key}.`);
      }
      inspect(child, `${location}.${key}`);
    });
    return;
  }
  if (typeof value === "string" && forbiddenValues.test(value)) {
    throw new Error(`Screenshot fixture contains a private-looking value at ${location}.`);
  }
}

export function validateScreenshotFixture(value) {
  if (!value || value.fixtureVersion !== 1 || typeof value.account?.displayName !== "string") {
    throw new Error("Screenshot fixture has an unsupported shape or version.");
  }
  inspect(value);
  return value;
}

export async function readSyntheticScreenshotFixture() {
  const parsed = JSON.parse(await readFile(fixturePath, "utf8"));
  return validateScreenshotFixture(parsed);
}

export function assertSafeScreenshotOutput(outputPath) {
  const resolved = path.resolve(outputPath);
  const relative = path.relative(screenshotDirectory, resolved);
  if (relative.startsWith("..") || path.isAbsolute(relative) || path.extname(resolved) !== ".svg") {
    throw new Error("Screenshot output must be an SVG inside website/public/screenshots.");
  }
  return resolved;
}

