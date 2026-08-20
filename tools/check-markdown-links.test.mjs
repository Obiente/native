import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const checker = path.join(scriptDirectory, "check-markdown-links.mjs");

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "markdown-links-"));
  fs.mkdirSync(path.join(root, "docs"));
  fs.mkdirSync(path.join(root, "assets"));
  fs.writeFileSync(path.join(root, "docs", "guide.md"), "# Guide\n");
  fs.writeFileSync(path.join(root, "assets", "diagram.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>\n");
  return root;
}

function check(root) {
  return spawnSync(process.execPath, [checker, root], { encoding: "utf8" });
}

test("accepts existing Markdown and HTML targets", () => {
  const root = fixture();
  try {
    fs.writeFileSync(
      path.join(root, "README.md"),
      "[Guide](docs/guide.md#start)\n<img src=\"assets/diagram.svg\">\n",
    );
    assert.equal(check(root).status, 0);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("rejects missing local targets", () => {
  const root = fixture();
  try {
    fs.writeFileSync(path.join(root, "README.md"), "[Missing](docs/missing.md)\n");
    const result = check(root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /missing local target/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("rejects links that escape the repository", () => {
  const root = fixture();
  try {
    fs.writeFileSync(path.join(root, "README.md"), "[Private](../outside.md)\n");
    const result = check(root);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /escapes the repository/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
