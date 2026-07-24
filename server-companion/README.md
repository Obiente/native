# Optional Obiente server companions

This directory contains independently installable server apps that add a
narrow capability where an upstream app does not expose a complete native
client API. Nextcloud Native must continue to work without these modules and
must discover each bridge through the normal capabilities response.

- [`obiente_native_bridge`](obiente_native_bridge/README.md) currently exposes
  an authenticated token-mint endpoint for Recognize's protected DAV people
  collection.

These are independent, unofficial Obiente components. They are not affiliated
with or endorsed by Nextcloud GmbH.
