category: fix
issue: 52
pull: 439
platforms: android
user-facing: yes

Restore queued attachment uploads after interrupted Android scheduling. Cleanup retries yield to failed-worker deadlines, and malformed grant ownership is quarantined without blocking other uploads or deleting retained grants.
