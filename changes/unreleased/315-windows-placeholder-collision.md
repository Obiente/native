category: fix
issue: 315
pull: none
platforms: windows
user-facing: yes

Windows filesync now preserves the existing root and rebuilds File Explorer integration when placeholder races or corrupt Cloud Files metadata would otherwise block activation, without deleting local data.
