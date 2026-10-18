category: fix
issue: 477
pull: none
platforms: linux, windows
user-facing: yes

Creating folders and files, saving text, favorites, version restores, sync uploads, and Linux mount writes no longer wait for local file status after the server confirmed them, and incomplete local updates are reported.
