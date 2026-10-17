# libVLC license texts

`COPYING` (GNU GPL 2) and `COPYING.LIB` (GNU LGPL 2.1) are copied unchanged
from the VLC `3.0.24` source tag. VideoLAN's Windows and macOS binary archives
do not include `COPYING.LIB`, so `ui/desktop-libvlc.gradle.kts` stages these
files next to the bundled libVLC in desktop packages.

Update both files from the matching source tag whenever the pinned libVLC
version changes.
