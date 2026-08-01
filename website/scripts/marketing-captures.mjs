import { createHash } from "node:crypto";
import { lstat, readFile, readdir, realpath } from "node:fs/promises";
import path from "node:path";
import { PNG } from "pngjs";
import { fileURLToPath } from "node:url";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const repositoryRoot = path.resolve(websiteRoot, "..");
export const captureManifestPath = path.join(
  websiteRoot,
  "public",
  "screenshots",
  "capture-manifest.json",
);
const captureInventoryRelativePath = "tools/marketing-capture-inputs.txt";
const captureThemes = ["dark", "light"];

export async function readCaptureManifest() {
  const manifest = JSON.parse(await readFile(captureManifestPath, "utf8"));
  validateCaptureManifest(manifest);
  return manifest;
}

export function validateCaptureManifest(manifest) {
  requireObject(manifest, "Capture manifest");
  requireExactKeys(
    manifest,
    [
      "schemaVersion",
      "renderer",
      "identity",
      "cloudIdentity",
      "networkAccess",
      "captureSources",
      "captureSourceHashes",
      "avatarSha256",
      "captures",
    ],
    "Capture manifest",
  );
  requireValue(manifest.schemaVersion === 4, "schemaVersion must be 4");
  requireValue(
    manifest.renderer === "Compose ImageComposeScene",
    "renderer must identify ImageComposeScene",
  );
  requireValue(manifest.identity === "Obiente", "identity must be Obiente");
  requireValue(manifest.cloudIdentity === "Nextcloud", "cloudIdentity must be Nextcloud");
  requireValue(manifest.networkAccess === false, "networkAccess must be false");
  requireSha256(manifest.avatarSha256, "avatarSha256");

  requireValue(
    Array.isArray(manifest.captureSources) && manifest.captureSources.length > 0,
    "captureSources must not be empty",
  );
  requireValue(
    new Set(manifest.captureSources).size === manifest.captureSources.length,
    "captureSources must be unique",
  );
  for (const relative of manifest.captureSources) {
    requireSafeRelativePath(relative, "captureSources entry");
  }
  requireObject(manifest.captureSourceHashes, "captureSourceHashes");
  requireExactKeys(
    manifest.captureSourceHashes,
    manifest.captureSources,
    "captureSourceHashes",
  );
  for (const [relative, digest] of Object.entries(manifest.captureSourceHashes)) {
    requireSafeRelativePath(relative, "captureSourceHashes entry");
    requireSha256(digest, `captureSourceHashes ${relative}`);
  }

  requireValue(
    Array.isArray(manifest.captures) && manifest.captures.length > 0,
    "captures must not be empty",
  );
  const scenarios = new Set();
  const files = new Set();
  for (const capture of manifest.captures) {
    requireObject(capture, "Capture");
    const expectedCaptureKeys = [
      "scenario",
      "baseScenario",
      "file",
      "theme",
      "width",
      "height",
      "density",
      "feature",
      "surface",
      "state",
      "purpose",
      "platform",
      "viewport",
      "sha256",
    ];
    if (capture.pullRequest !== undefined) expectedCaptureKeys.push("pullRequest");
    if (capture.issue !== undefined) expectedCaptureKeys.push("issue");
    requireExactKeys(capture, expectedCaptureKeys, "Capture");
    requireSlug(capture.scenario, "capture scenario");
    requireSlug(capture.baseScenario, `${capture.scenario} baseScenario`);
    requireValue(
      captureThemes.includes(capture.theme),
      `${capture.scenario} theme must be dark or light`,
    );
    requireValue(
      !scenarios.has(capture.scenario),
      `Duplicate capture scenario: ${capture.scenario}`,
    );
    scenarios.add(capture.scenario);
    requireValue(
      typeof capture.file === "string" && /^[a-z0-9-]+\.png$/u.test(capture.file),
      `Invalid capture file for ${capture.scenario}`,
    );
    requireValue(!files.has(capture.file), `Duplicate capture file: ${capture.file}`);
    files.add(capture.file);
    requirePositiveInteger(capture.width, `${capture.scenario} width`);
    requirePositiveInteger(capture.height, `${capture.scenario} height`);
    requireValue(
      typeof capture.density === "number" &&
        Number.isFinite(capture.density) &&
        capture.density > 0,
      `${capture.scenario} density must be positive`,
    );
    requireSha256(capture.sha256, `${capture.scenario} sha256`);
    requireLabel(capture.feature, `${capture.scenario} feature`);
    requireLabel(capture.surface, `${capture.scenario} surface`);
    requireLabel(capture.state, `${capture.scenario} state`);
    requireValue(
      capture.purpose === "showcase" || capture.purpose === "state-coverage",
      `${capture.scenario} purpose must be showcase or state-coverage`,
    );
    requireSlug(capture.platform, `${capture.scenario} platform`);
    requireSlug(capture.viewport, `${capture.scenario} viewport`);
    if (capture.pullRequest !== undefined) {
      requirePositiveInteger(capture.pullRequest, `${capture.scenario} pullRequest`);
    }
    if (capture.issue !== undefined) {
      requirePositiveInteger(capture.issue, `${capture.scenario} issue`);
    }
  }
  const capturesByBase = new Map();
  for (const capture of manifest.captures) {
    const pair = capturesByBase.get(capture.baseScenario) ?? [];
    pair.push(capture);
    capturesByBase.set(capture.baseScenario, pair);
  }
  for (const [baseScenario, pair] of capturesByBase) {
    requireValue(
      pair.length === captureThemes.length &&
        captureThemes.every(
          (theme) => pair.filter((capture) => capture.theme === theme).length === 1,
        ),
      `${baseScenario} must declare exactly one dark and one light capture`,
    );
    const [reference, candidate] = pair;
    for (const field of [
      "width",
      "height",
      "density",
      "feature",
      "surface",
      "state",
      "purpose",
      "platform",
      "viewport",
      "pullRequest",
      "issue",
    ]) {
      requireValue(
        candidate[field] === reference[field],
        `${baseScenario} theme variants must share ${field}`,
      );
    }
  }
  return manifest;
}

