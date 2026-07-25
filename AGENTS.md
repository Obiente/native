# Nextcloud Native contributor guidance

## Code Review Rules

### Remote safety and privacy

- Flag any audit, demo, preview, or test path that can mutate a real Nextcloud account. Read-only verification must not call mutation services, and public artifacts must never contain credentials, server URLs, account identifiers, personal content, or machine-specific paths.

### Resource identity and action binding

- Flag dynamic routes or actions that substitute a child item ID for a parent, project, board, table, conversation, or container ID, or that leave required path parameters unresolved. Resolve identifiers from typed resource relationships and the active navigation context, and disable writes when identity data is stale or ambiguous.

### Cross-platform state and media correctness

- Flag navigation, selection, editor, or transfer state that exists only in a transient composable when it must survive Android rotation, process restoration, or desktop resizing. Flag image paths that apply EXIF orientation to already-oriented server previews or fail to apply it exactly once to original bytes, zoom, edits, and exports.
