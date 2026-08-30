# ADR 0001: document-only Android Office integration

Status: accepted

## Context

Nextcloud instances can expose different document suites and MIME support. Loading an Office dashboard URL also exposes the surrounding Nextcloud web navigation. That conflicts with the native client: file selection and navigation belong in NC Native.

OOXML/ODF collaborative editing requires an Office engine and WOPI lifecycle that the client does not implement. A server thumbnail is a preview, not a complete viewer or editor.

## Decision

Office app entries open a native document browser. The browser reads DAV folders and core Direct Editing capabilities; opening it does not create an editing token or open an app dashboard. Cached listings remain visible but must be confirmed online before selecting a document.

File-listing confirmation is independent of editor discovery. A failed or unsupported Direct Editing capability request does not disable native previews for a successfully loaded folder. Document types with native preview support remain listed without an advertised editor. Editor eligibility remains a separate check in the document workflow.

Preview and Edit are separate actions. Editing is gated by a secure advertised editor for the exact MIME type, file identity, version, and write permission. Eligibility is not restricted to a hard-coded Office format list: PDFs and other advertised formats can have editing choices too. A failed thumbnail must not hide those choices.

Every Edit and editor Retry resolves the stable file ID through DAV immediately before session creation. The current writable file must produce the exact reviewed request, including its ETag and parent path. Changed or unavailable sources cannot create a token; a changed source withholds Edit until the preview is closed and the folder refreshed. This is a preflight check, not an atomic ETag guard: Direct Editing does not accept `If-Match`.

The response validator and embedded navigator share one token policy: 1-1,024 ASCII letters, digits, hyphens, or underscores. Malformed, encoded, and oversized tokens fail at session creation rather than during UI composition.

Android embeds only the validated one-time Direct Editing URL after explicit selection. It never sends account credentials into the WebView. Desktop keeps the system-browser editing handoff.

The document response is supplied by the server's Direct Editing integration. For example, [Nextcloud Office renders its document template with the base layout](https://github.com/nextcloud/richdocuments/blob/main/lib/Controller/DocumentTrait.php), without the normal dashboard. NC Native does not scrape or cosmetically hide Nextcloud navigation.

The top-level URL is fixed to the selected session. Navigation to another document, a dashboard, settings, login, another origin, or an external app is rejected. Popups and clicked subframe navigation are rejected. Non-interactive provider iframe bootstrap is allowed under the server page's CSP and WebView mixed-content policy.

Android checks both navigation and main-frame resource requests, with page-start and page-commit guards for paths not covered by navigation interception. [Android documents that navigation interception alone does not cover POST requests](https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20android.webkit.WebResourceRequest)). A blocked link leaves the editor in place where possible; otherwise the user can return to native file selection or request a fresh session.

Back returns to the native preview or document browser, not WebView history. Cookies, cache, and web storage are cleared between sessions. File/content URL access, HTTP auth challenges, and mixed content remain disabled. A TLS exception must match the exact certificate already approved for that server; hostname, validity, and other certificate errors remain blocked.

Retry requests a new editing session because [Nextcloud consumes the initial token](https://github.com/nextcloud/server/blob/stable34/lib/private/DirectEditing/Manager.php#L167-L186). It must not reload a consumed token.

## Compatibility and limits

- Registry support and deterministic tests do not prove live editor/save compatibility for every suite.
- Providers that redirect the top-level document to a different location, including federated editor redirects, are blocked until a document-scoped handoff contract is implemented.
- Read-only files keep their native preview; they do not acquire write permission from editor availability.
- Native-capable functionality stays native. A separate general Web version entry with links back into native screens is outside this Office change.
- Real-server save, lifecycle, IME, accessibility, and provider compatibility still require platform testing.
