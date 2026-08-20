# Obiente Native Bridge

`obiente_native_bridge` is an optional server app for independent Obiente
native clients. Its first capability is deliberately small: an authenticated
user can mint the secondary key that Recognize 11+ requires on its DAV people
API.

It does not proxy files, query Recognize's private database tables, bypass DAV
permissions, store account passwords, or add an administrator endpoint.

**Last reviewed: 2026-08-20.** Server and Recognize compatibility may have
changed. The checked-in [`appinfo/info.xml`](appinfo/info.xml), capability
response, and automated checks are the current source of truth.

## Compatibility boundary

- Nextcloud 33 through 35
- PHP 8.2 through 8.5
- Recognize 11.0.0 or newer, while its public
  `OCA\Recognize\Public\ApiKeyManager` API remains available

Recognize 10 and older did not require this secondary header and did not expose
the public key manager. The bridge reports that combination as unavailable.
The app intentionally checks the dependency at runtime because the current
Nextcloud `info.xml` schema has no installed-app dependency element.

The boundary is based on the official implementation:

- [Recognize public ApiKeyManager](https://github.com/nextcloud/recognize/blob/main/lib/Public/ApiKeyManager.php)
- [Recognize DAV key validation and 24-hour timeout](https://github.com/nextcloud/recognize/blob/main/lib/Dav/Faces/PropFindPlugin.php)
- [Memories' supported use of ApiKeyManager](https://github.com/pulsejet/memories/blob/master/lib/Controller/PageController.php)
- [Nextcloud OCS API authentication and CSRF behavior](https://docs.nextcloud.com/server/latest/developer_manual/digging_deeper/rest_apis.html)

## Discovery

The standard authenticated capabilities response contains:

```json
{
  "obiente_native_bridge": {
    "api_version": 1,
    "recognize": {
      "available": true,
      "reason": null,
      "recognize_version": "12.0.0",
      "minimum_recognize_version": "11.0.0",
      "token_endpoint": "/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token",
      "method": "POST",
      "ocs_api_request_required": true,
      "dav_header": "X-Recognize-Api-Key",
      "expires_in": 86400
    }
  }
}
```

No key is included in capabilities.

## Token endpoint

```text
POST /ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token
OCS-APIRequest: true
Authorization: Basic <username and app password>
Accept: application/json
```

Example with placeholders only:

```bash
curl --fail-with-body \
  -u 'USERNAME:APP_PASSWORD' \
  -H 'OCS-APIRequest: true' \
  -H 'Accept: application/json' \
  -X POST \
  'https://cloud.example/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token?format=json'
```

The response returns `token`, `header_name`, `expires_in`, `expires_at`, and
`recognize_version` inside the normal OCS response envelope. Send the token as
`X-Recognize-Api-Key` only to the same server's
`/remote.php/dav/recognize/` collection, together with normal account
authentication.

## Authentication and CSRF rules

- The controller is not a public page. Normal Nextcloud authentication is
  mandatory and an explicit null-user guard is kept as defense in depth.
- Regular users are allowed because Recognize's DAV tree is per user. Admin
  rights are not requested.
- This is an OCS route and the client must send `OCS-APIRequest: true`.
- The controller deliberately has no `NoCSRFRequired` attribute and enables no
  CORS. It uses the framework's OCS request handling instead of weakening CSRF
  checks.
- The route is POST-only and accepts no URL, query, or body parameters.

## Security model

Recognize generates an opaque, encrypted, instance-scoped key. It is not bound
to a particular user, but it is only a secondary gate: Recognize still runs
inside the authenticated DAV request and its collections enforce the current
user's ownership. A key alone is not an account credential.

This bridge follows the same public API that Memories uses. It does not create
its own token format and does not call Recognize's private mappers. A narrower
per-user token would require an upstream Recognize protocol change; pretending
to add that restriction in this app would not be enforced by Recognize.

The bridge:

- never stores a generated key;
- sets `Cache-Control: no-store, private`, `Pragma: no-cache`, and
  `Referrer-Policy: no-referrer` on every endpoint response;
- never writes a key or response body to logs;
- returns a fixed expiry matching Recognize's 24-hour validation window;
- returns a typed unavailable result when Recognize is disabled, too old, or
  missing the public manager.

Clients should keep the key in protected memory or their platform credential
store, scope it to the exact server origin, avoid analytics and crash logs,
refresh before expiry, discard it on logout, and retry once with a newly minted
key after a Recognize DAV 403 response.

## Local validation

From this directory:

```bash
./tools/check.sh
```

The check lints every PHP file, validates `info.xml`, exercises the pure version
policy, and verifies the route, OpenAPI, authentication, and no-store security
contract. It does not install the app or contact a server.

For a manual development install, copy or symlink the directory with the exact
folder name `obiente_native_bridge` into a test server's custom apps directory,
then run the server's normal `occ app:enable obiente_native_bridge` command.
Do not validate it first on a production instance.
