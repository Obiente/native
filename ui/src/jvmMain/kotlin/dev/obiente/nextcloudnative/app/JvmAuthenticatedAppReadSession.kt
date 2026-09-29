package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** HTTP application routes may initialize filesystem apps before Basic authentication runs. */
enum class JvmAppReadSessionFailureKind { Http, InvalidResponse, OversizedResponse }

class JvmAuthenticatedAppReadSessionException(
    val status: Int,
    val kind: JvmAppReadSessionFailureKind = JvmAppReadSessionFailureKind.Http,
) : IOException(
    when (status) {
        401 -> "Sign in again to load this content."
        403 -> "The server did not allow this account session."
        else -> "The server could not prepare this content. Try again in a moment."
    },
)

/** Account-owned, process-only cookies. This adapter never attaches cookies to mutations. */
class JvmAuthenticatedAppReadSessions internal constructor(
    private val now: () -> Long = System::currentTimeMillis,
    private val gate: AccountPrivateMemoryGate = sharedAccountPrivateMemoryGate,
) {
    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    suspend fun clientForRead(
        session: NextcloudSession,
        request: NextcloudApiRequest,
        client: OkHttpClient,
        recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit = {},
    ): OkHttpClient = clientForRead(session, request, client, recordDiagnostic, expectedEntry = null)

    /** Renewal may refresh this entry, but cannot recreate it after replacement or retirement. */
    internal suspend fun prepareRenewableRead(
        session: NextcloudSession,
        request: NextcloudApiRequest,
        client: OkHttpClient,
    ): JvmAppReadSessionLease {
        require(request.method == NextcloudApiMethod.GET &&
            (request.relativePath.startsWith("/apps/") || request.relativePath.startsWith("/index.php/apps/")))
        val entry = entryFor(session)
        clientForRead(session, request, client, {}, entry)
        return JvmAppReadSessionLease { clientForRead(session, request, client, {}, entry) }
    }

    private suspend fun clientForRead(
        session: NextcloudSession,
        request: NextcloudApiRequest,
        client: OkHttpClient,
        recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
        expectedEntry: Entry?,
    ): OkHttpClient {
        if (request.method != NextcloudApiMethod.GET ||
            !(request.relativePath.startsWith("/apps/") || request.relativePath.startsWith("/index.php/apps/"))) {
            return client
        }
        val target = buildNextcloudApiUrl(session.serverUrl, request)
        val policy = NextcloudAuthenticatedRequestPolicy(session, "nati.ve")
        policy.requestBuilder(target) // Validate origin, account subpath, and encoded traversal before bootstrap.
        val entry = entryFor(session, expectedEntry)
        entry.bootstrap.withLock {
            currentCoroutineContext().ensureActive()
            if (!current(entry, false) { true }) throw CancellationException("The account session is no longer active.")
            if (current(entry, false) { it.validUntil > now() }) return@withLock
            val bootstrap = policy.requestBuilder(session.serverUrl.trimEnd('/') + "/ocs/v2.php/cloud/user?format=json")
                .header("OCS-APIRequest", "true").header("Accept", "application/json").get().build()
            val prepared = try { executeCancellableNextcloudAuthenticatedRequest(
                client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build(),
                initialRequest = bootstrap,
                onNetworkFailure = {},
            ) { response, active ->
                if (!active()) throw CancellationException("Account session preparation was cancelled.")
                when (response.code) {
                    404, 405, 501 -> Prepared(emptyList(), now() + UNSUPPORTED_LIFETIME_MILLIS)
                    in 200..299 -> {
                        val source = response.body.source()
                        if (source.request(MAX_BOOTSTRAP_BYTES + 1)) throw JvmAuthenticatedAppReadSessionException(response.code, JvmAppReadSessionFailureKind.OversizedResponse)
                        val bytes = source.readByteArray()
                        if (!validProfile(bytes)) throw JvmAuthenticatedAppReadSessionException(response.code, JvmAppReadSessionFailureKind.InvalidResponse)
                        val cookies = boundedCookies(entry, Cookie.parseAll(response.request.url, response.headers))
                        Prepared(cookies, cookies.minOfOrNull { cookie -> cookie.expiresAt }
                            ?.coerceAtMost(now() + SESSION_LIFETIME_MILLIS) ?: (now() + UNSUPPORTED_LIFETIME_MILLIS))
                    }
                    else -> throw JvmAuthenticatedAppReadSessionException(response.code)
                }
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportBootstrapFailure(failure, recordDiagnostic)
                throw failure
            }
            currentCoroutineContext().ensureActive()
            if (!current(entry, false) {
                    it.cookies = prepared.cookies
                    it.validUntil = prepared.validUntil
                    true
                }) throw CancellationException("The account changed during session preparation.")
        }
        return client.newBuilder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                if (!current(entry, false) { true }) throw CancellationException("The account session is no longer active.")
                require(chain.request().method == "GET" && entry.accepts(chain.request().url)) {
                    "The prepared account session is limited to application reads."
                }
                chain.proceed(chain.request())
            }.cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = current(entry, emptyList()) {
                if (!entry.accepts(url) || it.validUntil <= now()) emptyList()
                else it.cookies.filter { cookie -> cookie.expiresAt > now() && cookie.matches(url) }
                    .sortedByDescending { cookie -> cookie.path.length }
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (!entry.accepts(url)) return
                current(entry, Unit) {
                    val incoming = boundedCookies(entry, cookies)
                    val replacements = cookies.filter { it.domain == entry.base.host }
                        .map { cookie -> cookie.name to cookie.path }.toSet()
                    it.cookies = boundedCookies(entry, it.cookies.filterNot { cookie ->
                        cookie.name to cookie.path in replacements
                    } + incoming)
                    it.validUntil = minOf(it.validUntil, it.cookies.minOfOrNull { cookie -> cookie.expiresAt } ?: now())
                }
            }
        }).build()
    }

    fun invalidate(session: NextcloudSession) = synchronized(lock) {
        val key = session.accountId.storageKey
        entries[key]?.takeIf { it.fingerprint == credentialFingerprint(session) }?.let {
            entries.remove(key)?.clear()
        }
    }

    fun retireAccount(accountStorageKey: String) {
        try {
            AccountPrivateMemoryLifecycle.retireAccount(accountStorageKey)
        } finally {
            removeAccount(accountStorageKey)
        }
    }

    fun removeAccount(accountStorageKey: String) = synchronized(lock) {
        entries.remove(accountStorageKey)?.clear()
    }

    private fun entryFor(session: NextcloudSession, expectedEntry: Entry? = null): Entry {
        val key = session.accountId.storageKey
        val fingerprint = credentialFingerprint(session)
        val producer = gate.producer(key) ?: throw CancellationException("The account session is no longer active.")
        return gate.read(producer, null) {
            synchronized(lock) {
                if (expectedEntry != null) {
                    if (entries[key] !== expectedEntry || expectedEntry.fingerprint != fingerprint ||
                        expectedEntry.producer.incarnation != producer.incarnation) {
                        throw CancellationException("The prepared account session has been replaced.")
                    }
                    return@synchronized expectedEntry
                }
                entries[key]?.takeIf { it.fingerprint == fingerprint && it.producer.incarnation == producer.incarnation } ?: run {
                    entries.remove(key)?.clear()
                    while (entries.size >= MAX_ACCOUNTS) entries.remove(entries.keys.first())?.clear()
                    Entry(key, fingerprint, producer, session.serverUrl.toHttpUrl()).also { entries[key] = it }
                }
            }
        } ?: throw CancellationException("The account session is no longer active.")
    }

    private fun credentialFingerprint(session: NextcloudSession): List<Byte> =
        MessageDigest.getInstance("SHA-256").digest(
            (session.loginName + "\u0000" + session.appPassword).toByteArray(Charsets.UTF_8),
        ).toList()

    private fun <T> current(entry: Entry, unavailable: T, action: (Entry) -> T): T =
        gate.read(entry.producer, unavailable) {
            synchronized(lock) { if (entries[entry.accountKey] === entry) action(entry) else unavailable }
        }

    private fun boundedCookies(entry: Entry, cookies: List<Cookie>): List<Cookie> {
        if (cookies.size > MAX_COOKIES) return emptyList()
        // Login can rotate the same session cookie several times in one response.
        // Apply replacements before expiry filtering so a final deletion also wins.
        val replaced = linkedMapOf<Triple<String, String, String>, Cookie>()
        cookies.forEach { cookie -> replaced[Triple(cookie.domain, cookie.path, cookie.name)] = cookie }
        val accepted = replaced.values.filter { cookie ->
            cookie.domain == entry.base.host && cookie.expiresAt > now() &&
                cookie.name.length + cookie.value.length <= MAX_COOKIE_BYTES &&
                (!cookie.secure || entry.base.isHttps)
        }
        return if (accepted.size <= MAX_COOKIES &&
            accepted.sumOf { it.name.length + it.value.length } <= MAX_COOKIE_TOTAL_BYTES) accepted else emptyList()
    }

    private class Entry(
        val accountKey: String,
        val fingerprint: List<Byte>,
        val producer: AccountPrivateMemoryProducer,
        val base: HttpUrl,
    ) {
        val bootstrap = Mutex()
        var cookies: List<Cookie> = emptyList()
        var validUntil = 0L
        fun clear() { cookies = emptyList(); validUntil = 0L }
        fun accepts(url: HttpUrl): Boolean {
            val root = base.encodedPath.trimEnd('/')
            val relative = url.encodedPath.removePrefix(root)
            return url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
                url.encodedPath.startsWith("$root/") &&
                (relative.startsWith("/apps/") || relative.startsWith("/index.php/apps/"))
        }
    }

    private data class Prepared(val cookies: List<Cookie>, val validUntil: Long)
}

