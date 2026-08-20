# AI assistance policy

Nextcloud Native welcomes responsible use of AI-assisted development tools.
The project does not accept autonomous AI contributions.

**Last reviewed: 2026-08-20.** Project governance may have changed. The
[default-branch policy](https://github.com/Obiente/nc-native/blob/main/AI_POLICY.md)
is the source of truth for contribution requirements.

The idea, purpose, judgment, passion, and responsibility behind a contribution
must come from a human. AI can help a person express or implement their work,
but it cannot replace the contributor.

## Human-led contributions

Every contribution must have an identifiable human contributor who:

- originates or consciously adopts the idea and intended outcome;
- defines the scope and gives the AI concrete guidance;
- makes the product, architecture, safety, and community decisions;
- reviews every changed line and every public message before submission;
- understands the implementation well enough to explain, defend, debug, and
  modify it;
- verifies the tests, dependencies, security implications, licensing, and
  user-visible behavior;
- accepts legal and moral responsibility for the submitted work.

"The AI wrote it" is not an acceptable explanation for a design or
implementation decision.

## No autonomous agent contributions

An AI agent must not independently:

- search the issue tracker for work to claim;
- choose product priorities or expand the approved scope;
- create or edit issues, pull requests, roadmaps, releases, or project state;
- write or publish code, documentation, tests, reviews, or community messages;
- merge, deploy, release, or rewrite repository history;
- submit security reports or contact people on the contributor's behalf.

AI assistance is allowed only within a concrete human-directed task. A
coordinating tool may divide that task into bounded subtasks, but it may not
turn a human request into an open-ended autonomous development mandate.

Repository automation that performs deterministic checks, dependency updates,
formatting, or release mechanics under maintainer-controlled configuration is
not treated as an autonomous contribution. A human still reviews and accepts
the resulting change.

## Human responsibility and truthful attribution

The human contributor is the author of the contribution, including when an AI
tool helped prepare it.

- AI tools must not be listed in `Co-authored-by` trailers.
- An AI tool must never fabricate or independently add a person's authorship,
  approval, signature, or certification trailer.
- Existing authorship from other contributors must be preserved.

## Optional disclosure

Disclosure is appreciated because it helps reviewers understand the development
process, but it is not mandatory and omission alone is not grounds for rejecting
a contribution.

A contributor may describe the assistance briefly in the pull request:

```text
AI assistance: Codex helped draft focused tests and identify edge cases. I
reviewed and revised the implementation and ran the listed validation myself.
```

For commit-level traceability, an optional trailer may be used:

```text
Assisted-by: Codex
```

Do not disclose prompts, private account data, credentials, unpublished
security information, or other sensitive context.

## Quality, security, and licensing

AI-assisted work has the same quality bar as any other contribution:

- focused scope and maintainable code;
- tests that demonstrate the claimed behavior;
- manual verification of new dependencies, authentication, authorization,
  destructive actions, retries, and data-loss risks;
- compatible licensing and no copied material of uncertain provenance;
- removal of dead code, prompt artifacts, redundant comments, generated noise,
  and unrelated changes;
- privacy-safe fixtures, logs, screenshots, and public communication.

AI output is a draft until a human has reviewed and accepted it.

## Maintainer judgment

Maintainers may ask a contributor to explain, change, split, or withdraw work
that is difficult to review or appears insufficiently understood. The purpose
of this policy is not to police tools. It is to preserve human creativity,
accountability, trust, and meaningful participation in the project.
