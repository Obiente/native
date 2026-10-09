category: fix
issue: 488
pull: none
platforms: android, desktop
user-facing: yes

Keep browser sign-in working after returning to the app during approval: each check uses a new connection, and connection failures before a request is sent are retried. Cancelling sign-in stops a check in flight.
