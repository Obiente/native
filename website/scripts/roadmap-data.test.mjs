import assert from "node:assert/strict";
import test from "node:test";
import {
  fallbackRoadmapState,
  githubJsonPages,
  nextPageUrl,
  normalizeRoadmapSnapshot,
  repositoryRoadmapFallback,
  roadmapSnapshotFromLive,
  shippedPriorityItems,
} from "./roadmap-data.mjs";

test("GitHub roadmap acquisition follows every next-page link", async () => {
  const requested = [];
  const responses = new Map([
    [
      "https://api.example.test/items?per_page=100",
      new Response(JSON.stringify([{ id: 1 }, { id: 2 }]), {
        headers: {
          link: '<https://api.example.test/items?after=cursor-2>; rel="next"',
        },
      }),
    ],
    [
      "https://api.example.test/items?after=cursor-2",
      new Response(JSON.stringify([{ id: 3 }]), {
        headers: {
          link: '<https://api.example.test/items?before=cursor-1>; rel="prev"',
        },
      }),
    ],
  ]);

  const items = await githubJsonPages(
    "https://api.example.test/items?per_page=100",
    {
      headers: { Accept: "application/json" },
      fetchImpl: async (url) => {
        requested.push(url);
        return responses.get(url);
      },
    },
  );

  assert.deepEqual(items, [{ id: 1 }, { id: 2 }, { id: 3 }]);
  assert.deepEqual(requested, [
    "https://api.example.test/items?per_page=100",
    "https://api.example.test/items?after=cursor-2",
  ]);
  assert.equal(
    nextPageUrl(
      '<https://api.example.test/items?before=one>; rel="prev", ' +
        '<https://api.example.test/items?after=two>; rel="next"',
    ),
    "https://api.example.test/items?after=two",
  );
});

test("roadmap progress includes only shipped P0 and P1 issues", () => {
  assert.deepEqual(
    shippedPriorityItems([
      { number: 1, status: "Done", priority: "P0" },
      { number: 2, status: "Done", priority: "P1" },
      { number: 3, status: "Done", priority: "P2" },
      { number: 4, status: "Done", priority: null },
      { number: 5, status: "In Progress", priority: "P0" },
    ]).map((item) => item.number),
    [1, 2],
  );
});

test("repository roadmap fallback has deterministic truthful sync state", () => {
  assert.deepEqual(fallbackRoadmapState, {
    source: "repository",
    syncState: "fallback",
    updatedAt: null,
  });
  assert.equal(JSON.stringify(fallbackRoadmapState), JSON.stringify(fallbackRoadmapState));

  const fallback = repositoryRoadmapFallback("https://example.test/project");
  assert.equal(fallback.projectUrl, "https://example.test/project");
  assert.deepEqual(fallback.epics, []);
  assert.deepEqual(fallback.shipped, []);
  assert.deepEqual(fallback.milestones, []);
  assert.deepEqual(fallback.priorities, []);
  assert.deepEqual(fallback.verification, []);
});

test("live roadmap data becomes an explicitly dated bundled snapshot", () => {
  const live = {
    source: "github",
    syncState: "live",
    projectUrl: "https://example.test/project",
    updatedAt: "2026-07-25T12:00:00Z",
    epics: [{ number: 10 }],
    shipped: [{ number: 82 }],
    milestones: [],
    priorities: [],
    verification: [],
  };
  const snapshot = roadmapSnapshotFromLive(live);

  assert.equal(snapshot.source, "github-snapshot");
  assert.equal(snapshot.syncState, "snapshot");
  assert.equal(snapshot.updatedAt, live.updatedAt);
  assert.deepEqual(snapshot.epics, live.epics);
  assert.deepEqual(normalizeRoadmapSnapshot(snapshot), snapshot);
  assert.throws(
    () => normalizeRoadmapSnapshot({ ...snapshot, shipped: null }),
    /snapshot is incomplete/,
  );
});
