package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WindowsCloudShellRegistrarTest {
    @Test
    fun passesPathsAndIdentityAsSeparateArguments() {
        val installation = createTempDirectory("nextcloud-shell-install").toFile()
        val launcher = installation.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).writeText("helper")
        val icon = installation.resolve(WINDOWS_SHELL_ICON_NAME).apply { writeText("icon") }
        val root = installation.resolve("Cloud root; still one argument").toPath().createDirectories()
        val accountId = "a5".repeat(32)
        var invocation = emptyList<String>()
        val registrar = PackagedWindowsCloudShellRegistrar(launcher.absolutePath, recoveryRootsProvider = { emptyMap() }) { command, timeout ->
            invocation = command
            assertEquals(30L, timeout)
            0
        }

        assertTrue(registrar.available)
        assertEquals(
            WindowsShellRegistrationResult.Registered,
            registrar.register(
                root,
                accountId,
                "nati.ve - ada@cloud.example",
                byteArrayOf(0, 15, -1),
            ),
        )
        assertEquals(
            listOf(
                installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).absolutePath,
                "register",
                root.toAbsolutePath().normalize().toString(),
                accountId,
                "nati.ve - ada@cloud.example",
                icon.absolutePath,
                "000fff",
            ),
            invocation,
        )
    }

    @Test
    fun passesOnlyDurablyRecordedLiveRecoveryRoots() {
        val installation = createTempDirectory("nextcloud-shell-recovery-roots").toFile()
        val launcher = installation.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).writeText("helper")
        installation.resolve(WINDOWS_SHELL_ICON_NAME).writeText("icon")
        val root = installation.resolve("Current root").toPath().createDirectories()
        val recoveryRoot = installation.resolve("Previous root").toPath().createDirectories()
        val currentAccountId = "a5".repeat(32)
        val recoveryAccountId = "b6".repeat(32)
        var invocation = emptyList<String>()
        val registrar = PackagedWindowsCloudShellRegistrar(
            launcherPath = launcher.absolutePath,
            recoveryRootsProvider = {
                mapOf(
                    recoveryAccountId to recoveryRoot,
                    currentAccountId to root,
                    "invalid" to recoveryRoot,
                )
            },
        ) { command, _ ->
            invocation = command
            0
        }

        assertEquals(
            WindowsShellRegistrationResult.Registered,
            registrar.register(root, currentAccountId, "nati.ve", byteArrayOf(1)),
        )
        assertEquals(
            listOf(
                installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).absolutePath,
                "register",
                root.toAbsolutePath().normalize().toString(),
                currentAccountId,
                "nati.ve",
                installation.resolve(WINDOWS_SHELL_ICON_NAME).absolutePath,
                "01",
                "--recoverable-root",
                recoveryAccountId,
                recoveryRoot.normalize().toString(),
            ),
            invocation,
        )
    }

    @Test
    fun missingPackagedFilesDoNotAttemptRegistration() {
        val installation = createTempDirectory("nextcloud-shell-missing").toFile()
        val launcher = installation.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        var invoked = false
        val registrar = PackagedWindowsCloudShellRegistrar(launcher.absolutePath, recoveryRootsProvider = { emptyMap() }) { _, _ ->
            invoked = true
            0
        }

        assertFalse(registrar.available)
        assertEquals(
            WindowsShellUnregistrationResult.NotFound,
            registrar.unregister(installation.toPath(), "a5".repeat(32)),
        )
        assertFalse(invoked)
    }

    @Test
    fun classifiesOwnedPathConflictsAndPassesTheExactRootToCleanup() {
        val installation = createTempDirectory("nextcloud-shell-conflict").toFile()
        val launcher = installation.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).writeText("helper")
        installation.resolve(WINDOWS_SHELL_ICON_NAME).writeText("icon")
        val root = installation.resolve("Cloud root").toPath().createDirectories()
        val accountId = "b6".repeat(32)
        val invocations = mutableListOf<List<String>>()
        val registrar = PackagedWindowsCloudShellRegistrar(launcher.absolutePath, recoveryRootsProvider = { emptyMap() }) { command, _ ->
            invocations += command
            if (command[1] == "register") WINDOWS_SHELL_OWNED_PATH_CONFLICT_EXIT_CODE else 0
        }

        assertEquals(
            WindowsShellRegistrationResult.OwnedPathConflict,
            registrar.register(root, accountId, "nati.ve - ada@cloud.example", byteArrayOf(1)),
        )
        assertEquals(WindowsShellUnregistrationResult.Unregistered, registrar.unregister(root, accountId))
        assertEquals(
            listOf(
                installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).absolutePath,
                "unregister",
                root.toAbsolutePath().normalize().toString(),
                accountId,
            ),
            invocations.last(),
        )
    }

    @Test
    fun distinguishesMissingRegistrationsFromUnsafeUnregistrationFailures() {
        val installation = createTempDirectory("nextcloud-shell-unregister-results").toFile()
        val launcher = installation.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).writeText("helper")
        installation.resolve(WINDOWS_SHELL_ICON_NAME).writeText("icon")
        val root = installation.resolve("Cloud root").toPath().createDirectories()
        val accountId = "b7".repeat(32)
        var exitCode = WINDOWS_SHELL_REGISTRATION_NOT_FOUND_EXIT_CODE
        val registrar = PackagedWindowsCloudShellRegistrar(launcher.absolutePath, recoveryRootsProvider = { emptyMap() }) { _, _ -> exitCode }

        assertEquals(WindowsShellUnregistrationResult.NotFound, registrar.unregister(root, accountId))
        exitCode = WINDOWS_SHELL_UNSAFE_CONFLICT_EXIT_CODE
        assertEquals(WindowsShellUnregistrationResult.Rejected, registrar.unregister(root, accountId))
        exitCode = 1
        assertEquals(WindowsShellUnregistrationResult.Rejected, registrar.unregister(root, accountId))
    }

    @Test
    fun arbitraryShellFailurePreservesTheExistingCloudFilesRegistration() {
        val events = mutableListOf<String>()
        val mode = migrateWindowsSyncRootRegistration(
            shellAvailable = true,
            unregisterCloudFilesRoot = { events += "unregister"; true },
            registerBrandedShellRoot = {
                events += "shell"
                WindowsShellRegistrationResult.Failed
            },
            registerCloudFilesRoot = { events += "fallback" },
        )

        assertEquals(WindowsSyncRootRegistrationMode.CloudFilesOnly, mode)
        assertEquals(listOf("shell", "fallback"), events)
    }

    @Test
    fun ownedPathConflictMigratesAndRestoresFallbackOnRetryFailure() {
        val events = mutableListOf<String>()
        var attempts = 0
        val mode = migrateWindowsSyncRootRegistration(
            shellAvailable = true,
            unregisterCloudFilesRoot = { events += "unregister"; true },
            registerBrandedShellRoot = {
                events += "shell"
                if (attempts++ == 0) {
                    WindowsShellRegistrationResult.OwnedPathConflict
                } else {
                    WindowsShellRegistrationResult.Failed
                }
            },
            registerCloudFilesRoot = { events += "fallback" },
        )

        assertEquals(WindowsSyncRootRegistrationMode.CloudFilesOnly, mode)
        assertEquals(listOf("shell", "unregister", "shell", "fallback"), events)
    }

    @Test
    fun unsafeConflictDuringOwnedPathRetryFallsBackToCloudFilesRegistration() {
        val events = mutableListOf<String>()
        var attempts = 0
        val mode = migrateWindowsSyncRootRegistration(
            shellAvailable = true,
            unregisterCloudFilesRoot = { events += "unregister"; true },
            registerBrandedShellRoot = {
                events += "shell"
                if (attempts++ == 0) {
                    WindowsShellRegistrationResult.OwnedPathConflict
                } else {
                    WindowsShellRegistrationResult.UnsafeConflict
                }
            },
            registerCloudFilesRoot = { events += "fallback" },
        )

        assertEquals(WindowsSyncRootRegistrationMode.CloudFilesOnly, mode)
        assertEquals(listOf("shell", "unregister", "shell", "fallback"), events)
        assertEquals(2, attempts)
    }

    @Test
    fun repeatedOwnedPathConflictFallsBackToCloudFilesRegistration() {
        val events = mutableListOf<String>()
        var attempts = 0

        val mode = migrateWindowsSyncRootRegistration(
            shellAvailable = true,
            unregisterCloudFilesRoot = { events += "unregister"; true },
            registerBrandedShellRoot = {
                events += "shell"
                attempts++
                WindowsShellRegistrationResult.OwnedPathConflict
            },
            registerCloudFilesRoot = { events += "fallback" },
        )

        assertEquals(WindowsSyncRootRegistrationMode.CloudFilesOnly, mode)
        assertEquals(listOf("shell", "unregister", "shell", "fallback"), events)
        assertEquals(2, attempts)
    }

    @Test
    fun successfulShellRegistrationDoesNotCreateASecondRegistration() {
        val events = mutableListOf<String>()
        val mode = migrateWindowsSyncRootRegistration(
            shellAvailable = true,
            unregisterCloudFilesRoot = { events += "unregister"; true },
            registerBrandedShellRoot = {
                events += "shell"
                WindowsShellRegistrationResult.Registered
            },
            registerCloudFilesRoot = { events += "fallback" },
        )

        assertEquals(WindowsSyncRootRegistrationMode.BrandedShell, mode)
        assertEquals(listOf("shell"), events)
    }

    @Test
    fun unsafeRegistrationConflictFallsBackToPathBasedRegistration() {
        val events = mutableListOf<String>()
        val mode = migrateWindowsSyncRootRegistration(
            shellAvailable = true,
            unregisterCloudFilesRoot = { events += "unregister"; true },
            registerBrandedShellRoot = {
                events += "shell"
                WindowsShellRegistrationResult.UnsafeConflict
            },
            registerCloudFilesRoot = { events += "fallback" },
        )

        assertEquals(WindowsSyncRootRegistrationMode.CloudFilesOnly, mode)
        assertEquals(listOf("shell", "unregister", "shell", "fallback"), events)
    }

    @Test
    fun recognizesOnlyTheBrandedAccountRootGeneration() {
        val accountId = "01".repeat(32)
        assertEquals(accountId, windowsCloudShellAccountId(File("C:/root/$accountId-v2").toPath()))
        assertEquals(null, windowsCloudShellAccountId(File("C:/root/$accountId").toPath()))
        assertEquals(null, windowsCloudShellAccountId(File("C:/root/not-an-account").toPath()))
    }

    @Test
    fun createsBoundedAccountSpecificDisplayNamesWithoutSecrets() {
        val firstSession = NextcloudSession("https://cloud.example/nextcloud", "ada", "secret-one")
        val secondSession = NextcloudSession("https://cloud.example/nextcloud", "grace", "secret-two")
        val first = windowsCloudShellDisplayName(firstSession)
        val second = windowsCloudShellDisplayName(secondSession)

        assertEquals(
            "nati.ve - ada@cloud.example [${desktopFileCacheAccountId(firstSession).take(12)}]",
            first,
        )
        assertEquals(
            "nati.ve - grace@cloud.example [${desktopFileCacheAccountId(secondSession).take(12)}]",
            second,
        )
        assertFalse(first.contains("secret"))
        assertTrue(first.length <= 128)
    }

    @Test
    fun distinguishesServersThatShareAHostAndLogin() {
        val first = windowsCloudShellDisplayName(
            NextcloudSession("https://cloud.example:8443/one", "ada", "secret-one"),
        )
        val second = windowsCloudShellDisplayName(
            NextcloudSession("https://cloud.example:9443/two", "ada", "secret-two"),
        )

        assertTrue(first.startsWith("nati.ve - ada@cloud.example ["))
        assertTrue(second.startsWith("nati.ve - ada@cloud.example ["))
        assertNotEquals(first, second)
        assertFalse(first.contains("secret"))
        assertFalse(second.contains("secret"))
    }

    @Test
    fun waitsForForcedRegistrarTerminationBeforeReturning() {
        val process = StubbornRegistrarProcess()

        terminateWindowsShellRegistrar(process)

        assertTrue(process.destroyCalled)
        assertTrue(process.destroyForciblyCalled)
        assertTrue(process.waitedForForcedExit)
        assertFalse(process.isAlive)
    }

    private class StubbornRegistrarProcess : Process() {
        var destroyCalled = false
        var destroyForciblyCalled = false
        var waitedForForcedExit = false
        private var alive = true

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int {
            waitedForForcedExit = true
            alive = false
            return 137
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int {
            check(!alive)
            return 137
        }
        override fun destroy() {
            destroyCalled = true
        }
        override fun destroyForcibly(): Process {
            destroyForciblyCalled = true
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
