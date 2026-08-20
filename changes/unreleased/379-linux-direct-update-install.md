category: fix
issue: 379
pull: none
platforms: linux
user-facing: yes

Direct Linux updates now use DNF for RPM packages and APT for DEB packages through normal system authorization instead of relying on a failing PackageKit transaction.