export function stableCapturePath(capture) {
  return `/screenshots/${capture.file}`;
}

export function websiteCapturePath(manifest, capture) {
  const revision = createHash("sha256").update(capture.sha256).digest("hex");
  return `${stableCapturePath(capture)}?v=${revision}`;
}

export function articleCapture(manifest, scenario, sourceLabel) {
  return articleCapturePair(manifest, scenario, sourceLabel).dark;
}

export function articleCapturePair(manifest, baseScenario, sourceLabel) {
  const captures = manifest.captures.filter(
    (candidate) => candidate.baseScenario === baseScenario,
  );
  if (captures.length === 0) {
    throw new Error(
      `${sourceLabel}: captureScenario must reference a declared Compose capture.`,
    );
  }
  if (captures.some((capture) => capture.purpose !== "showcase")) {
    throw new Error(
      `${sourceLabel}: captureScenario must reference a showcase capture, not state coverage.`,
    );
  }
  const dark = captures.find((capture) => capture.theme === "dark");
  const light = captures.find((capture) => capture.theme === "light");
  if (!dark || !light) {
    throw new Error(
      `${sourceLabel}: captureScenario must provide both dark and light captures.`,
    );
  }
  return { dark, light };
}

export async function verifyCaptureAssets(manifest) {
  const failures = [];
  for (const capture of manifest.captures) {
    const imagePath = path.join(websiteRoot, "public", "screenshots", capture.file);
    try {
      const bytes = await readFile(imagePath);
      const dimensions = decodePngDimensions(bytes);
      if (dimensions.width !== capture.width || dimensions.height !== capture.height) {
        failures.push(
          `${capture.file} is ${dimensions.width}x${dimensions.height}; manifest expects ` +
            `${capture.width}x${capture.height}`,
        );
      }
      const digest = sha256(bytes);
      if (digest !== capture.sha256) {
        failures.push(`${capture.file} content does not match its manifest sha256`);
      }
    } catch (error) {
      failures.push(`${capture.file} could not be validated: ${error.message}`);
    }
  }
  return failures;
}

export async function verifyCaptureFreshness(manifest) {
  const failures = await verifyCaptureAssets(manifest);
  const expectedSources = await discoverCaptureSources();
  const declaredSources = [...manifest.captureSources].sort();
  const expectedSet = new Set(expectedSources);
  const declaredSet = new Set(declaredSources);
  const missing = expectedSources.filter((relative) => !declaredSet.has(relative));
  const obsolete = declaredSources.filter((relative) => !expectedSet.has(relative));
  if (missing.length > 0) {
    failures.push(`captureSources is missing: ${missing.join(", ")}`);
  }
  if (obsolete.length > 0) {
    failures.push(`captureSources contains obsolete entries: ${obsolete.join(", ")}`);
  }
  for (const relative of expectedSources) {
    const bytes = await readFile(path.join(repositoryRoot, relative));
    if (manifest.captureSourceHashes[relative] !== sha256(bytes)) {
      failures.push(`captureSourceHashes does not match: ${relative}`);
    }
  }
  if (manifest.avatarSha256) {
    const avatar = await readFile(
      path.join(
        repositoryRoot,
        "ui",
        "src",
        "desktopMain",
        "resources",
        "marketing",
        "obiente-avatar.png",
      ),
    );
    if (sha256(avatar) !== manifest.avatarSha256) {
      failures.push("avatarSha256 does not match the capture avatar");
    }
  }
  return failures;
}

