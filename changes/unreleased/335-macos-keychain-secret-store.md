category: fix
issue: 335
pull: 430
platforms: macos
user-facing: yes

Store desktop login credentials and local Deck draft keys in the user's macOS Keychain, migrate existing Secret Service values, keep locked Keychain access safely retryable, and durably retry credential deletion after sign-out.
