category: fix
issue: 113
pull: none
platforms: android, desktop
user-facing: yes

Superseded resumable uploads now retain durable size, content hash, and publication state during cleanup, so an ambiguous server result cannot mistake an already published directory replacement for an abandoned stage.
