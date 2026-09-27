category: fix
issue: none
pull: 439
platforms: android
user-facing: yes

New uploads whose background scheduling fails are recovered immediately even while an earlier cleanup pass is running. Repeated cleanup failures still use a bounded retry delay.
