category: fix
issue: none
pull: 324
platforms: desktop, windows
user-facing: yes

Windows virtual files now serialize initial and on-demand placeholder population so concurrent File Explorer requests cannot abort activation with a name collision.
