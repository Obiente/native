import { createHash } from "node:crypto";

export const NATIVE_NEWS_FEED_LIMITS = Object.freeze({
  maximumBytes: 512 * 1024,
  maximumEntries: 100,
  maximumTitleLength: 160,
  maximumDescriptionLength: 320,
  maximumTagCount: 12,
  maximumTagLength: 48,
  maximumBodyLength: 64 * 1024,
});

const sha256Pattern = /^[a-f0-9]{64}$/;
const articleIdPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const datePattern = /^\d{4}-\d{2}-\d{2}$/;
const isoControlPattern = /[\u0000-\u001f\u007f-\u009f]/u;

function isBoundedPublicText(value, maximumLength) {
  return (
    typeof value === "string" &&
    value.trim().length > 0 &&
    value.length <= maximumLength &&
    !isoControlPattern.test(value)
  );
}

function isBoundedMarkdown(value, maximumLength) {
  return (
    typeof value === "string" &&
    value.trim().length > 0 &&
    value.length <= maximumLength &&
    !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(value)
  );
}

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

export function assertValidNativeNewsArticle(article) {
  const limits = NATIVE_NEWS_FEED_LIMITS;
  requireCondition(articleIdPattern.test(article.id), "News article id is invalid.");
  requireCondition(
    isBoundedPublicText(article.title, limits.maximumTitleLength),
    `News article title must contain 1 to ${limits.maximumTitleLength} non-control characters.`,
  );
  requireCondition(
    isBoundedPublicText(article.description, limits.maximumDescriptionLength),
    `News article description must contain 1 to ${limits.maximumDescriptionLength} non-control characters.`,
  );
  requireCondition(datePattern.test(article.publishedDate), "News article published date is invalid.");
  if (article.lastUpdated != null) {
    requireCondition(datePattern.test(article.lastUpdated), "News article updated date is invalid.");
    requireCondition(
      article.lastUpdated >= article.publishedDate,
      "News article updated date cannot precede its published date.",
    );
  }
  requireCondition(Array.isArray(article.tags), "News article tags must be an array.");
  requireCondition(
    article.tags.length <= limits.maximumTagCount,
    `News article tags cannot exceed ${limits.maximumTagCount} entries.`,
  );
  requireCondition(
    article.tags.every((tag) => isBoundedPublicText(tag, limits.maximumTagLength)),
    `Each news article tag must contain 1 to ${limits.maximumTagLength} non-control characters.`,
  );
  requireCondition(
    isBoundedMarkdown(article.bodyMarkdown, limits.maximumBodyLength),
    `News article body must contain 1 to ${limits.maximumBodyLength} non-control characters.`,
  );
  requireCondition(
    article.webUrl === `https://nc-native.obiente.dev/news/${article.id}/`,
    "News article URL is not canonical.",
  );
  requireCondition(sha256Pattern.test(article.contentSha256), "News article content hash is invalid.");
  requireCondition(
    article.contentSha256 === createHash("sha256").update(article.bodyMarkdown).digest("hex"),
    "News article content hash does not match its Markdown body.",
  );
}

export function assertValidNativeNewsFeed(feed, serializedBytes) {
  const limits = NATIVE_NEWS_FEED_LIMITS;
  requireCondition(feed.schemaVersion === 1, "Native news feed schema version must be 1.");
  requireCondition(sha256Pattern.test(feed.feedRevision), "Native news feed revision is invalid.");
  requireCondition(
    Array.isArray(feed.entries) &&
      feed.entries.length > 0 &&
      feed.entries.length <= limits.maximumEntries,
    `Native news feed must contain 1 to ${limits.maximumEntries} entries.`,
  );
  const ids = new Set();
  for (const article of feed.entries) {
    assertValidNativeNewsArticle(article);
    requireCondition(!ids.has(article.id), `Duplicate news article id: ${article.id}.`);
    ids.add(article.id);
  }
  requireCondition(
    feed.entries.every(
      (article, index) =>
        index === feed.entries.length - 1 ||
        article.publishedDate >= feed.entries[index + 1].publishedDate,
    ),
    "Native news feed entries must be sorted by descending published date.",
  );
  if (serializedBytes != null) {
    requireCondition(
      serializedBytes > 0 && serializedBytes <= limits.maximumBytes,
      `Native news feed must contain 1 to ${limits.maximumBytes} bytes.`,
    );
  }
}
