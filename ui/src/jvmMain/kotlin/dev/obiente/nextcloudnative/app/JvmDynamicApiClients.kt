package dev.obiente.nextcloudnative.app

import okhttp3.OkHttpClient

/**
 * The HTTP clients a dynamic app request may use. Both keep redirects disabled. A read may let
 * OkHttp retry a stale pooled connection; a write never does, because OkHttp can retry after the
 * request body was already written and the server may have applied it. A write that fails on the
 * connection surfaces as an unknown result instead of being sent a second time.
 */
class JvmDynamicApiClients(noRedirectClient: OkHttpClient) {
    init {
        require(!noRedirectClient.followRedirects && !noRedirectClient.followSslRedirects) {
            "Dynamic app requests never follow redirects."
        }
    }

    /** Body-free reads. */
    val read: OkHttpClient = noRedirectClient

    /** Every non-GET request, including multipart uploads and exactly-once sends. */
    val write: OkHttpClient = noRedirectClient.newBuilder().retryOnConnectionFailure(false).build()

    fun forMethod(method: NextcloudApiMethod): OkHttpClient = if (method == NextcloudApiMethod.GET) read else write
}
