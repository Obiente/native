category: fix
issue: none
pull: 438
platforms: android
user-facing: yes

Pause queued uploads when a damaged account registry has no recoverable credential aggregate, preserving the queue for explicit account recovery instead of endless timed retries.
