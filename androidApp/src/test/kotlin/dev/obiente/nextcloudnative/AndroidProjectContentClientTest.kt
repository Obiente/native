package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.AppDistributionChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer

class AndroidProjectContentClientTest {
    @Test
    fun publicContentClientUsesExplicitBoundedTimeouts() {
        val client = buildProjectContentHttpClient()

        assertEquals(PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS * 1_000, client.connectTimeoutMillis.toLong())
        assertEquals(PROJECT_CONTENT_READ_TIMEOUT_SECONDS * 1_000, client.readTimeoutMillis.toLong())
        assertEquals(PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS * 1_000, client.writeTimeoutMillis.toLong())
        assertEquals(PROJECT_CONTENT_CALL_TIMEOUT_SECONDS * 1_000, client.callTimeoutMillis.toLong())
    }

    @Test
    fun updateDownloadResumesFromAnExistingPartialFile() {
        val directory = Files.createTempDirectory("project-content-update-test").toFile()
        val temporary = directory.resolve("update.apk.part")
        MockWebServer().use { server ->
            temporary.writeText("abc")
            server.enqueue(
                MockResponse.Builder()
                    .code(206)
                    .setHeader("Content-Range", "bytes 3-5/6")
                    .body("def")
                    .build(),
            )
            server.start()
            val progress = mutableListOf<Long>()
            try {
                downloadUpdateApk(
                    client = OkHttpClient(),
                    url = server.url("/update.apk").toString(),
                    expectedSize = 6,
                    target = temporary,
                    isCancelled = { false },
                    onProgress = progress::add,
                )

                assertEquals("bytes=3-", server.takeRequest().headers["Range"])
                assertEquals("abcdef", temporary.readText())
                assertEquals(3, progress.first())
                assertEquals(6, progress.last())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun serverIgnoringRangeRestartsWithoutDuplicatingBytes() {
        val directory = Files.createTempDirectory("project-content-update-test").toFile()
        val temporary = directory.resolve("update.apk.part")
        MockWebServer().use { server ->
            temporary.writeText("abc")
            server.enqueue(MockResponse.Builder().code(200).body("abcdef").build())
            server.start()
            try {
                downloadUpdateApk(
                    client = OkHttpClient(),
                    url = server.url("/update.apk").toString(),
                    expectedSize = 6,
                    target = temporary,
                    isCancelled = { false },
                )

                assertEquals("bytes=3-", server.takeRequest().headers["Range"])
                assertEquals("abcdef", temporary.readText())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun cancelledDownloadRetainsSafeBytesForResume() {
        val directory = Files.createTempDirectory("project-content-update-test").toFile()
        val temporary = directory.resolve("update.apk.part")
        val payload = ByteArray(96 * 1024) { index -> (index % 251).toByte() }
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(Buffer().write(payload))
                    .build(),
            )
            server.start()
            var cancelled = false
            try {
                assertFailsWith<UpdateDownloadCancelledException> {
                    downloadUpdateApk(
                        client = OkHttpClient(),
                        url = server.url("/update.apk").toString(),
                        expectedSize = payload.size.toLong(),
                        target = temporary,
                        isCancelled = { cancelled },
                        onProgress = { downloaded -> if (downloaded > 0) cancelled = true },
                    )
                }

                assertTrue(temporary.isFile)
                assertTrue(temporary.length() in 1 until payload.size.toLong())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun rejectedPackageDiscardsPartialWhileRecoverableDownloadRetainsIt() {
        val directory = Files.createTempDirectory("project-content-update-test").toFile()
        val temporary = directory.resolve("update.apk.part")
        try {
            temporary.writeText("partial")
            assertEquals(
                temporary.length(),
                settleUpdatePartial(temporary, expectedSize = 64, retain = true),
            )
            assertTrue(temporary.isFile)

            assertEquals(
                0,
                settleUpdatePartial(temporary, expectedSize = 64, retain = false),
            )
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun storeOwnedAndDirectApkChannelsRemainDistinct() {
        assertEquals(
            AppDistributionChannel.GooglePlay,
            classifyAndroidDistribution("com.android.vending", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.FDroid,
            classifyAndroidDistribution("org.fdroid.fdroid", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.OtherStore,
            classifyAndroidDistribution("com.example.store", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.DirectApk,
            classifyAndroidDistribution(null, debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.DirectApk,
            classifyAndroidDistribution("com.android.packageinstaller", debugBuild = false),
        )
        assertEquals(
            AppDistributionChannel.Development,
            classifyAndroidDistribution(null, debugBuild = true),
        )
        assertTrue(
            canCheckAndroidDirectUpdates(
                AppDistributionChannel.DirectApk,
                directApkBuild = true,
            ),
        )
        assertFalse(
            canCheckAndroidDirectUpdates(
                AppDistributionChannel.DirectApk,
                directApkBuild = false,
            ),
        )
        assertFalse(
            canCheckAndroidDirectUpdates(
                AppDistributionChannel.GooglePlay,
                directApkBuild = true,
            ),
        )
    }

    @Test
    fun updateSdkCompatibilityRejectsUnsupportedDevicesBeforeInstallation() {
        assertNull(androidSdkCompatibilityFailure(minSdk = 26, maxSdk = null, deviceSdk = 36))
        assertNull(androidSdkCompatibilityFailure(minSdk = 26, maxSdk = 36, deviceSdk = 36))

        val deviceTooOld = requireNotNull(
            androidSdkCompatibilityFailure(minSdk = 35, maxSdk = null, deviceSdk = 34),
        )
        assertContains(deviceTooOld, "requires Android API 35 or newer")
        assertContains(deviceTooOld, "device uses API 34")

        val deviceTooNew = requireNotNull(
            androidSdkCompatibilityFailure(minSdk = 26, maxSdk = 34, deviceSdk = 35),
        )
        assertContains(deviceTooNew, "supports Android API 34 or older")
        assertContains(deviceTooNew, "device uses API 35")
    }

    @Test
    fun updateSdkCompatibilityRejectsInvalidArchiveRequirements() {
        assertFailsWith<IllegalArgumentException> {
            androidSdkCompatibilityFailure(minSdk = 35, maxSdk = 34, deviceSdk = 34)
        }
    }
}
