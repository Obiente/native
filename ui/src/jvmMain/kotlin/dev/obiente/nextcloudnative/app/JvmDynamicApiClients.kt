package dev.obiente.nextcloudnative.app

import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
    val write: OkHttpClient = noRedirectClient.newBuilder()
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            // Application interceptors run once per Call; follow-ups retain this request tag.
            chain.proceed(chain.request().newBuilder().tag(DynamicWriteAttempt::class.java, DynamicWriteAttempt()).build())
        }
        .addNetworkInterceptor { chain ->
            // Retry-After: 0 on a 503 can trigger a follow-up even with connection retries off.
            // Stop before a second exchange sends headers or a body, including body-free writes.
            val attempt = chain.request().tag(DynamicWriteAttempt::class.java)
            if (attempt == null || !attempt.started.compareAndSet(false, true)) {
                throw IOException("The dynamic write may have been applied; an automatic repeat was blocked.")
            }
            chain.proceed(chain.request())
        }
        .build()

    fun forMethod(method: NextcloudApiMethod): OkHttpClient = if (method == NextcloudApiMethod.GET) read else write
}

private class DynamicWriteAttempt(val started: AtomicBoolean = AtomicBoolean(false))
