category: fix
issue: 335
pull: 430
platforms: macos
user-facing: yes

Store desktop login credentials and Deck draft keys in macOS Keychain, migrate Secret Service values, and keep locked access and sign-out cleanup retryable. Preserve encrypted Deck drafts instead of replacing a missing key.
