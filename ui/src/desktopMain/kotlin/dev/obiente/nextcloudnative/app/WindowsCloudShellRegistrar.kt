package dev.obiente.nextcloudnative.app

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal interface WindowsCloudShellRegistrar {
    val available: Boolean
    fun register(
        root: Path,
        accountId: String,
        displayName: String,
        syncRootIdentity: ByteArray,
    ): WindowsShellRegistrationResult
    fun unregister(root: Path, accountId: String): WindowsShellUnregistrationResult
}

internal enum class WindowsShellRegistrationResult {
    Registered,
    OwnedPathConflict,
    UnsafeConflict,
    Failed,
}

internal enum class WindowsShellUnregistrationResult {
    Unregistered,
    NotFound,
    Rejected,
}

internal class PackagedWindowsCloudShellRegistrar(
    launcherPath: String? = packagedDesktopLauncherPath(),
    private val recoveryRootsProvider: () -> Map<String, Path> = {
        runCatching(::pagedPersistedWindowsCloudFilesRecoveryRoots).getOrDefault(emptyMap())
    },
    private val processRunner: (List<String>, Long) -> Int? = ::runWindowsShellRegistrar,
) : WindowsCloudShellRegistrar {
    private val installationDirectory = launcherPath
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.parentFile
    private val helper = installationDirectory?.resolve(WINDOWS_SHELL_REGISTRAR_NAME)
    private val icon = installationDirectory?.resolve(WINDOWS_SHELL_ICON_NAME)

    override val available: Boolean
        get() = helper?.isFile == true && icon?.isFile == true

    override fun register(
        root: Path,
        accountId: String,
        displayName: String,
        syncRootIdentity: ByteArray,
    ): WindowsShellRegistrationResult {
        requireWindowsShellAccountId(accountId)
        requireWindowsShellDisplayName(displayName)
        require(syncRootIdentity.isNotEmpty() && syncRootIdentity.size <= MAX_SYNC_ROOT_IDENTITY_BYTES)
        val normalizedRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedRoot) && !Files.isSymbolicLink(normalizedRoot))
        val executable = helper?.takeIf(File::isFile) ?: return WindowsShellRegistrationResult.Failed
        val iconResource = icon?.takeIf(File::isFile) ?: return WindowsShellRegistrationResult.Failed
        val command = mutableListOf(
            executable.absolutePath,
            "register",
            normalizedRoot.toString(),
            accountId,
            displayName,
            iconResource.absolutePath,
            syncRootIdentity.lowercaseHex(),
        )
        recoveryRootsProvider()
            .asSequence()
            .filter { (recoveryAccountId, recoveryRoot) ->
                recoveryAccountId != accountId &&
                    isWindowsShellAccountId(recoveryAccountId) &&
                    recoveryRoot.isAbsolute &&
                    recoveryRoot.toString().length <= MAX_RECOVERY_ROOT_PATH_CHARACTERS &&
                    Files.isDirectory(recoveryRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(recoveryRoot)
            }
            .sortedBy(Map.Entry<String, Path>::key)
            .forEach { (recoveryAccountId, recoveryRoot) ->
                command += listOf(
                    RECOVERABLE_ROOT_ARGUMENT,
                    recoveryAccountId,
                    recoveryRoot.normalize().toString(),
                )
            }
        val exitCode = runCatching {
            processRunner(
                command,
                REGISTRATION_TIMEOUT_SECONDS,
            )
        }.getOrNull()
        return when (exitCode) {
            0 -> WindowsShellRegistrationResult.Registered
            WINDOWS_SHELL_OWNED_PATH_CONFLICT_EXIT_CODE -> WindowsShellRegistrationResult.OwnedPathConflict
            WINDOWS_SHELL_UNSAFE_CONFLICT_EXIT_CODE -> WindowsShellRegistrationResult.UnsafeConflict
            else -> WindowsShellRegistrationResult.Failed
        }
    }

    override fun unregister(root: Path, accountId: String): WindowsShellUnregistrationResult {
        requireWindowsShellAccountId(accountId)
        val normalizedRoot = root.toAbsolutePath().normalize()
        val executable = helper?.takeIf(File::isFile) ?: return WindowsShellUnregistrationResult.NotFound
        val exitCode = runCatching {
            processRunner(
                listOf(executable.absolutePath, "unregister", normalizedRoot.toString(), accountId),
                REGISTRATION_TIMEOUT_SECONDS,
            )
        }.getOrNull()
        return when (exitCode) {
            0 -> WindowsShellUnregistrationResult.Unregistered
            WINDOWS_SHELL_REGISTRATION_NOT_FOUND_EXIT_CODE -> WindowsShellUnregistrationResult.NotFound
            else -> WindowsShellUnregistrationResult.Rejected
        }
    }

    private companion object {
        const val MAX_SYNC_ROOT_IDENTITY_BYTES = 4_096
        const val MAX_RECOVERY_ROOT_PATH_CHARACTERS = 1_024
        const val REGISTRATION_TIMEOUT_SECONDS = 30L
        const val RECOVERABLE_ROOT_ARGUMENT = "--recoverable-root"
    }
}

internal enum class WindowsSyncRootRegistrationMode {
    BrandedShell,
    CloudFilesOnly,
}

