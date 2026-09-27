category: fix
issue: none
pull: 438
platforms: android
user-facing: yes

Account removal now waits for folder-sync schedule restoration to finish authenticated discovery. Permanent restoration failures stop automatic retries, and temporary failures use a bounded retry budget while preserving configured pairs.
