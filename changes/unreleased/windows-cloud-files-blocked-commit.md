category: fix
issue: none
pull: none
platforms: windows
user-facing: yes

Windows Cloud Files now keeps writing back edits to a file that another application had open during an earlier upload, and marks that file as synced once the application closes it.
