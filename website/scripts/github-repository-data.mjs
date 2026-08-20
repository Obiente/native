function validTimestamp(value) {
  return (
    typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u.test(value) &&
    Number.isFinite(Date.parse(value))
  );
}

function normalizedRepository(stargazersCount, updatedAt, source) {
  if (!Number.isInteger(stargazersCount) || stargazersCount < 0) {
    throw new TypeError(`The ${source} repository star count is invalid.`);
  }
  if (!validTimestamp(updatedAt)) {
    throw new TypeError(`The ${source} repository update timestamp is invalid.`);
  }
  return Object.freeze({ stargazersCount, updatedAt });
}

const canonicalWebsiteOrigin = "https://nc-native.obiente.dev";

/**
 * The cached repository endpoint is an Nginx route, not a static website file.
 * Do not request it from Vite preview, file URLs, or other static hosts.
 */
export function shouldRefreshGithubRepository(location) {
  return location?.origin === canonicalWebsiteOrigin;
}

export function normalizeGithubRepositoryResponse(response) {
  if (!response || typeof response !== "object" || Array.isArray(response)) {
    throw new TypeError("The GitHub repository response is not an object.");
  }
  return normalizedRepository(
    response.stargazers_count,
    response.updated_at,
    "GitHub",
  );
}

export function normalizeGithubRepositorySnapshot(snapshot) {
  if (!snapshot || typeof snapshot !== "object" || Array.isArray(snapshot)) {
    throw new TypeError("The bundled repository snapshot is not an object.");
  }
  return normalizedRepository(
    snapshot.stargazersCount,
    snapshot.updatedAt,
    "bundled",
  );
}

export async function fetchGithubRepository({
  fetchImpl = globalThis.fetch,
  endpoint = "/api/github-repository",
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new TypeError("A repository metadata fetch implementation is required.");
  }
  const response = await fetchImpl(endpoint, {
    cache: "no-cache",
    headers: {
      Accept: "application/vnd.github+json",
    },
  });
  if (!response.ok) {
    throw new Error(`Repository metadata request failed with HTTP ${response.status}.`);
  }
  return normalizeGithubRepositoryResponse(await response.json());
}

export async function resolveGithubRepositoryData({ loadLive, loadSnapshot }) {
  if (typeof loadLive !== "function" || typeof loadSnapshot !== "function") {
    throw new TypeError("Repository metadata loaders are required.");
  }
  try {
    return {
      repository: normalizeGithubRepositoryResponse(await loadLive()),
      source: "github",
      warning: null,
    };
  } catch (liveError) {
    try {
      return {
        repository: normalizeGithubRepositorySnapshot(await loadSnapshot()),
        source: "snapshot",
        warning: liveError instanceof Error ? liveError.message : String(liveError),
      };
    } catch (snapshotError) {
      throw new Error(
        `GitHub repository metadata and its bundled fallback are unavailable: ${
          liveError instanceof Error ? liveError.message : String(liveError)
        }; ${snapshotError instanceof Error ? snapshotError.message : String(snapshotError)}`,
      );
    }
  }
}
