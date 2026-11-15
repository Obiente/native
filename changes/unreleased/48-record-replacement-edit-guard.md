category: fix
issue: 48
pull: none
platforms: android, desktop
user-facing: yes

Saving a whole-record edit first re-reads the record and stops with a refresh option if it changed elsewhere; values you did not touch are sent back exactly as stored.
