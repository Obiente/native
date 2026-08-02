package dev.obiente.nextcloudnative.app

import java.io.File
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val registrar = PackagedWindowsCloudShellRegistrar(launcher.absolutePath) { command, timeout ->
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
                "Nextcloud Native - ada@cloud.example",
                byteArrayOf(0, 15, -1),
            ),
        )
        assertEquals(
            listOf(
                installation.resolve(WINDOWS_SHELL_REGISTRAR_NAME).absolutePath,
                "register",
                root.toAbsolutePath().normalize().toString(),
                accountId,
                "Nextcloud Native - ada@cloud.example",
                icon.absolutePath,
                "000fff",
            ),
            invocation,
        )
    }

    @Test
    fun missingPackagedFilesDoNotAttemptRegistration() {
        val installation = createTempDirectory("nextcloud-shell-missing").toFile()
        val launcher = installation.resolve("NextcloudNative.exe").apply { writeText("launcher") }
        var invoked = false
        val registrar = PackagedWindowsCloudShellRegistrar(launcher.absolutePath) { _, _ ->
            invoked = true
            0
        }

        assertFalse(registrar.available)
        assertFalse(registrar.unregister(installation.toPath(), "a5".repeat(32)))
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
        val registrar = PackagedWindowsCloudShellRegistrar(launcher.absolutePath) { command, _ ->
            invocations += command
            if (command[1] == "register") WINDOWS_SHELL_OWNED_PATH_CONFLICT_EXIT_CODE else 0
        }

        assertEquals(
            WindowsShellRegistrationResult.OwnedPathConflict,
            registrar.register(root, accountId, "Nextcloud Native - ada@cloud.example", byteArrayOf(1)),
        )
        assertTrue(registrar.unregister(root, accountId))
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
    fun recognizesCurrentAndLegacyAccountRootNames() {
        val accountId = "01".repeat(32)
        assertEquals(accountId, windowsCloudShellAccountId(File("C:/root/$accountId-v2").toPath()))
        assertEquals(accountId, windowsCloudShellAccountId(File("C:/root/$accountId").toPath()))
        assertEquals(null, windowsCloudShellAccountId(File("C:/root/not-an-account").toPath()))
    }

    @Test
    fun createsBoundedAccountSpecificDisplayNamesWithoutSecrets() {
        val first = windowsCloudShellDisplayName(
            NextcloudSession("https://cloud.example/nextcloud", "ada", "secret-one"),
        )
        val second = windowsCloudShellDisplayName(
            NextcloudSession("https://cloud.example/nextcloud", "grace", "secret-two"),
        )

        assertEquals("Nextcloud Native - ada@cloud.example", first)
        assertEquals("Nextcloud Native - grace@cloud.example", second)
        assertFalse(first.contains("secret"))
        assertTrue(first.length <= 128)
    }
}
