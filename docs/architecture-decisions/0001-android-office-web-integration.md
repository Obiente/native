# ADR 0001: Android Office web integration

Status: accepted

## Context

Nextcloud servers can expose different document suites. Nextcloud Office normally advertises the `richdocuments` editor, while another instance can advertise ONLYOFFICE or another implementation through the core Direct Editing capability. Treating one app ID as universal hides valid editors and breaks instances that chose a different suite.

Native rendering remains the preferred implementation for formats whose read and write semantics the client owns. OOXML and ODF collaborative editing require a full Office engine and WOPI lifecycle, which the app does not implement. A first-page server preview is not a usable substitute for opening a PDF or editing a document.

## Decision

The client reads the core Direct Editing editor registry and offers every editor that is marked secure and advertises the file's exact MIME type. It does not infer a suite from a product name or hard-code `richdocuments` as the editor.

On Android, verified Office dashboards and explicit Direct Editing sessions can open in an embedded web surface. Desktop keeps the system-browser handoff. This is presented as a web Office integration, not native Office.

The Android surface has two authentication modes:

- A same-origin Office dashboard receives the active account authorization header only on its initial request.
- A one-time Direct Editing URL receives no app-password authorization header. The short-lived URL carries the server-issued editing session.

Both modes recreate the web surface when the account or URL changes, clear cookies before and after the session, reject cross-origin top-level navigation, disable file and content access, reject mixed content, cancel HTTP authentication challenges, and never persist or log Direct Editing URLs. A certificate exception is accepted only when it matches the exact certificate that the user already approved for that server; hostname, validity, and other TLS failures remain blocked.

Retrying an Office dashboard reloads its initial page. Retrying a Direct Editing failure requests a new session after the user taps Retry, because [Nextcloud marks the initial editing token as consumed](https://github.com/nextcloud/server/blob/stable34/lib/private/DirectEditing/Manager.php#L167-L186). Reloading that token would fail even after the connection recovered.

Files with a supported native implementation stay native. Files without a complete native viewer, including multipage PDFs until that viewer lands, also expose an explicit Android system-app chooser.

## Compatibility impact

- Editor selection accepts Nextcloud Office/Collabora, ONLYOFFICE, and other suites only when they advertise secure Direct Editing metadata for the file's MIME type through the server's core capability. Registry support does not prove live editing or save compatibility for a suite.
- Older servers without usable editor metadata remain read-only and explain why editing is unavailable.
- Office app navigation is web-backed only for a small verified app-ID allowlist and a same-origin advertised route. Other dynamic apps continue through native discovery and never fall back automatically to web content.
- Existing desktop behavior stays external and does not acquire an embedded browser dependency.

## Consequences

The Android app gains an embedded Office editing path without claiming a native engine. The integration requires origin, certificate, session-isolation, capability-selection, retry, and real-server save tests before claiming verified compatibility. If a supported native Office SDK later meets the roadmap's fidelity and collaboration gate, it can replace the web surface behind the same capability-driven editor choice.
