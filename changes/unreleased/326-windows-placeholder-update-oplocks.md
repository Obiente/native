category: fix
issue: none
pull: 326
platforms: desktop, windows
user-facing: yes

Windows virtual files now use Cloud Files oplocks while updating existing placeholders so File Explorer access cannot interrupt activation with a handle-sharing race.
