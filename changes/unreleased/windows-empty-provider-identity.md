category: fix
issue: 341
pull: none
platforms: windows
user-facing: yes

Windows Cloud Files recovery verifies the complete account root identity when a saved provider GUID is empty, avoiding a false foreign-registration rejection while retaining path safety checks.
