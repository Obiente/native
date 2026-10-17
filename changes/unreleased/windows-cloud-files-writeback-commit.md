category: fix
issue: none
pull: 516
platforms: windows
user-facing: yes

Windows Cloud Files now finishes writing back edited files: an uploaded file is marked as synced instead of staying in the failed state, and an edit made during an upload is uploaded again instead of being marked as synced.
