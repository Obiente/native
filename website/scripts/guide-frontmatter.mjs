const requiredGuideMetadata = [
  "title",
  "slug",
  "description",
  "category",
  "platforms",
  "durationMinutes",
  "difficulty",
  "lastUpdated",
  "captureScenarios",
  "prerequisites",
];

const guideSlug = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const guideDate = /^\d{4}-\d{2}-\d{2}$/;
const guideDifficulty = new Set(["Getting started", "Everyday", "Advanced"]);

function commaSeparated(value) {
  return value.split(",").map((entry) => entry.trim()).filter(Boolean);
}

function stripLeadingTitle(body, title) {
  const withoutLeadingBlankLines = body.replace(/^(?:[ \t]*\r?\n)+/u, "");
  const heading = withoutLeadingBlankLines.match(/^#\s+([^\r\n]+)\r?\n/u);
  if (!heading || heading[1].trim() !== title.trim()) return withoutLeadingBlankLines;
  return withoutLeadingBlankLines.slice(heading[0].length).replace(/^(?:[ \t]*\r?\n)+/u, "");
}

function parseGuideSteps(body, captureScenarios, file) {
  const matches = [...body.matchAll(/^##\s+(.+)$/gmu)];
  if (matches.length === 0) throw new Error(`${file} must contain at least one guide step.`);
  if (matches.length !== captureScenarios.length) {
    throw new Error(
      `${file} has ${matches.length} steps but ${captureScenarios.length} capture scenarios.`,
    );
  }

  const introduction = body.slice(0, matches[0].index).trim();
  if (introduction.length < 80) throw new Error(`${file} needs a useful guide introduction.`);

  const steps = matches.map((match, index) => {
    const start = match.index + match[0].length;
    const end = matches[index + 1]?.index ?? body.length;
    const rawBody = body.slice(start, end).trim();
    const altMatch = rawBody.match(/^@capture-alt:\s+(.+)$/mu);
    const captionMatch = rawBody.match(/^@capture-caption:\s+(.+)$/mu);
    if (!altMatch || !captionMatch) {
      throw new Error(`${file} step ${index + 1} needs capture alt text and a caption.`);
    }
    const content = rawBody
      .replace(/^@capture-alt:\s+.+\r?\n?/mu, "")
      .replace(/^@capture-caption:\s+.+\r?\n?/mu, "")
      .trim();
    if (content.length < 120) throw new Error(`${file} step ${index + 1} is too short.`);
    return {
      number: index + 1,
      title: match[1].replace(/^\d+[.)]\s*/, "").trim(),
      source: content,
      captureScenario: captureScenarios[index],
      imageAlt: altMatch[1].trim(),
      imageCaption: captionMatch[1].trim(),
    };
  });

  return { introduction, steps };
}

export function parseGuideFrontmatter(source, file) {
  const match = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n([\s\S]*)$/u);
  if (!match) throw new Error(`${file} must start with YAML-like frontmatter.`);
  const metadata = Object.fromEntries(
    match[1].split(/\r?\n/u).map((line) => {
      const separator = line.indexOf(":");
      if (separator <= 0) throw new Error(`${file} contains invalid frontmatter.`);
      return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
    }),
  );
  for (const key of requiredGuideMetadata) {
    if (!metadata[key]) throw new Error(`${file} is missing ${key} frontmatter.`);
  }

  const durationMinutes = Number.parseInt(metadata.durationMinutes, 10);
  const platforms = commaSeparated(metadata.platforms);
  const captureScenarios = commaSeparated(metadata.captureScenarios);
  const prerequisites = commaSeparated(metadata.prerequisites);
  if (
    !guideSlug.test(metadata.slug) ||
    !guideDate.test(metadata.lastUpdated) ||
    !Number.isInteger(durationMinutes) ||
    durationMinutes < 1 ||
    durationMinutes > 60 ||
    !guideDifficulty.has(metadata.difficulty) ||
    platforms.length === 0 ||
    prerequisites.length === 0 ||
    captureScenarios.length === 0 ||
    captureScenarios.some((scenario) => !guideSlug.test(scenario))
  ) {
    throw new Error(`${file} has invalid guide metadata.`);
  }
  if (metadata.description.length < 60 || metadata.description.length > 220) {
    throw new Error(`${file} needs a concise, useful description.`);
  }

  const body = stripLeadingTitle(match[2], metadata.title);
  const { introduction, steps } = parseGuideSteps(body, captureScenarios, file);
  return {
    metadata: {
      ...metadata,
      durationMinutes,
      platforms,
      prerequisites,
      captureScenarios,
    },
    introduction,
    steps,
  };
}
