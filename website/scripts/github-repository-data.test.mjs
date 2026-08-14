import assert from "node:assert/strict";
import test from "node:test";
import {
  fetchGithubRepository,
  normalizeGithubRepositoryResponse,
  normalizeGithubRepositorySnapshot,
  resolveGithubRepositoryData,
} from "./github-repository-data.mjs";

const snapshot = Object.freeze({
  stargazersCount: 88,
  updatedAt: "2026-08-13T08:06:57Z",
});

test("GitHub repository metadata is normalized for the website", () => {
  assert.deepEqual(
    normalizeGithubRepositoryResponse({
      stargazers_count: 147,
      updated_at: "2026-08-13T19:14:55Z",
    }),
    {
      stargazersCount: 147,
      updatedAt: "2026-08-13T19:14:55Z",
    },
  );
  assert.deepEqual(normalizeGithubRepositorySnapshot(snapshot), snapshot);
});

test("malformed GitHub repository metadata is rejected", () => {
  for (const response of [
    null,
    [],
    { stargazers_count: -1, updated_at: "2026-08-13T19:14:55Z" },
    { stargazers_count: 1.5, updated_at: "2026-08-13T19:14:55Z" },
    { stargazers_count: "147", updated_at: "2026-08-13T19:14:55Z" },
    { stargazers_count: 147, updated_at: "not-a-date" },
  ]) {
    assert.throws(() => normalizeGithubRepositoryResponse(response), TypeError);
  }
});

test("the runtime refresh uses the cached same-origin endpoint", async () => {
  const requests = [];
  const repository = await fetchGithubRepository({
    fetchImpl: async (url, options) => {
      requests.push({ url, options });
      return {
        ok: true,
        status: 200,
        json: async () => ({
          stargazers_count: 148,
          updated_at: "2026-08-13T19:30:00Z",
        }),
      };
    },
  });

  assert.deepEqual(repository, {
    stargazersCount: 148,
    updatedAt: "2026-08-13T19:30:00Z",
  });
  assert.deepEqual(requests, [
    {
      url: "/api/github-repository",
      options: {
        cache: "no-cache",
        headers: { Accept: "application/vnd.github+json" },
      },
    },
  ]);
});

test("the runtime refresh rejects upstream and response failures", async () => {
  await assert.rejects(
    fetchGithubRepository({
      fetchImpl: async () => ({ ok: false, status: 503 }),
    }),
    /HTTP 503/,
  );
  await assert.rejects(
    fetchGithubRepository({
      fetchImpl: async () => ({
        ok: true,
        status: 200,
        json: async () => ({ stargazers_count: -1, updated_at: "invalid" }),
      }),
    }),
    /star count/,
  );
});

test("a successful live refresh wins over the bundled snapshot", async () => {
  let snapshotRead = false;
  const result = await resolveGithubRepositoryData({
    loadLive: async () => ({
      stargazers_count: 147,
      updated_at: "2026-08-13T19:14:55Z",
    }),
    loadSnapshot: async () => {
      snapshotRead = true;
      return snapshot;
    },
  });

  assert.equal(result.source, "github");
  assert.equal(result.warning, null);
  assert.equal(result.repository.stargazersCount, 147);
  assert.equal(snapshotRead, false);
});

test("transport failures preserve the bundled snapshot", async () => {
  const result = await resolveGithubRepositoryData({
    loadLive: async () => {
      throw new Error("GitHub API request failed with HTTP 403.");
    },
    loadSnapshot: async () => snapshot,
  });

  assert.equal(result.source, "snapshot");
  assert.match(result.warning, /HTTP 403/);
  assert.deepEqual(result.repository, snapshot);
});

test("malformed live responses preserve the bundled snapshot", async () => {
  const result = await resolveGithubRepositoryData({
    loadLive: async () => ({ stargazers_count: "many", updated_at: null }),
    loadSnapshot: async () => snapshot,
  });

  assert.equal(result.source, "snapshot");
  assert.match(result.warning, /star count/);
  assert.deepEqual(result.repository, snapshot);
});

test("content generation fails when both live and bundled metadata are invalid", async () => {
  await assert.rejects(
    resolveGithubRepositoryData({
      loadLive: async () => {
        throw new Error("network unavailable");
      },
      loadSnapshot: async () => ({ stargazersCount: -1, updatedAt: "invalid" }),
    }),
    /metadata and its bundled fallback are unavailable/,
  );
});
