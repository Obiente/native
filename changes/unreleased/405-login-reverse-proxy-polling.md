category: fix
issue: 405
pull: none
platforms: android, desktop
user-facing: yes

Keep browser sign-in working through reverse proxies that require the entered server's explicit `index.php` Login Flow endpoint. Reject server addresses whose URL components would change the request.
