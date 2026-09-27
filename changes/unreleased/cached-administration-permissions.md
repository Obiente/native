category: fix
issue: 187
pull: none
platforms: android, desktop
user-facing: yes

Hide Administration and Server apps unless the signed-in account has freshly verified access. Cache permission checks for five minutes within the session, and prevent stale responses or restored navigation from exposing admin controls.
