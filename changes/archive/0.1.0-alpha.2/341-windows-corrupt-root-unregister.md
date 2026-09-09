category: fix
issue: 341
pull: 342
platforms: windows
user-facing: yes

Windows filesync can now safely unregister an exact branded root during corrupt-metadata recovery without probing the unavailable Cloud Files directory first.
