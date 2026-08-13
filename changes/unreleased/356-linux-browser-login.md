category: fix
issue: 356
pull: 357
platforms: desktop, linux
user-facing: yes

Linux sign-in now falls back to the system URL opener when Java cannot browse. Failed handoffs remain retryable and appear in pre-login anonymized diagnostics without recording the login URL.
