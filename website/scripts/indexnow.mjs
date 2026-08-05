import { createHash } from "node:crypto";
import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";

const deploymentFilename = "indexnow-deployment.txt";

function decodeXmlText(value) {
  return value
    .replaceAll("&amp;", "&")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'");
}

export function extractIndexNowUrls(sitemap, siteUrl) {
  const canonicalOrigin = new URL(siteUrl).origin;
  const urls = [...sitemap.matchAll(/<loc>([\s\S]*?)<\/loc>/g)].map((match) =>
    decodeXmlText(match[1].trim()),
  );

  if (urls.length === 0) {
    throw new Error("The sitemap does not contain any IndexNow URLs.");
  }
  if (urls.length > 10_000) {
    throw new Error("The sitemap exceeds the IndexNow limit of 10,000 URLs per request.");
  }

  const uniqueUrls = new Set();
  for (const value of urls) {
    const url = new URL(value);
    if (url.origin !== canonicalOrigin) {
      throw new Error(`IndexNow URL belongs to a different origin: ${value}`);
    }
    if (url.username || url.password || url.hash) {
      throw new Error(`IndexNow URL contains unsupported credentials or a fragment: ${value}`);
    }
    uniqueUrls.add(url.href);
  }
  if (uniqueUrls.size !== urls.length) {
    throw new Error("The sitemap contains duplicate IndexNow URLs.");
  }

  return [...uniqueUrls];
}

async function findIndexNowKey(distDirectory) {
  const candidates = [];
  for (const entry of await readdir(distDirectory, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith(".txt")) continue;
    const filenameKey = entry.name.slice(0, -4);
    const content = (await readFile(path.join(distDirectory, entry.name), "utf8")).trim();
    if (filenameKey === content && /^[A-Za-z0-9-]{8,128}$/.test(content)) {
      candidates.push({ filename: entry.name, key: content });
    }
  }

  if (candidates.length !== 1) {
    throw new Error(`Expected exactly one root IndexNow key file, found ${candidates.length}.`);
  }
  return candidates[0];
}

async function listFiles(directory, relativeDirectory = "") {
  const files = [];
  const absoluteDirectory = path.join(directory, relativeDirectory);
  for (const entry of await readdir(absoluteDirectory, { withFileTypes: true })) {
    const relativePath = path.posix.join(relativeDirectory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await listFiles(directory, relativePath)));
    } else if (entry.isFile() && relativePath !== deploymentFilename) {
      files.push(relativePath);
    }
  }
  return files.sort();
}

async function fingerprintBuild(distDirectory) {
  const hash = createHash("sha256");
  for (const relativePath of await listFiles(distDirectory)) {
    hash.update(relativePath);
    hash.update("\0");
    hash.update(await readFile(path.join(distDirectory, relativePath)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

export async function prepareIndexNowArtifacts({
  distDirectory,
  privateDirectory,
  sitemap,
  siteUrl,
}) {
  const canonicalUrl = new URL(siteUrl);
  if (canonicalUrl.protocol !== "https:" || canonicalUrl.href !== `${canonicalUrl.origin}/`) {
    throw new Error("The canonical IndexNow site URL must be an HTTPS origin.");
  }

  const urls = extractIndexNowUrls(sitemap, canonicalUrl.href);
  const keyFile = await findIndexNowKey(distDirectory);
  const fingerprint = await fingerprintBuild(distDirectory);
  const keyLocation = new URL(keyFile.filename, canonicalUrl).href;
  const payload = {
    host: canonicalUrl.host,
    key: keyFile.key,
    keyLocation,
    urlList: urls,
  };

  await writeFile(path.join(distDirectory, deploymentFilename), `${fingerprint}\n`);
  await rm(privateDirectory, { recursive: true, force: true });
  await mkdir(privateDirectory, { recursive: true });
  await writeFile(
    path.join(privateDirectory, "payload.json"),
    `${JSON.stringify(payload, null, 2)}\n`,
  );
  await writeFile(path.join(privateDirectory, "fingerprint"), `${fingerprint}\n`);

  return { fingerprint, keyLocation, urlCount: urls.length };
}
