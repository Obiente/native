category: feature
issue: none
pull: none
platforms: android, desktop
user-facing: yes

Boards and notes now open from the installed app's own contract instead of fixed Deck and Notes routes, so any app shaped like a board or a notes collection, at any version, gets the native board or notes workspace, with each action offered only when that app's contract describes it. Label and assignee changes and board restore are offered when a signed package's own routes describe them, card moves keep the source list in the path, cleared optional card fields are saved as empty, and notes refreshes reuse the cached copy when the server reports no change.
