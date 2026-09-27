category: fix
issue: 484
pull: none
platforms: android, desktop, windows
user-facing: yes

Windows virtual files startup checks resident entries without recursively loading unopened remote folders. Storage status explains slow checks, preserves the previous snapshot after a failed refresh, and offers a retry.
