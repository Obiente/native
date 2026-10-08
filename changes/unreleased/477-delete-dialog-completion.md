category: fix
issue: 477
pull: none
platforms: android, desktop
user-facing: yes

Files deletes always finish: stalled local virtual-file updates no longer hold the dialog, interrupted responses are checked on the server, and stale, throttled, or still-running deletes are never resent.
