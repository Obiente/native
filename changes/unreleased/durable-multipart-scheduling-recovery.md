category: fix
issue: 52
pull: 439
platforms: android
user-facing: yes

Restore queued attachment uploads after interrupted Android scheduling. Quarantine malformed grant ownership without repeated cleanup polling, allowing terminal uploads to finish and be dismissed while preserving retained grants.
