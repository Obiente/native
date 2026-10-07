category: internal
issue: none
pull: none
platforms: all
user-facing: no

Repository checks report missing tools up front, scan files in batches, and skip the Debian package build check locally when dpkg-deb is unavailable while CI still requires it.
