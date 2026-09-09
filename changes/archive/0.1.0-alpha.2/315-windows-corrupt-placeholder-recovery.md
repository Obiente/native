category: fix
issue: 315
pull: 320
platforms: windows
user-facing: yes

Windows filesync now preserves the existing root and rebuilds File Explorer integration when corrupt Cloud Files metadata would otherwise block activation, without deleting local data.
