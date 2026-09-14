category: fix
issue: 172
pull: 436
platforms: desktop
user-facing: yes

Record successful desktop credential rollback before deleting its recovery secret, so interrupted cleanup can retry without locking accounts out.
