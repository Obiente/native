import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { hydrationTheme } from "../src/hydration-state.js";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("the first client theme matches the static server render", async () => {
  assert.deepEqual(hydrationTheme, {
    preference: "system",
    system: "dark",
  });
  assert.equal(Object.isFrozen(hydrationTheme), true);

  const app = await readFile(path.join(websiteRoot, "src", "App.vue"), "utf8");
  assert.match(app, /import \{ hydrationTheme \} from "\.\/hydration-state\.js";/u);
  assert.match(app, /const initialTheme = hydrationTheme;/u);
  assert.doesNotMatch(app, /const initialTheme = typeof window/u);
  assert.doesNotMatch(app, /window\.__NEXTCLOUD_NATIVE_THEME__ \?\?/u);
});
