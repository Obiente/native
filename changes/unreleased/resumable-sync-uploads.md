category: fix
issue: 113
pull: 423
platforms: android, desktop
user-facing: yes

Large folder-sync uploads now use Nextcloud chunking v2 on Android and desktop, persist exact-generation progress after every accepted chunk, and safely resume after an interrupted client process without skipping file bytes.
