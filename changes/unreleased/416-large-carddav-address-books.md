category: fix
issue: 416
pull: 418
platforms: android, desktop
user-facing: yes

Contacts now load large address books in bounded batches instead of failing when one server response exceeds the size limit.
