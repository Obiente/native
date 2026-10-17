category: fix
issue: none
pull: none
platforms: windows
user-facing: yes

Windows Cloud Files no longer times out while starting on very large photo libraries, because the startup scan reuses folder listing data instead of checking every file again.
