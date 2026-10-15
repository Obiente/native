category: internal
issue: none
pull: none
platforms: desktop
user-facing: no

The desktop contacts mutation scene tests now wait for the rendered contact list and the reconciled end state instead of a fixed number of frames, so a slow machine no longer fails them.
