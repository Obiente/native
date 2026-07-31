package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class DesktopAppUpdatesTest {
    @Test
    fun linuxTargetDetectionSelectsOnlyAnUnambiguousNativePackage() {
        assertEquals(
            DesktopUpdateTarget("linux", "rpm", "x86_64"),
            detectDesktopUpdateTarget(
                "Linux",
                "amd64",
                debianMarker = false,
                rpmMarker = true,
                installedPackageFormat = null,
            ),
        )
        assertEquals(
            DesktopUpdateTarget("linux", "deb", "aarch64"),
            detectDesktopUpdateTarget(
                "Linux",
                "arm64",
                debianMarker = true,
                rpmMarker = false,
                installedPackageFormat = null,
            ),
        )
        assertEquals(
            DesktopUpdateTarget("linux", "rpm", "x86_64"),
            detectDesktopUpdateTarget("Linux", "amd64", false, false, installedPackageFormat = "rpm"),
        )
        assertNull(detectDesktopUpdateTarget("Windows 11", "amd64", false, false, null))
        assertNull(detectDesktopUpdateTarget("Linux", "riscv64", false, true, null))
        assertNull(detectDesktopUpdateTarget("Linux", "amd64", true, true, null))
        assertNull(detectDesktopUpdateTarget("Linux", "amd64", false, false, null))
        assertEquals(
            "rpm",
            detectInstalledDesktopPackageFormat("Linux") { command ->
                if (command.first().endsWith("/rpm")) "nextcloudnative" else null
            },
        )
        assertEquals(
            "deb",
            detectInstalledDesktopPackageFormat("Linux") { command ->
                if (command.first().endsWith("/dpkg-query")) "ii " else null
            },
        )
        assertNull(detectInstalledDesktopPackageFormat("Linux") { "unexpected" })
        assertNull(detectInstalledDesktopPackageFormat("Windows 11") { "unexpected" })
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
                    directPackageUpdates = true,
                ),
                target = DesktopUpdateTarget("linux", "rpm", "x86_64"),
                updateDirectory = directory,
                openInstaller = {},
            )
            val development = DesktopAppUpdater(
                preferences = node,
                buildIdentity = DesktopUpdateBuildIdentity("development", 0, "0.1.0", false, false),
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
            val distributionManaged = DesktopAppUpdater(
                preferences = node,
                buildIdentity = DesktopUpdateBuildIdentity(
                    versionName = "0.1.0-alpha.1",
                    versionCode = 1,
                    packageVersion = "0.1.0",
                    releaseBuild = true,
                    directPackageUpdates = false,
                ),
                target = DesktopUpdateTarget("linux", "rpm", "x86_64"),
                updateDirectory = directory,
                openInstaller = {},
            )
            assertEquals(AppDistributionChannel.Development, distributionManaged.support().channel)
            assertFalse(distributionManaged.support().canCheckDirectUpdates)
            assertTrue(distributionManaged.support().explanation.contains("distribution-managed"))
            assertEquals(6L * 60L * 60L * 1_000L, DESKTOP_APP_UPDATE_CHECK_INTERVAL_MILLIS)
        } finally {
            node.removeNode()
            directory.deleteRecursively()
        }
    }

    @Test
    fun completedAndAbandonedUpdatePackagesAreEvictedWithinAProtectedPartialBudget() {
        val directory = Files.createTempDirectory("desktop-update-cleanup-test").toFile()
        try {
            val now = 2 * DESKTOP_PARTIAL_RETENTION_MILLIS
            val rpm = directory.resolve("nextcloud-native-old.rpm").apply { writeText("rpm") }
            val deb = directory.resolve("nextcloud-native-old.deb").apply { writeText("deb") }
            val expiredPartial = directory.resolve("nextcloud-native-old.rpm.part").apply {
                writeText("old")
                setLastModified(now - DESKTOP_PARTIAL_RETENTION_MILLIS - 1)
            }
            val overBudgetPartial = directory.resolve("nextcloud-native-large.deb.part").apply {
                writeText("large")
                setLastModified(now - 2)
            }
            val freshPartial = directory.resolve("nextcloud-native-new.rpm.part").apply {
                writeText("new")
                setLastModified(now - 1)
            }
            val activePartial = directory.resolve("nextcloud-native-active.rpm.part").apply {
                writeText("active")
                setLastModified(0)
            }
            val unrelated = directory.resolve("README.txt").apply { writeText("keep") }

            assertEquals(
                4,
                cleanupDesktopUpdatePackages(
                    directory = directory,
                    activePartial = activePartial,
                    nowMillis = now,
                    maximumPartialBytes = freshPartial.length(),
                ),
            )
            assertFalse(rpm.exists())
            assertFalse(deb.exists())
            assertFalse(expiredPartial.exists())
            assertFalse(overBudgetPartial.exists())
            assertTrue(freshPartial.isFile)
            assertTrue(activePartial.isFile)
            assertTrue(unrelated.isFile)
            assertEquals(
                0,
                cleanupDesktopUpdatePackages(
                    directory = directory,
                    activePartial = activePartial,
                    nowMillis = now,
                    maximumPartialBytes = freshPartial.length(),
                ),
            )
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

    @Test
    fun desktopReleaseCannotCrossTheSavedUpdateChannel() {
        val node = Preferences.userRoot().node("desktop-update-channel-test-${UUID.randomUUID()}")
        val directory = Files.createTempDirectory("desktop-update-channel-test").toFile()
        try {
            val updater = DesktopAppUpdater(
                preferences = node,
                buildIdentity = DesktopUpdateBuildIdentity(
                    versionName = "0.1.0-alpha.1",
                    versionCode = 1,
                    packageVersion = "0.1.0",
                    releaseBuild = true,
                    directPackageUpdates = true,
                ),
                target = DesktopUpdateTarget("linux", "rpm", "x86_64"),
                updateDirectory = directory,
                openInstaller = {},
            )
            val alphaRelease = DesktopDirectRelease(
                updateChannel = AndroidUpdateChannel.Alpha,
                versionName = "0.1.0-alpha.2",
                versionCode = 2,
                packageVersion = "0.1.1",
                asset = DesktopUpdateAsset(
                    platform = "linux",
                    format = "rpm",
                    architecture = "x86_64",
                    url = "https://github.com/Obiente/nc-native/releases/download/" +
                        "v0.1.0-alpha.2/nextcloudnative.rpm",
                    size = 1,
                    sha256 = "a".repeat(64),
                ),
                releaseNotesUrl = "https://github.com/Obiente/nc-native/releases/tag/v0.1.0-alpha.2",
            )

            assertTrue(updater.saveUpdateChannel(AndroidUpdateChannel.Nightly))
            val result = runBlocking { updater.beginUpdate(alphaRelease) }
            val rejected = assertIs<AppUpdateInstallResult.Rejected>(result)
            assertTrue(rejected.message.contains("channel changed", ignoreCase = true))
        } finally {
            node.removeNode()
            directory.deleteRecursively()
        }
    }
}
