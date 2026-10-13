category: fix
issue: 477
pull: none
platforms: android, desktop
user-facing: yes

Files deletes always finish, even after leaving Files. Edits, folder pickers, sync setup, and Android share uploads wait until a covering delete is verified or refreshed, and file changes report incomplete local status.
