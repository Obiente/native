category: fix
issue: 11
pull: none
platforms: linux, windows
user-facing: yes

Desktop folder sync now handles larger trees with indexed state and lighter virtual-file cache writes. Linux and Windows register start-on-login only after it is explicitly enabled; Linux then starts its supervised tray session.
