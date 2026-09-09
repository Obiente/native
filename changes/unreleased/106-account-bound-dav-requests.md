category: fix
issue: 106
pull: 428
platforms: android, desktop
user-facing: yes

Keep Android DocumentsProvider and desktop folder-sync credentials inside the configured Nextcloud account origin and base path. Follow only bounded, method-preserving DAV redirects whose targets pass the same account checks.
