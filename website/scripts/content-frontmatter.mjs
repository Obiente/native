const requiredNewsMetadata = [
  "title",
  "slug",
  "date",
  "lastUpdated",
  "description",
  "tags",
  "captureScenario",
  "imageAlt",
  "imageCaption",
];

export function parseNewsFrontmatter(source, file) {
  const match = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/);
  if (!match) throw new Error(`${file} must start with YAML-like frontmatter.`);
  const metadata = Object.fromEntries(
    match[1].split(/\r?\n/).map((line) => {
      const separator = line.indexOf(":");
      if (separator <= 0) throw new Error(`${file} contains invalid frontmatter.`);
      return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
    }),
  );
  for (const key of requiredNewsMetadata) {
    if (!metadata[key]) throw new Error(`${file} is missing ${key} frontmatter.`);
  }
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(metadata.date) ||
    !/^\d{4}-\d{2}-\d{2}$/.test(metadata.lastUpdated) ||
    metadata.lastUpdated < metadata.date ||
    !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(metadata.slug)
  ) {
    throw new Error(`${file} has an invalid date or slug.`);
  }
  if (
    !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(metadata.captureScenario) ||
    metadata.imageAlt.length > 240 ||
    metadata.imageCaption.length > 240
  ) {
    throw new Error(`${file} has invalid screenshot metadata.`);
  }
  return { metadata, body: match[2] };
}

export function normalizeNewsArticleBody(body, title) {
  const withoutLeadingBlankLines = body.replace(/^(?:[ \t]*\r?\n)+/u, "");
  const heading = withoutLeadingBlankLines.match(/^#\s+([^\r\n]+)\r?\n/u);
  if (!heading || heading[1].trim() !== title.trim()) return withoutLeadingBlankLines;
  return withoutLeadingBlankLines.slice(heading[0].length).replace(/^(?:[ \t]*\r?\n)+/u, "");
}
