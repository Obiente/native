category: fix
issue: none
pull: none
platforms: windows
user-facing: yes

Opening a cloud-only file in File Explorer now downloads it with one streamed request instead of one server request per 4 MiB, so large photos open about as fast as a normal download.
