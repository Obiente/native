category: security
issue: none
pull: 436
platforms: android, desktop
user-facing: yes

Account removal now stops older dynamic reads even when a newer request replaced them during refresh, preventing retries with retired credentials after sign-in.
