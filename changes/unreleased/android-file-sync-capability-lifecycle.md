category: fix
issue: 11
pull: 445
platforms: android
user-facing: yes

Android folder sync now tracks selected-folder access through setup, pairing, removal, and restart recovery so cancelled setup cannot leave access behind and removal retries a failed permission release.
