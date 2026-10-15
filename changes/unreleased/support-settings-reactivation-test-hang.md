category: internal
issue: none
pull: none
platforms: desktop
user-facing: no

The desktop support-settings reactivation test now keeps offering frames while recomposition is pending and cancels its recomposer with bounded waits, so a missed frame fails the test instead of hanging the desktop test suite.
