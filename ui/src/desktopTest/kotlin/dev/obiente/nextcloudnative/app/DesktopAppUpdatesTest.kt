package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAppUpdatesTest {
    @Test
    fun linuxTargetDetectionSelectsOnlyAnUnambiguousNativePackage() {
        assertEquals(
            DesktopUpdateTarget("linux", "rpm", "x86_64"),
            detectDesktopUpdateTarget("Linux", "amd64", debianMarker = false, rpmMarker = true),
        )
        assertEquals(
            DesktopUpdateTarget("linux", "deb", "aarch64"),
            detectDesktopUpdateTarget("Linux", "arm64", debianMarker = true, rpmMarker = false),
        )
        assertNull(detectDesktopUpdateTarget("Windows 11", "amd64", false, false))
        assertNull(detectDesktopUpdateTarget("Linux", "riscv64", false, true))
        assertNull(detectDesktopUpdateTarget("Linux", "amd64", true, true))
        assertNull(detectDesktopUpdateTarget("Linux", "amd64", false, false))
    }

    @Test
    fun onlyPackagedReleaseBuildsOfferDirectLinuxUpdates() {
        val node = Preferences.userRoot().node("desktop-update-test-${UUID.randomUUID()}")
        val directory = Files.createTempDirectory("desktop-update-support-test").toFile()
        try {
            val release = DesktopAppUpdater(
                preferences = node,
                buildIdentity = DesktopUpdateBuildIdentity(
                    versionName = "nightly-20260731-1543-run358-02200472",
                    versionCode = 20_002_921,
                    packageVersion = "1.0.2921",
                    releaseBuild = true,
                ),
                target = DesktopUpdateTarget("linux", "rpm", "x86_64"),
                updateDirectory = directory,
                openInstaller = {},
            )
            val development = DesktopAppUpdater(
                preferences = node,
                buildIdentity = DesktopUpdateBuildIdentity("development", 0, "0.1.0", false),
                target = DesktopUpdateTarget("linux", "rpm", "x86_64"),
                updateDirectory = directory,
                openInstaller = {},
            )

            assertEquals(AppDistributionChannel.DirectDesktopPackage, release.support().channel)
            assertTrue(release.support().canCheckDirectUpdates)
            assertTrue(release.support().explanation.contains("checksum"))
            assertFalse(release.support().explanation.contains("signed", ignoreCase = true))
            assertEquals(AppDistributionChannel.Development, development.support().channel)
            assertFalse(development.support().canCheckDirectUpdates)
            assertEquals(6L * 60L * 60L * 1_000L, DESKTOP_APP_UPDATE_CHECK_INTERVAL_MILLIS)
        } finally {
            node.removeNode()
            directory.deleteRecursively()
        }
    }

    @Test
    fun completedUpdatePackagesAreEvictedWithoutTouchingPartialsOrUnrelatedFiles() {
        val directory = Files.createTempDirectory("desktop-update-cleanup-test").toFile()
        try {
            val rpm = directory.resolve("nextcloud-native-old.rpm").apply { writeText("rpm") }
            val deb = directory.resolve("nextcloud-native-old.deb").apply { writeText("deb") }
            val partial = directory.resolve("nextcloud-native-new.rpm.part").apply { writeText("partial") }
            val unrelated = directory.resolve("README.txt").apply { writeText("keep") }

            assertEquals(2, cleanupCompletedDesktopUpdatePackages(directory))
            assertFalse(rpm.exists())
            assertFalse(deb.exists())
            assertTrue(partial.isFile)
            assertTrue(unrelated.isFile)
            assertEquals(0, cleanupCompletedDesktopUpdatePackages(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun releaseDownloadsMayRedirectOnlyToGithubReleaseAssetStorage() {
        assertTrue(
            isTrustedDesktopReleaseAssetRedirect(
                "https://release-assets.githubusercontent.com/github-production-release-asset/file.rpm",
            ),
        )
        assertFalse(isTrustedDesktopReleaseAssetRedirect("https://github.com/redirected.rpm"))
        assertFalse(isTrustedDesktopReleaseAssetRedirect("http://release-assets.githubusercontent.com/file.rpm"))
        assertFalse(isTrustedDesktopReleaseAssetRedirect("https://release-assets.githubusercontent.com/"))
    }
}
