category: fix
issue: 315
pull: none
platforms: windows
user-facing: yes

Windows filesync now recovers when Explorer creates the same placeholder concurrently, while preserving ordinary local entries for safe recovery.
