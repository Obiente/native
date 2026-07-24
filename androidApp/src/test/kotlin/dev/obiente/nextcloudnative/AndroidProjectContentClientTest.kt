package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.AppDistributionChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun failedUpdateVerificationRemovesPartialApk() {
        val directory = Files.createTempDirectory("project-content-update-test").toFile()
        val temporary = directory.resolve("update.apk.part")
        val staged = directory.resolve("update.apk")
        try {
            assertFailsWith<IllegalStateException> {
                stageVerifiedUpdate(temporary, staged) {
                    temporary.writeText("unverified")
                    error("verification failed")
                }
            }

            assertFalse(temporary.exists())
            assertFalse(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulUpdateStagingPreservesVerifiedApk() {
        val directory = Files.createTempDirectory("project-content-update-test").toFile()
        val temporary = directory.resolve("update.apk.part")
        val staged = directory.resolve("update.apk")
        try {
            stageVerifiedUpdate(temporary, staged) {
                temporary.writeText("verified")
            }

            assertFalse(temporary.exists())
            assertTrue(staged.isFile)
            assertEquals("verified", staged.readText())
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
    }
}
