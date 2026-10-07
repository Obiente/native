category: fix
issue: 477
pull: none
platforms: android, desktop
user-facing: yes

Files deletes always finish: stalled local virtual-file updates no longer hold the dialog, and interrupted responses are checked on the server instead of retried.
