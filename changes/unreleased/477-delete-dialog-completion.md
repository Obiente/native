category: fix
issue: 477
pull: none
platforms: android, desktop
user-facing: yes

Files deletes always finish, even after leaving Files. Edits from Files, the media viewer, the text editor, and version history wait until a covering delete is verified or refreshed, and file changes report incomplete local status.
