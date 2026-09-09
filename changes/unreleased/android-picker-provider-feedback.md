category: fix
issue: none
pull: 446
platforms: android
user-facing: yes

Android pickers now reject the app's own document provider, ensuring uploads and sync roots use independent storage. Legacy self-provider cleanup can read uncached replacement files without blocking account removal.
