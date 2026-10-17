category: fix
issue: none
pull: 515
platforms: windows
user-facing: yes

Windows Cloud Files now uploads files that were edited in the nati.ve folder while the app was not running the next time it starts, instead of skipping them. Such a file can still be reported as needing review after its upload.
