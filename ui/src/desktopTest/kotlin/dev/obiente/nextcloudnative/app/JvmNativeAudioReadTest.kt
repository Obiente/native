package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request

class JvmNativeAudioReadTest {
    @Test
    fun `old queue cannot renew after a newer credential session replaces its entry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val gate = AccountPrivateMemoryGate()
            val owner = JvmAuthenticatedAppReadSessions(gate = gate)
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "synthetic")
            val source = NativeAudioPlaybackSource("tone", "/apps/music/api/files/7/download", "audio/mpeg")
            fun profile(cookie: String) = MockResponse.Builder()
                .body("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"id":"alice"}}}""")
                .addHeader("Set-Cookie", "session=$cookie; Path=/cloud").build()
            server.enqueue(profile("first"))
            server.enqueue(profile("replacement"))
            val reads = prepareNativeAudioReads(session, listOf(source), OkHttpClient(), owner, gate)
            val replacement = owner.clientForRead(session.copy(appPassword = "replacement"),
                NextcloudApiRequest(NextcloudApiMethod.GET, source.relativePath), OkHttpClient())
            val request = Request.Builder().url(nativeAudioPlaybackUrl(session, source)).build()
            assertFailsWith<IOException> { reads.newCall(request).execute() }
            assertEquals(2, server.requestCount)
            assertEquals("replacement", replacement.cookieJar.loadForRequest(request.url).single().value)
            server.enqueue(MockResponse(body = "replacement audio"))
            replacement.newCall(NextcloudAuthenticatedRequestPolicy(session.copy(appPassword = "replacement"), "test")
                .requestBuilder(request.url.toString()).get().build()).execute().use { assertEquals(200, it.code) }
            server.takeRequest()
            server.takeRequest()
            assertEquals("session=replacement", server.takeRequest().headers["Cookie"])
        }
    }


    @Test
    fun `existing queue refreshes expired session before the next range request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var now = 1_000L
            val gate = AccountPrivateMemoryGate()
            val owner = JvmAuthenticatedAppReadSessions(now = { now }, gate = gate)
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "synthetic")
            val source = NativeAudioPlaybackSource("tone", "/apps/music/api/files/7/download", "audio/mpeg")
            fun profile(cookie: String) = MockResponse.Builder()
                .body("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"id":"alice"}}}""")
                .addHeader("Set-Cookie", "session=$cookie; Path=/cloud; HttpOnly").build()
            server.enqueue(profile("first"))
            server.enqueue(MockResponse(body = "first audio"))
            val reads = prepareNativeAudioReads(session, listOf(source), OkHttpClient(), owner, gate)
            val request = Request.Builder().url(nativeAudioPlaybackUrl(session, source)).header("Range", "bytes=2-").build()
            reads.newCall(request).execute().close()
            server.takeRequest()
            assertEquals("session=first", server.takeRequest().headers["Cookie"])
            now += 16 * 60 * 1000L
            server.enqueue(profile("renewed"))
            server.enqueue(MockResponse(body = "later audio"))
            reads.newCall(request).execute().close()
            assertEquals("/cloud/ocs/v2.php/cloud/user", server.takeRequest().url.encodedPath)
            val renewed = server.takeRequest()
            assertEquals("session=renewed", renewed.headers["Cookie"])
            assertEquals("bytes=2-", renewed.headers["Range"])
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `cancellation and caller timeout stop expired session preparation before stream delivery`() = runBlocking {
        for (useTimeout in listOf(false, true)) {
            MockWebServer().use { server ->
                server.start()
                var now = 1_000L
                val gate = AccountPrivateMemoryGate()
                val owner = JvmAuthenticatedAppReadSessions(now = { now }, gate = gate)
                val session = NextcloudSession(server.url("/cloud").toString(), "alice", "synthetic")
                val source = NativeAudioPlaybackSource("tone", "/apps/music/api/files/7/download", "audio/mpeg")
                val profile = """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"id":"alice"}}}"""
                server.enqueue(MockResponse.Builder().body(profile).addHeader("Set-Cookie", "session=first; Path=/cloud").build())
                val reads = prepareNativeAudioReads(session, listOf(source), OkHttpClient(), owner, gate)
                server.takeRequest()
                now += 16 * 60 * 1000L
                server.enqueue(MockResponse.Builder().body(profile).bodyDelay(30, TimeUnit.SECONDS).build())
                val call = reads.newCall(Request.Builder().url(nativeAudioPlaybackUrl(session, source)).build())
                if (useTimeout) call.timeout().timeout(1, TimeUnit.SECONDS)
                val failed = CountDownLatch(1)
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { failed.countDown() }
                    override fun onResponse(call: Call, response: Response) { response.close(); error("Cancelled stream cannot succeed") }
                })
                withContext(Dispatchers.IO) { server.takeRequest() }
                if (!useTimeout) call.cancel()
                assertTrue(withContext(Dispatchers.IO) { failed.await(5, TimeUnit.SECONDS) })
                assertTrue(call.isCanceled())
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `app stream shares guarded session cookies and preserves range without following redirects`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"id":"alice"}}}""")
                .addHeader("Set-Cookie", "session=synthetic; Path=/cloud; HttpOnly").build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://outside.invalid/audio").build())
            val gate = AccountPrivateMemoryGate()
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "synthetic")
            val source = NativeAudioPlaybackSource("tone", "/apps/music/api/file/7/download", "audio/mpeg")
            val reads = prepareNativeAudioReads(session, listOf(source), OkHttpClient(), JvmAuthenticatedAppReadSessions(gate = gate), gate)
            server.takeRequest()
            val request = Request.Builder().url(nativeAudioPlaybackUrl(session, source)).header("Range", "bytes=2-").build()
            reads.newCall(request).execute().use { assertEquals(302, it.code) }
            val received = server.takeRequest()
            assertEquals("session=synthetic", received.headers["Cookie"])
            assertTrue(received.headers["Authorization"].orEmpty().startsWith("Basic "))
            assertEquals("bytes=2-", received.headers["Range"])
            assertEquals(2, server.requestCount)
            assertFailsWith<IOException> { reads.newCall(request.newBuilder().url("https://outside.invalid/audio").build()) }
            assertFailsWith<IOException> { reads.newCall(request.newBuilder().url(server.url("/cloud/apps/music/api/file/8/download")).build()) }
            assertFailsWith<IOException> { reads.newCall(request.newBuilder().delete().build()).execute() }
            gate.retireAccount(session.accountId.storageKey) {}
            gate.activateAccount(session.accountId.storageKey)
            assertFailsWith<IOException> { reads.newCall(request).execute() }
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `cancelled session preparation never starts an audio request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("{}").bodyDelay(30, TimeUnit.SECONDS).build())
            val gate = AccountPrivateMemoryGate()
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "synthetic")
            val preparing = async(Dispatchers.IO) {
                prepareNativeAudioReads(session,
                    listOf(NativeAudioPlaybackSource("tone", "/apps/music/api/file/7/download", "audio/mpeg")),
                    OkHttpClient(), JvmAuthenticatedAppReadSessions(gate = gate), gate)
            }
            withContext(Dispatchers.IO) { server.takeRequest() }
            preparing.cancelAndJoin()
            assertTrue(preparing.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `DAV audio does not bootstrap or acquire application cookies`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "synthetic audio"))
            val gate = AccountPrivateMemoryGate()
            val session = NextcloudSession(server.url("/cloud").toString(), "alice", "synthetic")
            val source = NativeAudioPlaybackSource("tone", "/remote.php/dav/files/alice/tone.mp3", "audio/mpeg")
            val reads = prepareNativeAudioReads(session, listOf(source), OkHttpClient(), JvmAuthenticatedAppReadSessions(gate = gate), gate)
            reads.newCall(Request.Builder().url(nativeAudioPlaybackUrl(session, source)).build()).execute().close()
            val received = server.takeRequest()
            assertEquals("/cloud/remote.php/dav/files/alice/tone.mp3", received.url.encodedPath)
            assertEquals(null, received.headers["Cookie"])
            assertEquals(1, server.requestCount)
        }
    }
}
