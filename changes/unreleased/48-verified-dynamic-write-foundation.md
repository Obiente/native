category: feature
issue: 48
pull: none
platforms: android, desktop
user-facing: yes

Changes to apps opened natively are now checked against what the app reports afterwards: table rows and records inside a parent keep every untouched value exactly as stored, are read back after saving, and pause further changes on this device when the app did not do what it reported, with a way to resume them under App compatibility details. Mailbox sending records each attempt before it is sent and never repeats it without your confirmation.