private fun reportBootstrapFailure(failure: Exception, record: (SupportDiagnosticEventDraft) -> Unit) {
    val typed = failure as? JvmAuthenticatedAppReadSessionException
    val code = when {
        typed?.kind == JvmAppReadSessionFailureKind.Http -> "APP_READ_SESSION_HTTP_FAILED"
        typed?.kind == JvmAppReadSessionFailureKind.InvalidResponse -> "APP_READ_SESSION_RESPONSE_INVALID"
        typed?.kind == JvmAppReadSessionFailureKind.OversizedResponse -> "APP_READ_SESSION_RESPONSE_OVERSIZED"
        failure is NextcloudAuthenticatedRedirectException -> "APP_READ_SESSION_REDIRECT_REJECTED"
        failure is IOException -> "APP_READ_SESSION_NETWORK_FAILED"
        else -> "APP_READ_SESSION_FAILED"
    }
    try {
        record(SupportDiagnosticEventDraft(
            severity = SupportDiagnosticSeverity.Warning,
            component = SupportDiagnosticComponent.AdaptiveApps,
            operation = "dynamic.session",
            outcome = "failed",
            code = code,
            fields = listOf(SupportDiagnosticFieldDraft("stage", "bootstrap")) +
                listOfNotNull(typed?.let { SupportDiagnosticFieldDraft("status", it.status.toString()) }),
        ))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Reporting cannot replace the authoritative bootstrap failure.
    }
}

