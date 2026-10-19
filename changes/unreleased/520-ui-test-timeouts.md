category: internal
issue: none
pull: none
platforms: desktop
user-facing: no

Desktop libVLC discovery checks each candidate directory without walking its subdirectories, and desktop tests name any test running over 3 minutes and stop after 30, so a machine without libVLC no longer stalls the Linux test run.
