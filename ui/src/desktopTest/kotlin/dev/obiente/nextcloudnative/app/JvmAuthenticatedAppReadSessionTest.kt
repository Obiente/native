package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.RecordedRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request

class JvmAuthenticatedAppReadSessionTest {
    private val read = NextcloudApiRequest(NextcloudApiMethod.GET, "/apps/music/api/albums")
    private val http = OkHttpClient()

    @Test
    fun `bootstrap retains only final rotated session cookie and honors final deletion`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(profile(
                "session=root; Path=/; HttpOnly",
                "session=before-login; Path=/cloud; HttpOnly",
                "session=authenticated; Path=/cloud; HttpOnly",
                "removed=old; Path=/cloud",
                "removed=; Path=/cloud; Max-Age=0",
                "oversized=old; Path=/cloud",
                "oversized=${"x".repeat(4097)}; Path=/cloud",
            ))
            server.enqueue(MockResponse(body = "[]"))
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val session = session(server)
            val prepared = owner.clientForRead(session, read, http)
            server.takeRequest()
            prepared.newCall(NextcloudAuthenticatedRequestPolicy(session, "test")
                .requestBuilder(server.url("/cloud/apps/music/api/albums").toString()).get().build()).execute().close()
            assertEquals("session=authenticated; session=root", server.takeRequest().headers["Cookie"])
        }
    }

    @Test
    fun `one bounded bootstrap prepares repeated app reads with Basic and private cookies`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(profile("session=synthetic; Path=/cloud; HttpOnly"))
            server.enqueue(MockResponse(body = "[]"))
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val session = session(server)
            val prepared = owner.clientForRead(session, read, http)
            val bootstrap = server.takeRequest()
            assertEquals("/cloud/ocs/v2.php/cloud/user?format=json", bootstrap.url.encodedPath + "?" + bootstrap.url.encodedQuery)
            assertEquals("true", bootstrap.headers["OCS-APIRequest"])
            assertTrue(bootstrap.headers["Authorization"].orEmpty().startsWith("Basic "))
            prepared.newCall(NextcloudAuthenticatedRequestPolicy(session, "test")
                .requestBuilder(server.url("/cloud/apps/music/api/albums").toString()).get().build()).execute().use {
                assertEquals(200, it.code)
            }
            assertEquals("session=synthetic", server.takeRequest().headers["Cookie"])
            owner.clientForRead(session, read, http)
            assertEquals(2, server.requestCount)
            assertTrue(prepared.cookieJar.loadForRequest(server.url("/other/apps/music/api/albums")).isEmpty())
            assertTrue(prepared.cookieJar.loadForRequest(server.url("/cloud/ocs/v2.php/cloud/user")).isEmpty())
            assertTrue(prepared.cookieJar.loadForRequest(server.url("/cloudish/apps/music/api/albums")).isEmpty())
        }
    }

    @Test
    fun `accounts and changed credentials never share cookies or accept late invalidation`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (value in listOf("alice", "bob", "replacement")) server.enqueue(profile("session=$value; Path=/"))
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val alice = session(server)
            val first = owner.clientForRead(alice, read, http)
            val bob = owner.clientForRead(alice.copy(loginName = "bob"), read, http)
            val replaced = owner.clientForRead(alice.copy(appPassword = "replacement"), read, http)
            val url = server.url("/cloud/apps/music/api/albums")
            assertTrue(first.cookieJar.loadForRequest(url).isEmpty())
            assertEquals("bob", bob.cookieJar.loadForRequest(url).single().value)
            owner.invalidate(alice)
            assertEquals("replacement", replaced.cookieJar.loadForRequest(url).single().value)
            owner.removeAccount(alice.accountId.storageKey)
            assertTrue(replaced.cookieJar.loadForRequest(url).isEmpty())
            assertEquals("bob", bob.cookieJar.loadForRequest(url).single().value)
        }
    }

    @Test
    fun `only application GETs bootstrap and prepared clients reject mutations`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val session = session(server)
            for (request in listOf(read.copy(method = NextcloudApiMethod.DELETE), read.copy(relativePath = "/ocs/v2.php/cloud/user"))) {
                assertSame(http, owner.clientForRead(session, request, http))
            }
            assertEquals(0, server.requestCount)
            server.enqueue(profile("session=synthetic; Path=/"))
            val prepared = owner.clientForRead(session, read, http)
            assertFailsWith<IllegalArgumentException> {
                prepared.newCall(Request.Builder().url(server.url("/cloud/apps/music/api/albums")).delete().build()).execute()
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `unsupported bootstrap preserves Basic without repeating bootstrap for every read`() = runBlocking {
        for (code in listOf(404, 405, 501)) MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = code))
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val prepared = owner.clientForRead(session(server), read, http)
            assertTrue(prepared.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).isEmpty())
            owner.clientForRead(session(server), read, http)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `authentication server malformed and oversized bootstrap failures are never swallowed`() = runBlocking {
        for (response in listOf(
            MockResponse(code = 401), MockResponse(code = 403), MockResponse(code = 500),
            MockResponse(body = "not-json"), MockResponse(body = "x".repeat(128 * 1024 + 1)),
        )) MockWebServer().use { server ->
            server.start()
            server.enqueue(response)
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            assertFailsWith<IOException> { owner.clientForRead(session(server), read, http) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `foreign and account escaping redirects cannot receive credentials or cookies`() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { foreign ->
            server.start(); foreign.start()
            for (location in listOf(foreign.url("/cloud/ocs").toString(), "/outside/ocs")) {
                server.enqueue(MockResponse.Builder().code(302).addHeader("Location", location).build())
                val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
                assertFailsWith<NextcloudAuthenticatedRedirectException> { owner.clientForRead(session(server), read, http) }
            }
            assertEquals(0, foreign.requestCount)
        } }
    }

    @Test
    fun `expired and excessive cookies do not escape the bounded session store`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var now = System.currentTimeMillis()
            val owner = JvmAuthenticatedAppReadSessions(now = { now }, gate = AccountPrivateMemoryGate())
            val session = session(server)
            server.enqueue(profile("session=one; Path=/"))
            val first = owner.clientForRead(session, read, http)
            now += 16 * 60 * 1000
            assertTrue(first.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).isEmpty())
            server.enqueue(profile("session=two; Path=/"))
            val second = owner.clientForRead(session, read, http)
            assertEquals("two", second.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).single().value)
            owner.invalidate(session)
            server.enqueue(profile(*(1..17).map { "cookie$it=value; Path=/" }.toTypedArray()))
            val bounded = owner.clientForRead(session, read, http)
            assertTrue(bounded.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).isEmpty())
        }
    }

    @Test
    fun `concurrent reads share bootstrap and retirement rejects its late completion`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(profile("session=one; Path=/", delayMillis = 200))
            val gate = AccountPrivateMemoryGate()
            val owner = JvmAuthenticatedAppReadSessions(gate = gate)
            val session = session(server)
            val first = async(Dispatchers.IO) { owner.clientForRead(session, read, http) }
            val second = async(Dispatchers.IO) { owner.clientForRead(session, read, http) }
            first.await(); second.await()
            assertEquals(1, server.requestCount)
            owner.invalidate(session)
            server.takeRequest()
            server.enqueue(profile("session=late; Path=/", delayMillis = 200))
            val late = async(Dispatchers.IO) {
                assertFailsWith<CancellationException> { owner.clientForRead(session, read, http) }
            }
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            gate.retireAccount(session.accountId.storageKey) {}
            late.await()
            gate.activateAccount(session.accountId.storageKey)
            server.enqueue(profile("session=new; Path=/"))
            val reactivated = owner.clientForRead(session, read, http)
            assertEquals("new", reactivated.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).single().value)
        }
    }

    @Test
    fun `cancelled bootstrap publishes no cookie and can restart`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(profile("session=cancelled; Path=/", delayMillis = 500))
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val session = session(server)
            val cancelled = async(Dispatchers.IO) { owner.clientForRead(session, read, http) }
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            cancelled.cancelAndJoin()
            server.enqueue(profile("session=restarted; Path=/"))
            val restarted = owner.clientForRead(session, read, http)
            assertEquals("restarted", restarted.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).single().value)
        }
    }

    @Test
    fun `cookie origin secure scope size and deletion are enforced`() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { foreign ->
            server.start(); foreign.start()
            server.enqueue(profile(
                "session=one; Path=/cloud", "other=foreign; Domain=other.example; Path=/",
                "secure=private; Secure; Path=/", "oversized=${"x".repeat(4097)}; Path=/",
            ))
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val session = session(server)
            val prepared = owner.clientForRead(session, read, http)
            val url = server.url("/cloud/apps/music/api/albums")
            assertEquals(listOf("session"), prepared.cookieJar.loadForRequest(url).map { cookie -> cookie.name })
            assertTrue(prepared.cookieJar.loadForRequest(foreign.url("/cloud/apps/music/api/albums")).isEmpty())
            prepared.cookieJar.saveFromResponse(url, listOf(Cookie.Builder().name("session").value("deleted")
                .hostOnlyDomain(url.host).path("/cloud").expiresAt(1).build()))
            assertTrue(prepared.cookieJar.loadForRequest(url).isEmpty())
            server.enqueue(profile("session=new; Path=/cloud"))
            assertEquals("new", owner.clientForRead(session, read, http).cookieJar.loadForRequest(url).single().value)
            owner.invalidate(session)
            server.enqueue(profile(*(1..3).map { "large$it=${"x".repeat(3000)}; Path=/" }.toTypedArray()))
            assertTrue(owner.clientForRead(session, read, http).cookieJar.loadForRequest(url).isEmpty())
        } }
    }

    @Test
    fun `account capacity evicts cookies and valid profiles without cookies preserve Basic`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            server.enqueue(profile("session=first; Path=/"))
            val first = owner.clientForRead(session(server), read, http)
            repeat(8) { index ->
                server.enqueue(profile())
                val session = session(server).copy(loginName = "other$index")
                val prepared = owner.clientForRead(session, read, http)
                assertTrue(prepared.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).isEmpty())
                owner.clientForRead(session, read, http)
            }
            assertTrue(first.cookieJar.loadForRequest(server.url("/cloud/apps/music/api/albums")).isEmpty())
            assertEquals(9, server.requestCount)
        }
    }

    @Test
    fun `retired queued waiter cannot dispatch another bootstrap`() = runBlocking {
        MockWebServer().use { server ->
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(release.await(5, TimeUnit.SECONDS))
                    return profile("session=late; Path=/")
                }
            }
            server.start()
            val gate = AccountPrivateMemoryGate()
            val owner = JvmAuthenticatedAppReadSessions(gate = gate)
            val session = session(server)
            val first = async(Dispatchers.IO) {
                assertFailsWith<CancellationException> { owner.clientForRead(session, read, http) }
            }
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<CancellationException> { owner.clientForRead(session, read, http) }
            }
            gate.retireAccount(session.accountId.storageKey) {}
            release.countDown()
            first.await(); queued.await()
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `bootstrap diagnostic is bounded and reporting failure cannot replace HTTP failure`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 500, body = "private profile body"))
            val events = mutableListOf<SupportDiagnosticEventDraft>()
            val owner = JvmAuthenticatedAppReadSessions(gate = AccountPrivateMemoryGate())
            val failure = assertFailsWith<JvmAuthenticatedAppReadSessionException> {
                owner.clientForRead(session(server), read, http) { event ->
                    events += event
                    throw IOException("reporting failed")
                }
            }
            assertEquals(500, failure.status)
            assertEquals("APP_READ_SESSION_HTTP_FAILED", events.single().code)
            assertEquals(mapOf("stage" to "bootstrap", "status" to "500"), events.single().fields.associate { it.name to it.value })
            assertEquals(null, events.single().message)
            assertEquals(null, events.single().exception)
            assertEquals(1, server.requestCount)
        }
    }

    private fun session(server: MockWebServer) = NextcloudSession(server.url("/cloud").toString(), "alice", "synthetic")
    private fun profile(vararg cookies: String, delayMillis: Long = 0): MockResponse = MockResponse.Builder()
        .code(200).body("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"id":"alice"}}}""")
        .bodyDelay(delayMillis, TimeUnit.MILLISECONDS)
        .apply { cookies.forEach { addHeader("Set-Cookie", it) } }.build()
}
