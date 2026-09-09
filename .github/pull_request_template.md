## Outcome

Describe the user-facing or maintainer-facing result. State explicitly when the
change has no user-visible effect.

## Verification

- [ ] Every check relevant to the changed scope passes, or each unrun check is
      listed below with a reason
- [ ] `bash tools/check-repository.sh` passes
- [ ] A new `changes/unreleased/` fragment records the change
- [ ] No credentials, private server data, machine-local paths, or generated
      output are included

List the exact tests, builds, package checks, devices, and server versions used.
Do not mark an unrelated platform check as passing. See
[`CONTRIBUTING.md`](../CONTRIBUTING.md) for the current baseline and
platform-specific guidance.

## Compatibility and risk

List the tested platforms, Nextcloud server/app versions, permission-sensitive
operations, migrations, and remaining limitations.

## Visual changes

Add screenshots for each affected form factor, or write "Not applicable."
