package dev.obiente.nextcloudnative.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient

/** Prepares private streaming clients without copying session cookies into media metadata. */
internal suspend fun prepareNativeAudioReads(
    session: NextcloudSession,
    sources: List<NativeAudioPlaybackSource>,
    client: OkHttpClient,
    sessions: JvmAuthenticatedAppReadSessions = sharedJvmAuthenticatedAppReadSessions,
    gate: AccountPrivateMemoryGate = sharedAccountPrivateMemoryGate,
): Call.Factory {
    require(sources.isNotEmpty())
    val producer = gate.producer(session.accountId.storageKey)
        ?: throw CancellationException("The account is no longer active.")
    val policy = NextcloudAuthenticatedRequestPolicy(session, "nati.ve")
    val targets = sources.associateBy { source ->
        policy.requestBuilder(nativeAudioPlaybackUrl(session, source)).build().url
    }
    val appSource = sources.firstOrNull { it.isApplicationAudio() }
    val appSession = appSource?.let { source ->
        sessions.prepareRenewableRead(session, NextcloudApiRequest(NextcloudApiMethod.GET, source.relativePath), client)
    }
    val appClient = appSession?.client()
    currentCoroutineContext().ensureActive()
    if (!gate.read(producer, false) { true }) throw CancellationException("The account changed during audio preparation.")
    return Call.Factory { request ->
        val source = targets[request.url] ?: throw IOException("The audio request is outside the selected source.")
        if (request.method != "GET" || request.body != null) throw IOException("The audio request is outside the selected source.")
        val appRead = source.isApplicationAudio()
        val prepared = if (appRead) requireNotNull(appClient) else client
        prepared.newBuilder().followRedirects(false).followSslRedirects(false)
            .apply { interceptors().add(0, okhttp3.Interceptor { chain ->
                if (!gate.read(producer, false) { true }) throw IOException("The audio account is no longer active.")
                val current = chain.request()
                if (current.method != "GET" || current.body != null || current.url != request.url) {
                    throw IOException("The audio request is outside the selected source.")
                }
                try {
                    if (appRead) renewAudioReadSession(chain.call(), requireNotNull(appSession))
                    if (!gate.read(producer, false) { true }) throw IOException("The audio account is no longer active.")
                    val authorization = policy.requestBuilder(current.url.toString()).build().header("Authorization")!!
                    chain.proceed(current.newBuilder().removeHeader("Cookie")
                        .header("Authorization", authorization).header("User-Agent", "nati.ve").build())
                } catch (cancelled: CancellationException) {
                    throw IOException("The audio account is no longer active.", cancelled)
                }
            }) }
            .build().newCall(request)
    }
}

private fun NativeAudioPlaybackSource.isApplicationAudio(): Boolean =
    relativePath.startsWith("/apps/") || relativePath.startsWith("/index.php/apps/")

/** Runs only inside OkHttp's stream worker; the native call owns cancellation and timeout. */
private fun renewAudioReadSession(call: Call, session: JvmAppReadSessionLease) {
    val preparation = Job()
    call.addEventListener(object : EventListener() {
        override fun canceled(call: Call) { preparation.cancel() }
    })
    if (call.isCanceled()) preparation.cancel()
    try {
        runBlocking(preparation) { session.client() }
    } finally {
        preparation.complete()
    }
}
