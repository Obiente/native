category: fix
issue: 286
pull: 325
platforms: linux
user-facing: yes

Linux tray menus now provide activity, app, and quit controls, and quitting cleanly releases the virtual-files mount without leaving the background service stuck.
