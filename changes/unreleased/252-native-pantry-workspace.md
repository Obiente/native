category: feature
issue: 252
pull: none
platforms: android, desktop
user-facing: yes

Dynamic collection apps now follow their declared parent and child relationships, preserve distinct parent-scoped and state-scoped routes, and open a container's active collection instead of falling back to raw record details or archived content. Empty and populated collections expose verified create actions, supported records offer edit and confirmed state-aware action menus, completion controls are reversible, destructive commands fail safe, and navigation adapts to the available space.

Contract-declared icons and colors are presented visually, real descriptions stay in the subtitle, enums use option menus, recurrence rules use a repeat control, booleans use switches, and linked identity fields use searchable single- or multi-select choices instead of exposing raw IDs. Relationship choices are preloaded from verified read routes while heuristic links remain presentation-only and cannot silently authorize writes. Exact request schemas govern editable fields, array constraints, validation, server-managed values, and mutation ownership.

Process restoration can reuse the exact-version verified contract cache for browsing while keeping every write disabled until the live server and app versions are verified again. This gives Pantry a native House, list, item, category, store, archive, restore, and trash foundation while keeping the behavior reusable for other compatible apps.
