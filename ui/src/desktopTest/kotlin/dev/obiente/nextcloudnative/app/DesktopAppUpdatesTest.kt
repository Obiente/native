package dev.obiente.nextcloudnative.app

import java.io.File
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
    fun linuxUpdatesUseAnAuthorizedPackageServiceWithoutShellParsing() {
        val packageFile = File("/tmp/update path;still-one-argument.rpm")
        val packageKit = File("/usr/bin/pkcon")

        assertEquals(
            listOf(
                packageKit.absolutePath,
                "--noninteractive",
                "install-local",
                packageFile.toPath().toAbsolutePath().normalize().toString(),
            ),
            linuxNativePackageInstallerCommand(packageFile) { executable -> executable == packageKit },
        )
        assertEquals(
            "install-local",
            linuxNativePackageInstallerCommand(File("/tmp/nextcloudnative.deb")) { true }?.get(2),
        )
        assertNull(linuxNativePackageInstallerCommand(packageFile) { false })
        assertNull(linuxNativePackageInstallerCommand(File("/tmp/nextcloudnative.pkg")) { true })
    }

    @Test
    fun linuxPackageTransactionsMustFinishSuccessfully() {
        val directory = Files.createTempDirectory("desktop-update-transaction-test").toFile()
        val packageFile = directory.resolve("nextcloudnative.rpm").apply { writeText("verified") }
        val command = listOf("/usr/bin/pkcon", "--noninteractive", "install-local", packageFile.absolutePath)
        try {
            var observedCommand: List<String>? = null
            assertTrue(
                runLinuxNativePackageInstaller(
                    packageFile = packageFile,
                    commandResolver = { command },
                    commandRunner = { launched ->
                        observedCommand = launched
                        0
                    },
                ),
            )
            assertEquals(command, observedCommand)
            val failure = kotlin.test.assertFailsWith<IllegalStateException> {
                runLinuxNativePackageInstaller(
                    packageFile = packageFile,
                    commandResolver = { command },
                    commandRunner = { 5 },
                )
            }
            assertTrue(failure.message.orEmpty().contains("exit code 5"))
            assertFalse(runLinuxNativePackageInstaller(packageFile, commandResolver = { null }))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun windowsInstallerMetadataPreservesTheInternetTrustBoundary() {
        val source = "https://github.com/Obiente/nc-native/releases/download/v1/NextcloudNative.msi"
        val notes = "https://github.com/Obiente/nc-native/releases/tag/v1"

        assertEquals(
            "[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=$source\r\nReferrerUrl=$notes\r\n",
            windowsZoneIdentifier(source, notes),
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            windowsZoneIdentifier("https://example.invalid/package.msi\r\nZoneId=0", notes)
        }
    }

    @Test
    fun windowsInstallerWaitsForTheAppToExitWithoutShellParsing() {
        val directory = Files.createTempDirectory("desktop-update-handoff-test").toFile()
        val packageFile = directory.resolve("nextcloud native;verified.msi").apply { writeText("verified") }
        val windowsDirectory = directory.resolve("Windows")
        val powershell = windowsDirectory.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").apply {
            parentFile.mkdirs()
            writeText("powershell")
        }
        val launcher = directory.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        var command = emptyList<String>()
        var cancelled = false
        try {
            startWindowsInstallerAfterAppExit(
                packageFile = packageFile,
                parentProcessId = 42L,
                windowsDirectory = windowsDirectory,
                launcherFile = launcher,
                processStarter = {
                    command = it
                    WindowsInstallerHandoffProcess {
                        cancelled = true
                        true
                    }
                },
                readinessWaiter = { acknowledgement, token ->
                    assertEquals(acknowledgement.absolutePath, command[command.indexOf("-AcknowledgementPath") + 1])
                    assertEquals(token, command[command.indexOf("-AcknowledgementToken") + 1])
                    true
                },
            )

            assertEquals(powershell.absolutePath, command.first())
            assertEquals("42", command[command.indexOf("-ParentProcessId") + 1])
            assertEquals(packageFile.absolutePath, command[command.indexOf("-InstallerPath") + 1])
            assertEquals(launcher.absolutePath, command[command.indexOf("-LauncherPath") + 1])
            assertTrue(command[command.indexOf("-CancellationPath") + 1].endsWith(".ack"))
            assertEquals(64, command[command.indexOf("-CancellationToken") + 1].length)
            val script = File(command[command.indexOf("-File") + 1])
            assertTrue(script.isFile)
            assertTrue(script.readText().contains("Wait-Process -Id \$ParentProcessId"))
            assertTrue(script.readText().contains("Join-Path \$env:SystemRoot 'System32\\msiexec.exe'"))
            assertTrue(script.readText().contains("'NEXTCLOUD_NATIVE_UPDATER_HANDOFF=1'"))
            assertTrue(script.readText().contains("Start-Process -FilePath \$LauncherPath -ErrorAction Stop"))
            assertTrue(script.readText().contains("\$successfulExitCodes = @(0, 1641, 3010)"))
            assertTrue(script.readText().contains("\$installerProcess.ExitCode -notin \$successfulExitCodes"))
            assertTrue(script.readText().contains("Set-Content -LiteralPath \$AcknowledgementPath"))
            assertTrue(script.readText().contains("Test-HandoffCancellation"))
            assertTrue(script.readText().contains("cancelled before installer launch"))
            assertTrue(script.readText().contains("--update-handoff-failed"))
            assertFalse(script.readText().contains(packageFile.absolutePath))
            assertFalse(cancelled)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun windowsInstallerHandoffKeepsTheAppOpenWithoutReadinessAcknowledgement() {
        val directory = Files.createTempDirectory("desktop-update-handoff-failure").toFile()
        val packageFile = directory.resolve("verified.msi").apply { writeText("verified") }
        val windowsDirectory = directory.resolve("Windows")
        windowsDirectory.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").apply {
            parentFile.mkdirs()
            writeText("powershell")
        }
        val launcher = directory.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        var script: File? = null
        var cancellationObserved = false
        var processCancelled = false
        try {
            val failure = kotlin.test.assertFailsWith<IllegalStateException> {
                startWindowsInstallerAfterAppExit(
                    packageFile = packageFile,
                    parentProcessId = 42L,
                    windowsDirectory = windowsDirectory,
                    launcherFile = launcher,
                    processStarter = { command ->
                        script = File(command[command.indexOf("-File") + 1])
                        WindowsInstallerHandoffProcess {
                            processCancelled = true
                            val cancellation = File(command[command.indexOf("-CancellationPath") + 1])
                            val token = command[command.indexOf("-CancellationToken") + 1]
                            cancellationObserved = cancellation.readText() == token
                            true
                        }
                    },
                    readinessWaiter = { _, _ -> false },
                )
            }

            assertTrue(failure.message.orEmpty().contains("did not confirm"))
            assertTrue(processCancelled)
            assertTrue(cancellationObserved)
            assertFalse(requireNotNull(script).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

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
        assertEquals(
            DesktopUpdateTarget("windows", "msi", "x86_64"),
            detectDesktopUpdateTarget("Windows 11", "amd64", false, false, null),
        )
        assertNull(detectDesktopUpdateTarget("Windows 11", "arm64", false, false, null))
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
        assertEquals(
            "1.0.3131",
            detectInstalledDesktopPackageVersion(DesktopUpdateTarget("linux", "rpm", "x86_64")) { command ->
                assertEquals("%{VERSION}", command[2].substringAfter("--queryformat="))
                "1.0.3131"
            },
        )
        assertEquals(
            "1.0.3131",
            detectInstalledDesktopPackageVersion(DesktopUpdateTarget("linux", "deb", "x86_64")) { command ->
                assertTrue(command[2].endsWith("\${Version}"))
                "1.0.3131"
            },
        )
        assertNull(
            detectInstalledDesktopPackageVersion(DesktopUpdateTarget("windows", "msi", "x86_64")) { "unexpected" },
        )
        requireInstalledDesktopPackageVersion("1.0.3131", "1.0.3131")
        assertTrue(
            kotlin.test.assertFailsWith<IllegalStateException> {
                requireInstalledDesktopPackageVersion("1.0.3021", "1.0.3131")
            }.message.orEmpty().contains("1.0.3021"),
        )
        assertTrue(
            kotlin.test.assertFailsWith<IllegalStateException> {
                requireInstalledDesktopPackageVersion(null, "1.0.3131")
            }.message.orEmpty().contains("could not be read"),
        )
    }

    @Test
    fun onlyPackagedReleaseBuildsOfferDirectNativePackageUpdates() {
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
                openInstaller = { DesktopPackageInstallerOutcome.InstallerHandoffStarted },
            )
            val development = DesktopAppUpdater(
                preferences = node,
                buildIdentity = DesktopUpdateBuildIdentity("development", 0, "0.1.0", false, false),
                target = DesktopUpdateTarget("linux", "rpm", "x86_64"),
                updateDirectory = directory,
                openInstaller = { DesktopPackageInstallerOutcome.InstallerHandoffStarted },
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
                openInstaller = { DesktopPackageInstallerOutcome.InstallerHandoffStarted },
            )
            assertEquals(AppDistributionChannel.Development, distributionManaged.support().channel)
            assertFalse(distributionManaged.support().canCheckDirectUpdates)
            assertTrue(distributionManaged.support().explanation.contains("distribution-managed"))
            assertEquals(6L * 60L * 60L * 1_000L, DESKTOP_APP_UPDATE_CHECK_INTERVAL_MILLIS)
            val windowsRelease = DesktopAppUpdater(
                preferences = node,
                buildIdentity = DesktopUpdateBuildIdentity(
                    versionName = "0.1.0-alpha.1",
                    versionCode = 1,
                    packageVersion = "1.0.1",
                    releaseBuild = true,
                    directPackageUpdates = true,
                ),
                target = DesktopUpdateTarget("windows", "msi", "x86_64"),
                updateDirectory = directory,
                openInstaller = { DesktopPackageInstallerOutcome.InstallerHandoffStarted },
            )
            assertEquals(AppDistributionChannel.DirectDesktopPackage, windowsRelease.support().channel)
            assertTrue(windowsRelease.support().canCheckDirectUpdates)
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
                openInstaller = { DesktopPackageInstallerOutcome.InstallerHandoffStarted },
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