export async function discoverCaptureSources(root = repositoryRoot) {
  const realRoot = await realpath(root);
  const inventoryPath = path.resolve(root, captureInventoryRelativePath);
  await requireSafeRepositoryEntry(root, realRoot, inventoryPath, captureInventoryRelativePath);
  const entries = (await readFile(inventoryPath, "utf8"))
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith("#"));
  const discovered = [];
  for (const entry of entries) {
    const optional = entry.startsWith("?");
    const relative = optional ? entry.slice(1) : entry;
    requireSafeRelativePath(relative, "capture input");
    const absolute = path.resolve(root, relative);
    requireContainedPath(root, absolute, relative);
    let info;
    try {
      info = await lstat(absolute);
    } catch (error) {
      if (optional && error.code === "ENOENT") continue;
      throw error;
    }
    requireValue(!info.isSymbolicLink(), `capture input must not be a symbolic link: ${relative}`);
    await requireSafeRepositoryEntry(root, realRoot, absolute, relative);
    if (info.isFile()) {
      discovered.push(relative);
    } else if (info.isDirectory()) {
      await walkFiles(absolute, discovered, root, realRoot);
    } else {
      throw new Error(`Capture input is not a file or directory: ${relative}`);
    }
  }
  discovered.push(captureInventoryRelativePath);
  return [...new Set(discovered)].sort();
}

async function walkFiles(directory, output, root, realRoot) {
  const entries = await readdir(directory, { withFileTypes: true });
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const absolute = path.join(directory, entry.name);
    const relative = path.relative(root, absolute).split(path.sep).join("/");
    requireValue(!entry.isSymbolicLink(), `capture input must not be a symbolic link: ${relative}`);
    await requireSafeRepositoryEntry(root, realRoot, absolute, relative);
    if (entry.isDirectory()) {
      await walkFiles(absolute, output, root, realRoot);
    } else if (entry.isFile()) {
      output.push(relative);
    } else {
      throw new Error(`Capture input is not a regular file or directory: ${relative}`);
    }
  }
}

export function decodePngDimensions(bytes) {
  const decoded = PNG.sync.read(bytes, {
    checkCRC: true,
  });
  requireValue(
    decoded.data.length === decoded.width * decoded.height * 4,
    "decoded PNG pixel data has an unexpected length",
  );
  return {
    width: decoded.width,
    height: decoded.height,
  };
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function requireObject(value, label) {
  requireValue(
    value !== null && typeof value === "object" && !Array.isArray(value),
    `${label} must be an object`,
  );
}

function requireExactKeys(value, expected, label) {
  const actualKeys = Object.keys(value).sort();
  const expectedKeys = [...expected].sort();
  requireValue(
    actualKeys.length === expectedKeys.length &&
      actualKeys.every((key, index) => key === expectedKeys[index]),
    `${label} has unexpected or missing fields`,
  );
}

function requireContainedPath(root, absolute, label) {
  const relative = path.relative(path.resolve(root), absolute);
  requireValue(
    relative.length > 0 && !relative.startsWith(`..${path.sep}`) && relative !== ".." &&
      !path.isAbsolute(relative),
    `capture input escaped the repository: ${label}`,
  );
}

async function requireSafeRepositoryEntry(root, realRoot, absolute, label) {
  requireContainedPath(root, absolute, label);
  const info = await lstat(absolute);
  requireValue(!info.isSymbolicLink(), `capture input must not be a symbolic link: ${label}`);
  const resolved = await realpath(absolute);
  const relative = path.relative(realRoot, resolved);
  requireValue(
    relative === "" ||
      (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative)),
    `capture input escaped the real repository: ${label}`,
  );
}

function requireSafeRelativePath(value, label) {
  requireValue(
    typeof value === "string" &&
      value.length > 0 &&
      !path.isAbsolute(value) &&
      !value.split(/[\\/]/u).includes("..") &&
      !value.includes("\\"),
    `${label} must be a safe repository-relative POSIX path`,
  );
}

function requirePositiveInteger(value, label) {
  requireValue(Number.isInteger(value) && value > 0, `${label} must be a positive integer`);
}

function requireSha256(value, label) {
  requireValue(
    typeof value === "string" && /^[a-f0-9]{64}$/u.test(value),
    `${label} must be a lowercase SHA-256 digest`,
  );
}

function requireSlug(value, label) {
  requireValue(
    typeof value === "string" && /^[a-z0-9-]+$/u.test(value),
    `${label} must be a lowercase slug`,
  );
}

function requireLabel(value, label) {
  requireValue(
    typeof value === "string" && value.trim() === value && value.length > 0,
    `${label} must not be empty`,
  );
}

function requireValue(condition, message) {
  if (!condition) throw new Error(`Invalid marketing capture manifest: ${message}.`);
}
