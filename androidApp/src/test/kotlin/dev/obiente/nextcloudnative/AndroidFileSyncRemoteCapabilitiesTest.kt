package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class AndroidFileSyncRemoteCapabilitiesTest {
    @Test
    fun `content identity read observes worker cancellation`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).body("same note")
                    .bodyDelay(30, TimeUnit.SECONDS).build(),
            )
            server.start()
            val cancellation = TestCancellation()
            val remote = AndroidFileSyncRemoteTree(
                NextcloudSession(server.url("/").toString(), "alice", "secret"),
                "alice",
                "Vault",
                NextcloudDocumentWebDav(),
                transferCancellation = cancellation,
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val verification = executor.submit<Boolean> {
                    remote.verifyContentHash(
                        "Notes/today.md",
                        "\"note-7\"",
                        "sha256:8b4c848f9c906b8b340c2400c9aa8fdc1c9d5db557bad1b6aabdd9aabe3eb6e9",
                        expectedBytes = 9L,
                        maximumBytes = 1_024L,
                    )
                }

                assertTrue(cancellation.attached.await(2, TimeUnit.SECONDS))
                cancellation.cancel()
                val failure = assertFailsWith<java.util.concurrent.ExecutionException> {
                    verification.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is TestCancelledException)
                assertTrue(cancellation.detached.await(2, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `direct overwrite fallback requires explicit create denial`() {
        MockWebServer().use { server ->
            server.enqueue(directoryAccessResponse("<oc:permissions>RGDNVW</oc:permissions>"))
            server.enqueue(directoryAccessResponse(""))
            server.enqueue(directoryAccessResponse("<oc:permissions>RGDNVW</oc:permissions>"))
            server.start()
            val remote = AndroidFileSyncRemoteTree(
                NextcloudSession(server.url("/").toString(), "alice", "secret"),
                "alice",
                "Vault",
                NextcloudDocumentWebDav(),
            )

            assertEquals(false, remote.ownedStageCreationAllowed("Shared/archive.bin"))
            assertEquals(null, remote.ownedStageCreationAllowed("Shared/archive.bin"))
            val failure = assertFailsWith<IllegalStateException> {
                remote.resumableUploadRemote(replacingDirectoryEtag = "directory-etag")
                    .ownedStageCreationAllowed("Shared/archive.bin")
            }
            assertTrue(failure.message.orEmpty().contains("required to replace a directory"))
            repeat(3) {
                val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("0", request.headers["Depth"])
                assertTrue(request.url.encodedPath.endsWith("/Vault/Shared"))
            }
        }
    }

    private fun directoryAccessResponse(permissions: String) =
        MockResponse.Builder().code(207).body(
            """
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response>
              <d:href>/remote.php/dav/files/alice/Vault/Shared</d:href>
              <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>$permissions
              </d:prop></d:propstat>
            </d:response></d:multistatus>
            """.trimIndent(),
        ).build()

    private class TestCancellation : DocumentRequestCancellation {
        val attached = CountDownLatch(1)
        val detached = CountDownLatch(1)
        @Volatile private var cancelled = false
        @Volatile private var cancelAction: (() -> Unit)? = null

        fun cancel() {
            cancelled = true
            cancelAction?.invoke()
        }

        override fun throwIfCancelled() {
            if (cancelled) throw TestCancelledException()
        }

        override fun setOnCancelAction(action: (() -> Unit)?) {
            cancelAction = action
            if (action == null) detached.countDown() else attached.countDown()
            if (cancelled) action?.invoke()
        }
    }

    private class TestCancelledException : RuntimeException()
}