private fun validProfile(bytes: ByteArray): Boolean {
    if (bytes.count { it == '{'.code.toByte() || it == '['.code.toByte() } > 128) return false
    val root = try { Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } ?: return false
    val ocs = root["ocs"] as? JsonObject ?: return false
    val meta = ocs["meta"] as? JsonObject ?: return false
    val status = (meta["statuscode"] as? JsonPrimitive)?.intOrNull
    val data = ocs["data"] as? JsonObject ?: return false
    return (status == 100 || status in 200..299) &&
        (meta["status"] as? JsonPrimitive)?.contentOrNull == "ok" &&
        !(data["id"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
}

internal class JvmAppReadSessionLease(private val renew: suspend () -> OkHttpClient) {
    suspend fun client(): OkHttpClient = renew()
}

val sharedJvmAuthenticatedAppReadSessions = JvmAuthenticatedAppReadSessions()
private const val MAX_ACCOUNTS = 8
private const val MAX_COOKIES = 16
private const val MAX_COOKIE_BYTES = 4096
private const val MAX_COOKIE_TOTAL_BYTES = 8192
private const val MAX_BOOTSTRAP_BYTES = 128L * 1024L
private const val SESSION_LIFETIME_MILLIS = 15L * 60L * 1000L
private const val UNSUPPORTED_LIFETIME_MILLIS = 60L * 1000L
