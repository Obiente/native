import assert from "node:assert/strict";
import test from "node:test";
import { docsContent } from "../src/generated/docs-content.js";

test("repository documentation bodies do not repeat the page H1", () => {
  for (const doc of docsContent) {
    assert.doesNotMatch(doc.html, /<h1\b/u, `${doc.path} contains a duplicate H1`);
  }
});
