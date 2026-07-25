export const fallbackRoadmapState = Object.freeze({
  source: "repository",
  syncState: "fallback",
  updatedAt: null,
});

export function repositoryRoadmapFallback(projectUrl) {
  return {
    ...fallbackRoadmapState,
    projectUrl,
    epics: [],
    shipped: [],
    milestones: [],
    priorities: [],
    verification: [],
  };
}

const roadmapListFields = ["epics", "shipped", "milestones", "priorities", "verification"];

export function normalizeRoadmapSnapshot(snapshot) {
  if (
    !snapshot ||
    typeof snapshot !== "object" ||
    typeof snapshot.projectUrl !== "string" ||
    typeof snapshot.updatedAt !== "string" ||
    roadmapListFields.some((field) => !Array.isArray(snapshot[field]))
  ) {
    throw new TypeError("The bundled roadmap snapshot is incomplete.");
  }
  return {
    ...snapshot,
    source: "github-snapshot",
    syncState: "snapshot",
  };
}

export function roadmapSnapshotFromLive(roadmap) {
  if (roadmap?.source !== "github" || roadmap?.syncState !== "live") {
    throw new TypeError("Only live GitHub roadmap data can refresh the bundled snapshot.");
  }
  return normalizeRoadmapSnapshot(roadmap);
}

export function nextPageUrl(linkHeader) {
  if (!linkHeader) return null;
  for (const entry of linkHeader.split(",")) {
    const match = entry.match(/<([^>]+)>\s*;\s*rel="next"/);
    if (match) return match[1];
  }
  return null;
}

export async function githubJsonPages(
  initialUrl,
  {
    headers,
    fetchImpl = globalThis.fetch,
    timeoutMs = 8_000,
  },
) {
  const pages = [];
  const visited = new Set();
  let url = initialUrl;

  while (url) {
    if (visited.has(url)) {
      throw new Error("GitHub roadmap pagination returned a repeated next-page URL.");
    }
    visited.add(url);

    const response = await fetchImpl(url, {
      headers,
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      throw new Error(`GitHub roadmap request failed with HTTP ${response.status}.`);
    }
    const page = await response.json();
    if (!Array.isArray(page)) {
      throw new Error("GitHub roadmap response was not an item array.");
    }
    pages.push(...page);
    url = nextPageUrl(response.headers.get("link"));
  }

  return pages;
}

export function isP0P1(item) {
  return item?.priority === "P0" || item?.priority === "P1";
}

export function shippedPriorityItems(items) {
  return items.filter((item) => item.status === "Done" && isP0P1(item));
}
