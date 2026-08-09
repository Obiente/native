category: fix
issue: 11
pull: none
platforms: linux
user-facing: yes

Linux folder sync now handles larger trees with transactional indexed state, reduces virtual-file cache metadata writes during active reads, and starts the supervised tray session only after start-on-login is enabled in settings.
