category: fix
issue: none
pull: 436
platforms: android, desktop
user-facing: yes

Keep replacement Deck drafts from being cleared by an older submitted-draft marker after migration. Explicitly discarding an unreadable legacy draft now clears only that account's selected draft without requiring its encryption key.
