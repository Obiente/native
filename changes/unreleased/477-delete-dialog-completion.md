category: fix
issue: 477
pull: none
platforms: android, desktop
user-facing: yes

Files deletes always finish, even after leaving Files. Other changes to the item wait until it is verified or refreshed, and renames, moves, copies, and deletes report incomplete local file status.
