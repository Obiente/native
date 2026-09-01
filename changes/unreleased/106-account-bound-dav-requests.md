category: fix
issue: 106
pull: none
platforms: android, desktop
user-facing: yes

Keep Android DocumentsProvider and desktop Files credentials inside the configured Nextcloud account origin and base path. Follow only bounded, method-preserving DAV redirects whose targets pass the same account checks.
