category: fix
issue: none
pull: none
platforms: windows
user-facing: no

Recovered branded Windows shell registration when stale package ownership conflicts block updates, so registration and unregistration paths continue to succeed without leaving a stuck provider state.