internal fun migrateWindowsSyncRootRegistration(
    shellAvailable: Boolean,
    unregisterCloudFilesRoot: () -> Boolean,
    registerBrandedShellRoot: () -> WindowsShellRegistrationResult,
    registerCloudFilesRoot: () -> Unit,
): WindowsSyncRootRegistrationMode {
    fun tryBrandedShellRootAfterCleanup(): WindowsSyncRootRegistrationMode? {
        if (!unregisterCloudFilesRoot()) return null
        return when (registerBrandedShellRoot()) {
            WindowsShellRegistrationResult.Registered -> WindowsSyncRootRegistrationMode.BrandedShell
            WindowsShellRegistrationResult.OwnedPathConflict,
            WindowsShellRegistrationResult.UnsafeConflict,
            WindowsShellRegistrationResult.Failed -> null
        }
    }

    if (shellAvailable) {
        when (registerBrandedShellRoot()) {
            WindowsShellRegistrationResult.Registered -> return WindowsSyncRootRegistrationMode.BrandedShell
            WindowsShellRegistrationResult.OwnedPathConflict,
            WindowsShellRegistrationResult.UnsafeConflict -> {
                tryBrandedShellRootAfterCleanup()?.let { return it }
            }
            WindowsShellRegistrationResult.Failed -> Unit
        }
    }
    registerCloudFilesRoot()
    return WindowsSyncRootRegistrationMode.CloudFilesOnly
}

internal fun windowsCloudShellAccountId(root: Path): String? {
    val name = root.fileName?.toString() ?: return null
    // The unsuffixed generation predates shell branding. Looking it up by account ID can resolve
    // the active -v2 registration and must never be used to unregister that different root.
    if (!name.endsWith(WINDOWS_CLOUD_ROOT_GENERATION_SUFFIX)) return null
    val accountId = name.removeSuffix(WINDOWS_CLOUD_ROOT_GENERATION_SUFFIX)
    return accountId.takeIf(::isWindowsShellAccountId)
}

private fun requireWindowsShellAccountId(accountId: String) {
    require(isWindowsShellAccountId(accountId))
}

private fun requireWindowsShellDisplayName(displayName: String) {
    require(
        displayName.isNotBlank() &&
            displayName.length <= WINDOWS_SHELL_DISPLAY_NAME_MAX_CHARACTERS &&
            displayName.none(Char::isISOControl),
    )
}

internal fun windowsCloudShellDisplayName(session: NextcloudSession): String {
    val accountId = desktopFileCacheAccountId(session)
    requireWindowsShellAccountId(accountId)
    val login = session.loginName.windowsShellLabelPart(WINDOWS_SHELL_ACCOUNT_LABEL_PART_MAX_CHARACTERS)
    val host = runCatching { URI(session.serverUrl).host.orEmpty() }
        .getOrDefault("")
        .lowercase()
        .windowsShellLabelPart(WINDOWS_SHELL_ACCOUNT_LABEL_PART_MAX_CHARACTERS)
    val accountLabel = when {
        login.isNotEmpty() && host.isNotEmpty() -> "$login@$host"
        login.isNotEmpty() -> login
        host.isNotEmpty() -> host
        else -> "account"
    }
    return "nati.ve - $accountLabel [${accountId.take(WINDOWS_SHELL_ACCOUNT_TAG_CHARACTERS)}]"
}

private fun String.windowsShellLabelPart(maxCharacters: Int): String =
    trim()
        .filterNot(Char::isISOControl)
        .replace(Regex("\\s+"), " ")
        .takeWholeCodePoints(maxCharacters)

private fun String.takeWholeCodePoints(maxCharacters: Int): String {
    if (length <= maxCharacters) return this
    val end = if (
        maxCharacters > 0 &&
        this[maxCharacters - 1].isHighSurrogate() &&
        this[maxCharacters].isLowSurrogate()
    ) {
        maxCharacters - 1
    } else {
        maxCharacters
    }
    return substring(0, end)
}

private fun isWindowsShellAccountId(accountId: String): Boolean =
    accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' }

private fun ByteArray.lowercaseHex(): String {
    val output = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        output[index * 2] = HEX_DIGITS[value ushr 4]
        output[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return output.concatToString()
}

private fun runWindowsShellRegistrar(command: List<String>, timeoutSeconds: Long): Int? {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val completed = try {
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        terminateWindowsShellRegistrar(process)
        Thread.currentThread().interrupt()
        return null
    }
    if (completed) return process.exitValue()
    terminateWindowsShellRegistrar(process)
    return null
}

internal fun terminateWindowsShellRegistrar(process: Process) {
    var interrupted = false
    runCatching { process.destroy() }
    val exitedDuringGrace = try {
        process.waitFor(WINDOWS_SHELL_TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        interrupted = true
        false
    }
    if (!exitedDuringGrace && process.isAlive) {
        runCatching { process.destroyForcibly() }
        while (process.isAlive) {
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private const val WINDOWS_CLOUD_ROOT_GENERATION_SUFFIX = "-v2"
internal const val WINDOWS_SHELL_REGISTRAR_NAME = "NextcloudNativeShellRegistrar.exe"
internal const val WINDOWS_SHELL_ICON_NAME = "NextcloudNative.ico"
internal const val WINDOWS_SHELL_OWNED_PATH_CONFLICT_EXIT_CODE = 3
internal const val WINDOWS_SHELL_REGISTRATION_NOT_FOUND_EXIT_CODE = 4
internal const val WINDOWS_SHELL_UNSAFE_CONFLICT_EXIT_CODE = 5
private const val WINDOWS_SHELL_DISPLAY_NAME_MAX_CHARACTERS = 128
private const val WINDOWS_SHELL_ACCOUNT_LABEL_PART_MAX_CHARACTERS = 44
private const val WINDOWS_SHELL_ACCOUNT_TAG_CHARACTERS = 12
private const val WINDOWS_SHELL_TERMINATION_GRACE_SECONDS = 2L
private const val HEX_DIGITS = "0123456789abcdef"
