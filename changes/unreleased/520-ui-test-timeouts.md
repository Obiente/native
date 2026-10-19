category: internal
issue: none
pull: 520
platforms: desktop
user-facing: no

Desktop test tasks now log the name of any test still running after 3 minutes and stop after 30 minutes, so a stuck test fails the build with its name instead of running until the CI job limit.
