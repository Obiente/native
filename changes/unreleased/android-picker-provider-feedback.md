category: fix
issue: none
pull: 446
platforms: android
user-facing: yes

Android file and folder pickers now reject the app's own document provider so selected uploads and sync roots always come from an independent storage source.
