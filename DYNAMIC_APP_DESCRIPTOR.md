# Dynamic App Descriptor 1.0

`DynamicAppDescriptor` is the machine-readable boundary between API discovery,
native presentation and HTTP execution. Its canonical Rust model is
`src/dynamic.rs`; the serializable Compose Multiplatform mirror is
`ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicAppDescriptor.kt`.
Both serialize camel-case JSON with `descriptorVersion: "1.0"`.

## Contract

The top-level document contains:

- exact app identity and an endpoint policy containing the server origin and
  approved API path prefixes;
- capability facts and permission requirements with provenance and confidence;
- typed resources and fields;
- explicit list, detail or grid layouts;
- field links, where inferred URLs default to `allowExternal: false`;
- forms bound to explicit mutating actions;
- actions containing method, path, path/query parameters, body media type and
  schema, authentication requirements and OCS envelope metadata;
- warnings describing intentionally omitted or degraded behavior.

Every descriptor must pass `DynamicAppDescriptor::validate()` after compilation
and again after deserialization. Kotlin consumers use `requireValid()` for the
same trust-boundary checks. Validation rejects unsupported versions, duplicate
or dangling references, path-parameter mismatches, absolute/cross-origin paths,
paths outside the approved app prefixes and writes without advertised OpenAPI
or verified-adapter provenance.

## Discovery inputs

`DynamicDescriptorCompiler` accepts only normalized facts in
`DynamicDiscoveryInput`:

1. An OpenAPI 3.x document that the connected app advertised. The fetching
   layer supplies the document and its advertised same-origin URL. The compiler
   never searches arbitrary URLs.
2. An OpenAPI 3.x document extracted from the exact installed release in the
   official Nextcloud App Store. This source is accepted only after local
   certificate-chain, signed-CRL, app-ID, version and archive-signature
   verification. Its provenance is `verifiedAppPackage`.
3. If that verified package contains no OpenAPI file, an OpenAPI 3.x document
   from the exact GitHub release tag linked by the App Store entry. Repository
   identity and tag come only from the official catalog metadata and release
   URL; the tag's `appinfo/info.xml` must reproduce the selected app ID and
   exact version. This unsigned, lower-trust provenance is
   `appStoreLinkedSourceTag`. Every operation still has to stay inside the
   connected server's approved same-origin app endpoint prefixes.
4. Successful 2xx JSON GET observations from an already approved endpoint.
   Response-shape inference creates read-only fields, a read action and a
   list/detail layout. It never creates a form or write action.

OpenAPI is authoritative for explicit write operations. An unnamed OpenAPI
write is omitted; every declared mutation requires confirmation, and deletes
are marked destructive.

Fixtures under `tests/fixtures/` cover an advertised OCS/OpenAPI app and an
unknown app learned from a successful JSON list response.

## Current limits

- OpenAPI 2/Swagger, remote `$ref` values, templated server URLs and ambiguous
  multiple server bases are rejected.
- OpenAPI security alternatives are currently flattened into conservative
  authentication requirements; optional/alternative scheme selection is not
  modeled yet.
- JSON inference samples at most 64 objects and 128 fields. Empty collections
  remain fieldless until a non-empty successful response or schema is available.
- JSON examples cannot establish validation rules, pagination, sync semantics,
  ACL behavior or write payloads.
- GraphQL introspection, DAV XML schemas, HTML/accessibility inspection and
  JavaScript traffic instrumentation are not discovery sources yet.
- Inferred URL fields are display/copy metadata only. External navigation needs
  verified policy or an explicit user handoff.
- `toNativeAppSchema()` adapts the descriptor for the current renderer, but the
  executor must retain the original dynamic action because schema 0.1 cannot
  carry query, authentication, permission or OCS metadata.
- The common Kotlin compiler provides the same focused OpenAPI and read-shape
  paths for Android/desktop today. When both sources are supplied it currently
  prefers advertised OpenAPI instead of merging observed fields into it.
